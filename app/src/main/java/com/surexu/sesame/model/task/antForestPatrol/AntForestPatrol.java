package com.surexu.sesame.model.task.antForestPatrol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.ChoiceModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.model.task.antForest.AntForestRpcCall;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

/**
 * 森林巡护（独立模块）——完整巡护链路。
 * <p>
 * 合并了三块能力，对齐 AG cf2474c3 + 990ea45f：
 * <ul>
 *   <li><b>旧版巡护</b>（LegacyPatrolWorkflow）：掷骰子走路、步数兑换、地图切换、动物伙伴派遣与能量收取</li>
 *   <li><b>新版大富翁巡护</b>（MonopolyPatrolWorkflow）：大富翁式掷骰、事件确认、任务领骰子、保护证书兑换</li>
 *   <li><b>动物碎片合成</b>（LegacyPatrolWorkflow.queryAnimalAndPiece）：查询并合成可合成的动物碎片</li>
 * </ul>
 */
public class AntForestPatrol extends ModelTask {

    private static final String TAG = AntForestPatrol.class.getSimpleName();
    private static final String PREFIX = "森林巡护🦌";
    private static final String CREATURE_STATUS_USING = "using";

    // ---- 字段声明 ----
    private BooleanModelField monopolyPatrol;
    private BooleanModelField monopolyTasks;
    private BooleanModelField legacyPatrol;
    private BooleanModelField combineAnimalPiece;
    private BooleanModelField collectAnimalEnergy;
    private BooleanModelField dispatchAnimal;
    private ChoiceModelField dispatchPriority;
    private BooleanModelField exchangeCertificate;
    private IntegerModelField executeInterval;

    @Override
    public String getName() {
        return "森林巡护";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(monopolyPatrol = new BooleanModelField("monopolyPatrol", "新版巡护 | 开启", false));
        modelFields.addField(monopolyTasks = new BooleanModelField("monopolyTasks", "新版巡护 | 自动任务", false));
        modelFields.addField(legacyPatrol = new BooleanModelField("legacyPatrol", "旧版巡护 | 开启", false));
        modelFields.addField(combineAnimalPiece = new BooleanModelField("combineAnimalPiece", "旧版巡护 | 合成动物碎片", false));
        modelFields.addField(collectAnimalEnergy = new BooleanModelField("collectAnimalEnergy", "动物伙伴 | 领取能量", false));
        modelFields.addField(dispatchAnimal = new BooleanModelField("dispatchAnimal", "动物伙伴 | 自动派遣", false));
        modelFields.addField(dispatchPriority = new ChoiceModelField("dispatchPriority", "动物伙伴 | 派遣优先级", 0, new String[]{"新版优先", "旧版优先"}));
        modelFields.addField(exchangeCertificate = new BooleanModelField("exchangeCertificate", "新版巡护 | 自动兑换保护证书", false));
        modelFields.addField(executeInterval = new IntegerModelField("executeInterval", "操作间隔(毫秒)", 800, 500, null));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.record(PREFIX + "任务暂停⏸️当前为仅收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            String uid = UserIdMap.getCurrentUid();
            if (uid == null || uid.isEmpty()) {
                Log.i(TAG, "无法获取当前用户，跳过森林巡护");
                return;
            }
            Log.record(PREFIX + "执行开始");

            // ① 旧版动物能量
            if (collectAnimalEnergy.getValue()) {
                collectLegacyEnergy(uid);
                sleepInterval();
            }
            // ② 新版动物能量
            if (collectAnimalEnergy.getValue()) {
                collectMonopolyEnergy(uid);
                sleepInterval();
            }
            // ③ 旧版巡护（掷骰子）
            if (legacyPatrol.getValue()) {
                LegacyPatrolWorkflow.queryUserPatrol();
                sleepInterval();
            }
            // ④ 动物碎片合成
            if (combineAnimalPiece.getValue()) {
                LegacyPatrolWorkflow.queryAnimalAndPiece();
                sleepInterval();
            }
            // ⑤ 新版大富翁巡护
            if (monopolyPatrol.getValue()) {
                MonopolyPatrolWorkflow.run(monopolyTasks.getValue());
                sleepInterval();
            }
            // ⑥ 保护证书兑换
            if (exchangeCertificate.getValue()) {
                MonopolyPatrolWorkflow.exchangeCertificate();
                sleepInterval();
            }
            // ⑦ 动物派遣（旧版+新版，按优先级）
            if (dispatchAnimal.getValue()) {
                dispatchAnimal(uid);
            }

            Log.record(PREFIX + "执行结束");
        } catch (Throwable t) {
            Log.i(TAG, "AntForestPatrol.run err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void sleepInterval() {
        Integer interval = executeInterval.getValue();
        TimeUtil.sleep(interval == null ? 800L : interval.longValue());
    }

    // ======== 响应解包 =====================================================

    private static JSONObject parsePatrolResponse(String raw) {
        try {
            JSONObject response = new JSONObject(raw);
            JSONObject resData = response.optJSONObject("resData");
            return resData != null ? resData : response;
        } catch (JSONException e) {
            Log.printStackTrace(TAG, e);
            return new JSONObject();
        }
    }

    // ======== 旧版动物能量领取 ============================================

    private JSONArray queryLegacyAnimals() {
        try {
            JSONObject home = parsePatrolResponse(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, home)) {
                Log.record(PREFIX + "查询动物首页失败");
                return null;
            }
            if ("Team".equals(home.optString("nextAction"))) {
                JSONObject teamResult = home.optJSONObject("teamHomeResult");
                JSONObject mainMember = teamResult != null ? teamResult.optJSONObject("mainMember") : null;
                return mainMember != null ? mainMember.optJSONArray("usingUserProps") : null;
            }
            return home.optJSONArray("usingUserPropsNew");
        } catch (Throwable t) {
            Log.i(TAG, "queryLegacyAnimals err:");
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    private void collectLegacyEnergy(String uid) {
        try {
            JSONArray animals = queryLegacyAnimals();
            if (animals == null) return;
            for (int i = 0; i < animals.length(); i++) {
                JSONObject animal = animals.optJSONObject(i);
                if (animal == null) continue;
                if (!"animal".equals(animal.optString("propGroup"))) continue;
                JSONObject ext;
                try {
                    ext = new JSONObject(animal.optString("extInfo", "{}"));
                } catch (JSONException e) {
                    continue;
                }
                if (ext.optBoolean("isCollected")) continue;
                JSONObject response = parsePatrolResponse(
                        AntForestRpcCall.collectAnimalRobEnergy(
                                animal.optString("propId"),
                                animal.optString("propType"),
                                ext.optString("shortDay")));
                if (MessageUtil.checkResultCode(TAG, response)) {
                    int energy = ext.optInt("energy", 0);
                    Log.forest(PREFIX + "收取[" + optString(
                            optJSON(ext, "animal"), "name") + "]派遣能量[" + energy + "g]");
                }
                queryLegacyAnimals(); // 刷新状态
                return; // 每次只收一个
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectLegacyEnergy err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // ======== 新版动物能量领取 ============================================

    private JSONObject queryUsingCreature(String uid) {
        try {
            JSONObject jo = new JSONObject(AntForestPatrolRpcCall.queryUsingCreatureInfo(uid));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return null;
            }
            return jo;
        } catch (Throwable t) {
            Log.i(TAG, "queryUsingCreature err:");
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    private void collectMonopolyEnergy(String uid) {
        try {
            JSONObject state = queryUsingCreature(uid);
            if (state == null) return;
            JSONObject creature = state.optJSONObject("userCreatureVO");
            if (creature == null) return;
            JSONObject energy = creature.optJSONObject("robEnergyVO");
            if (energy == null) return;
            if (energy.optBoolean("energyIsCollect") || energy.optInt("yesterdayRobEnergy", 0) == 0)
                return;
            String creatureCode = creature.optString("creatureCode");
            String shortDay = energy.optString("yesterdayShortDay");
            if (creatureCode.isEmpty() || shortDay.isEmpty()) return;

            JSONObject collected = new JSONObject(
                    AntForestPatrolRpcCall.collectMonopolyCreatureEnergy(creatureCode, shortDay));
            if (!MessageUtil.checkSuccess(TAG, collected)) return;
            int collectedEnergy = collected.optInt("collectedEnergy", -1);
            if (collectedEnergy < 0) {
                Log.record(PREFIX + "[" + creature.optString("creatureName", creatureCode)
                        + "]领取成功但未返回到账量，待回查");
            } else {
                Log.forest(PREFIX + "收取[" + creature.optString("creatureName", creatureCode)
                        + "]派遣能量[" + collectedEnergy + "g]");
            }

            // 回查确认
            sleepInterval();
            JSONObject refreshed = queryUsingCreature(uid);
            JSONObject refreshedEnergy = refreshed == null ? null
                    : (refreshed.optJSONObject("userCreatureVO") == null ? null
                    : refreshed.optJSONObject("userCreatureVO").optJSONObject("robEnergyVO"));
            if (refreshedEnergy == null || !refreshedEnergy.optBoolean("energyIsCollect")) {
                Log.record(PREFIX + "动物伙伴能量尚未确认领取，留待下次调度查询");
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectMonopolyEnergy err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // ======== 动物派遣（旧版+新版，按优先级）==============================

    private void dispatchAnimal(String uid) {
        try {
            // 先检查旧版动物是否已在工作
            JSONArray oldAnimals = queryLegacyAnimals();
            if (oldAnimals == null) {
                Log.record(PREFIX + "查询旧版动物失败，跳过派遣");
                return;
            }
            boolean oldWorking = false;
            if (oldAnimals != null) {
                for (int i = 0; i < oldAnimals.length(); i++) {
                    JSONObject a = oldAnimals.optJSONObject(i);
                    if (a != null && "animal".equals(a.optString("propGroup"))) {
                        oldWorking = true;
                        break;
                    }
                }
            }
            if (oldWorking) {
                Log.record(PREFIX + "已有旧版动物工作，保留当前伙伴");
                return;
            }

            // 检查新版动物是否已在工作
            JSONObject using = queryUsingCreature(uid);
            Boolean usingNew = using != null && using.has("usingMonopolyCreature")
                    ? using.optBoolean("usingMonopolyCreature") : null;
            if (Boolean.TRUE.equals(usingNew)) {
                Log.record(PREFIX + "已有新版动物工作，保留当前伙伴");
                return;
            }
            if (usingNew == null) {
                Log.record(PREFIX + "新版占用尚未确认，仅保留旧版非强制派遣请求");
                dispatchLegacyAnimal();
                return;
            }

            // 按优先级派遣
            boolean newFirst = dispatchPriority.getValue() == 0;
            boolean[] sources = newFirst ? new boolean[]{true, false} : new boolean[]{false, true};
            for (boolean tryNew : sources) {
                Boolean result = tryNew ? dispatchMonopolyAnimal() : dispatchLegacyAnimal();
                if (result == null) continue; // 查询失败
                if (result) {
                    queryLegacyAnimals();
                    queryUsingCreature(uid);
                    return;
                }
            }
            Log.record(PREFIX + "当前没有可派遣动物");
        } catch (Throwable t) {
            Log.i(TAG, "dispatchAnimal err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /** @return true=已派遣, false=无候选, null=查询失败 */
    private Boolean dispatchLegacyAnimal() {
        try {
            JSONObject raw = new JSONObject(AntForestRpcCall.queryAnimalPropList());
            if (!MessageUtil.checkResultCode(TAG, raw)) return null;
            JSONArray animals = raw.optJSONArray("animalProps");
            if (animals == null) {
                Log.record(PREFIX + "旧版候选缺少 animalProps");
                return null;
            }
            LegacyPatrolWorkflow.AnimalPropSelection selected =
                    LegacyPatrolWorkflow.selectBestAnimalProp(animals);
            if (selected == null) return false;
            LegacyPatrolWorkflow.consumeAnimalProp(selected);
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "dispatchLegacyAnimal err:");
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /** @return true=已派遣, false=无候选, null=查询失败 */
    private Boolean dispatchMonopolyAnimal() {
        try {
            JSONObject response = parsePatrolResponse(
                    AntForestPatrolRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkResultCode(TAG, response)) return null;
            if (response.optBoolean("usingMonopolyCreature")) return true;
            JSONArray creatureList = response.optJSONArray("creatureList");
            if (creatureList == null) {
                Log.record(PREFIX + "新版候选缺少 creatureList");
                return null;
            }

            JSONObject selected = selectCreature(creatureList);
            if (selected == null) return false;

            JSONObject assigned = parsePatrolResponse(
                    AntForestPatrolRpcCall.assignMonopolyCreature(
                            selected.optString("creatureCode")));
            if (MessageUtil.checkResultCode(TAG, assigned)) {
                Log.forest(PREFIX + "派遣新版动物["
                        + selected.optString("creatureName",
                        selected.optString("creatureCode")) + "]");
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "dispatchMonopolyAnimal err:");
            Log.printStackTrace(TAG, t);
            return null;
        }
    }

    /** 从候选列表中选出最优动物伙伴 */
    private JSONObject selectCreature(JSONArray creatureList) {
        JSONObject best = null;
        int bestEnergy = Integer.MIN_VALUE;
        boolean allHaveInitialEnergy = true;
        for (int i = 0; i < creatureList.length(); i++) {
            JSONObject candidate = creatureList.optJSONObject(i);
            if (candidate == null || !isDispatchable(candidate)) continue;
            if (!candidate.has("initialRobEnergy") || candidate.isNull("initialRobEnergy")) {
                allHaveInitialEnergy = false;
            }
            int initialEnergy = candidate.optInt("initialRobEnergy", 0);
            if (best == null || initialEnergy > bestEnergy) {
                best = candidate;
                bestEnergy = initialEnergy;
            }
        }
        if (best == null) return null;
        if (!allHaveInitialEnergy) {
            for (int i = 0; i < creatureList.length(); i++) {
                JSONObject candidate = creatureList.optJSONObject(i);
                if (candidate != null && isDispatchable(candidate)) return candidate;
            }
        }
        return best;
    }

    private boolean isDispatchable(JSONObject candidate) {
        if (candidate.optString("creatureCode").isEmpty()) return false;
        if (CREATURE_STATUS_USING.equals(candidate.optString("status"))) return false;
        if (candidate.optLong("assignTime", 0L) > 0) return false;
        JSONObject energy = candidate.optJSONObject("robEnergyVO");
        if (energy != null) {
            if (energy.has("robRemainDays") && energy.optInt("robRemainDays") <= 0) return false;
            int maxWorkDays = energy.optInt("maxWorkDays", 0);
            if (maxWorkDays > 0 && energy.optInt("alreadyWorkDays", 0) >= maxWorkDays) return false;
        }
        return true;
    }

    // ---- JSON helpers ----------------------------------------------------

    private static JSONObject optJSON(JSONObject container, String... path) {
        JSONObject cur = container;
        for (String key : path) {
            if (cur == null) return null;
            cur = cur.optJSONObject(key);
        }
        return cur;
    }

    private static String optString(JSONObject jo, String key) {
        return jo != null ? jo.optString(key, "") : "";
    }
}