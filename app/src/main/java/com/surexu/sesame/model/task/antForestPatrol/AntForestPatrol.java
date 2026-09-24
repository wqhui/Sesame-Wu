package com.surexu.sesame.model.task.antForestPatrol;

import org.json.JSONArray;
import org.json.JSONObject;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.StringUtil;
import com.surexu.sesame.util.TimeUtil;
import com.surexu.sesame.util.idMap.UserIdMap;

/**
 * 森林巡护（独立模块）——新版动物伙伴链路。
 * <p>
 * 只包含三块相互独立、可单独开关的能力，改动不触及森林模块原有巡护流程：
 * <ul>
 *   <li>动物伙伴领取派遣能量</li>
 *   <li>动物伙伴自动派遣（已有伙伴在工作时保留，不抢占）</li>
 *   <li>新版巡护自动兑换保护证书</li>
 * </ul>
 * 新版巡护的完整推进玩法（地图掷骰、事件确认、红山动物园任务）与旧版巡护搬迁不在此模块内。
 */
public class AntForestPatrol extends ModelTask {

    private static final String TAG = AntForestPatrol.class.getSimpleName();

    private static final String PREFIX = "森林巡护🦌";

    /** 新版动物未确认占用时的状态标识 */
    private static final String CREATURE_STATUS_USING = "using";

    private BooleanModelField collectAnimalEnergy;
    private BooleanModelField dispatchAnimal;
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
        modelFields.addField(collectAnimalEnergy = new BooleanModelField("collectAnimalEnergy", "动物伙伴 | 领取能量", false));
        modelFields.addField(dispatchAnimal = new BooleanModelField("dispatchAnimal", "动物伙伴 | 自动派遣", false));
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

            if (collectAnimalEnergy.getValue()) {
                collectAnimalEnergy(uid);
                sleepInterval();
            }
            if (dispatchAnimal.getValue()) {
                dispatchAnimal(uid);
                sleepInterval();
            }
            if (exchangeCertificate.getValue()) {
                exchangeCertificate();
            }
        } catch (Throwable t) {
            Log.i(TAG, "AntForestPatrol.run err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void sleepInterval() {
        Integer interval = executeInterval.getValue();
        TimeUtil.sleep(interval == null ? 800L : interval.longValue());
    }

    /**
     * 领取新版动物伙伴派遣能量。
     * <p>
     * 先查占用中的伙伴与能量状态，仅在「未领取且昨日有可领取能量」时领取，领取后回查确认。
     */
    private void collectAnimalEnergy(String uid) {
        if (Status.hasFlagToday("antForestPatrol::collectAnimalEnergy")) {
            return;
        }
        try {
            JSONObject state = queryUsingCreature(uid);
            if (state == null) {
                return;
            }
            JSONObject creature = state.optJSONObject("userCreatureVO");
            if (creature == null) {
                Log.record(PREFIX + "当前没有占用中的动物伙伴");
                Status.flagToday("antForestPatrol::collectAnimalEnergy");
                return;
            }
            JSONObject energy = creature.optJSONObject("robEnergyVO");
            if (energy == null) {
                Log.record(PREFIX + "动物伙伴缺少能量状态，跳过");
                return;
            }
            if (energy.optBoolean("energyIsCollect") || energy.optInt("yesterdayRobEnergy", 0) == 0) {
                Status.flagToday("antForestPatrol::collectAnimalEnergy");
                return;
            }
            String creatureCode = creature.optString("creatureCode");
            String shortDay = energy.optString("yesterdayShortDay");
            if (creatureCode.isEmpty() || shortDay.isEmpty()) {
                Log.record(PREFIX + "动物伙伴能量缺少领取标识，跳过");
                return;
            }

            JSONObject collected = new JSONObject(
                    AntForestPatrolRpcCall.collectMonopolyCreatureEnergy(creatureCode, shortDay));
            if (!MessageUtil.checkSuccess(TAG, collected)) {
                Log.record(PREFIX + "领取[" + creature.optString("creatureName", creatureCode) + "]派遣能量失败");
                return;
            }
            int collectedEnergy = collected.optInt("collectedEnergy", -1);
            if (collectedEnergy < 0) {
                Log.record(PREFIX + "[" + creature.optString("creatureName", creatureCode) + "]领取成功但未返回到账量，待回查");
            } else {
                Log.forest(PREFIX + "收取[" + creature.optString("creatureName", creatureCode)
                        + "]派遣能量[" + collectedEnergy + "g]");
            }
            Status.flagToday("antForestPatrol::collectAnimalEnergy");

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
            Log.i(TAG, "collectAnimalEnergy err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 自动派遣新版动物伙伴。
     * <p>
     * 已有伙伴在工作时直接保留，不抢占；候选按「已工作日数未达上限、未被占用」筛选，
     * 若服务端下发了 initialRobEnergy 则取收益最高的一个。
     */
    private void dispatchAnimal(String uid) {
        if (Status.hasFlagToday("antForestPatrol::dispatchAnimal")) {
            return;
        }
        try {
            JSONObject entry = new JSONObject(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkSuccess(TAG, entry)) {
                Log.record(PREFIX + "查询新版巡护入口失败，跳过派遣");
                return;
            }
            if (entry.optBoolean("usingMonopolyCreature")) {
                Log.record(PREFIX + "已有动物伙伴在工作，保留当前伙伴");
                Status.flagToday("antForestPatrol::dispatchAnimal");
                return;
            }
            JSONArray creatureList = entry.optJSONArray("creatureList");
            if (creatureList == null) {
                Log.record(PREFIX + "新版巡护入口缺少动物候选列表，跳过派遣");
                return;
            }

            JSONObject selected = selectCreature(creatureList);
            if (selected == null) {
                Log.record(PREFIX + "当前没有可派遣的动物伙伴");
                Status.flagToday("antForestPatrol::dispatchAnimal");
                return;
            }
            String creatureCode = selected.optString("creatureCode");
            if (creatureCode.isEmpty()) {
                return;
            }

            JSONObject assigned = new JSONObject(AntForestPatrolRpcCall.assignMonopolyCreature(creatureCode));
            if (MessageUtil.checkSuccess(TAG, assigned)) {
                Log.forest(PREFIX + "派遣动物伙伴[" + selected.optString("creatureName", creatureCode) + "]");
                Status.flagToday("antForestPatrol::dispatchAnimal");
            } else {
                Log.record(PREFIX + "派遣动物伙伴[" + selected.optString("creatureName", creatureCode) + "]失败");
            }
        } catch (Throwable t) {
            Log.i(TAG, "dispatchAnimal err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /** 按候选状态筛选并选择收益最高的可用动物伙伴，无候选时返回 null */
    private JSONObject selectCreature(JSONArray creatureList) {
        JSONObject best = null;
        int bestEnergy = Integer.MIN_VALUE;
        boolean allHaveInitialEnergy = true;
        for (int i = 0; i < creatureList.length(); i++) {
            JSONObject candidate = creatureList.optJSONObject(i);
            if (candidate == null || !isDispatchable(candidate)) {
                continue;
            }
            if (!candidate.has("initialRobEnergy") || candidate.isNull("initialRobEnergy")) {
                allHaveInitialEnergy = false;
            }
            int initialEnergy = candidate.optInt("initialRobEnergy", 0);
            if (best == null || initialEnergy > bestEnergy) {
                best = candidate;
                bestEnergy = initialEnergy;
            }
        }
        if (best == null) {
            return null;
        }
        if (!allHaveInitialEnergy) {
            // 候选未统一下发收益预估时退回列表中的第一个可用项
            for (int i = 0; i < creatureList.length(); i++) {
                JSONObject candidate = creatureList.optJSONObject(i);
                if (candidate != null && isDispatchable(candidate)) {
                    return candidate;
                }
            }
        }
        return best;
    }

    /** 判断单个动物候选是否可派遣 */
    private boolean isDispatchable(JSONObject candidate) {
        if (candidate.optString("creatureCode").isEmpty()) {
            return false;
        }
        if (CREATURE_STATUS_USING.equals(candidate.optString("status"))) {
            return false;
        }
        if (candidate.optLong("assignTime", 0L) > 0) {
            return false;
        }
        JSONObject energy = candidate.optJSONObject("robEnergyVO");
        if (energy != null) {
            if (energy.has("robRemainDays") && energy.optInt("robRemainDays") <= 0) {
                return false;
            }
            int maxWorkDays = energy.optInt("maxWorkDays", 0);
            if (maxWorkDays > 0 && energy.optInt("alreadyWorkDays", 0) >= maxWorkDays) {
                return false;
            }
        }
        return true;
    }

    /**
     * 自动兑换保护证书。
     * <p>
     * 从巡护地图下发链接取 projectId，先查资格，仅在「可兑换、未领取、能量充足且额度未超限」时兑换，
     * 兑换后回查证书数量确认到账。
     */
    private void exchangeCertificate() {
        if (Status.hasFlagToday("antForestPatrol::exchangeCertificate")) {
            return;
        }
        try {
            JSONObject entry = new JSONObject(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
            if (!MessageUtil.checkSuccess(TAG, entry)) {
                Log.record(PREFIX + "查询巡护地图失败，跳过证书兑换");
                return;
            }
            String redirectUrl = redirectUrlOf(entry);
            if (StringUtil.isEmpty(redirectUrl)) {
                Log.record(PREFIX + "当前地图没有证书项目");
                Status.flagToday("antForestPatrol::exchangeCertificate");
                return;
            }
            String projectId = StringUtil.getUrlQueryParam(redirectUrl, "projectId");
            if (StringUtil.isEmpty(projectId)) {
                String innerUrl = StringUtil.getUrlQueryParam(redirectUrl, "url");
                if (!StringUtil.isEmpty(innerUrl)) {
                    projectId = StringUtil.getUrlQueryParam(innerUrl, "projectId");
                }
            }
            if (StringUtil.isEmpty(projectId)) {
                Log.record(PREFIX + "当前地图证书链接缺少 projectId，跳过");
                return;
            }

            JSONObject before = new JSONObject(AntForestPatrolRpcCall.queryCertificate(projectId));
            if (!MessageUtil.checkSuccess(TAG, before)) {
                Log.record(PREFIX + "查询巡护证书资格失败，跳过");
                return;
            }
            JSONObject project = before.optJSONObject("exchangeableTree");
            if (project == null) {
                Log.record(PREFIX + "证书查询缺少 exchangeableTree，跳过");
                return;
            }
            if (!"AVAILABLE".equals(before.optString("applyAction")) || project.optInt("certCount", 0) > 0) {
                Log.record(PREFIX + "当前证书不可兑换或已领取[" + before.optString("applyAction") + "]");
                Status.flagToday("antForestPatrol::exchangeCertificate");
                return;
            }
            long cost = project.optLong("energy", -1L);
            long balance = before.optLong("currentEnergy", -1L);
            if (cost < 0 || balance < 0) {
                Log.record(PREFIX + "证书查询缺少实时成本或余额，跳过");
                return;
            }
            if (balance < cost || project.optBoolean("overLimit") || !project.optBoolean("hasBudget", true)) {
                Log.record(PREFIX + "当前证书资源或额度不足，成本" + cost + "g，余额" + balance + "g");
                Status.flagToday("antForestPatrol::exchangeCertificate");
                return;
            }

            sleepInterval();
            int certCountBefore = project.optInt("certCount", 0);
            JSONObject exchanged = new JSONObject(
                    AntForestPatrolRpcCall.exchangeCertificate(project.optLong("projectId")));
            if (!MessageUtil.checkSuccess(TAG, exchanged)) {
                Log.record(PREFIX + "兑换保护证书失败，保留待续");
                return;
            }
            Log.forest(PREFIX + "保护证书兑换请求成功[" + project.optString("projectName")
                    + "]，成本" + cost + "g");
            Status.flagToday("antForestPatrol::exchangeCertificate");

            // 回查确认到账
            sleepInterval();
            JSONObject after = new JSONObject(AntForestPatrolRpcCall.queryCertificate(projectId));
            if (!MessageUtil.checkSuccess(TAG, after)) {
                return;
            }
            JSONObject afterTree = after.optJSONObject("exchangeableTree");
            if (afterTree != null && afterTree.optInt("certCount", 0) > certCountBefore) {
                Log.forest(PREFIX + "已确认获得当前地图保护证书");
            } else {
                Log.record(PREFIX + "证书回查状态[" + after.optString("applyAction") + "]，本轮不再兑换");
            }
        } catch (Throwable t) {
            Log.i(TAG, "exchangeCertificate err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /** 从巡护地图响应中取证书兑换引导链接 */
    private String redirectUrlOf(JSONObject entry) {
        JSONObject displayInfo = entry.optJSONObject("displayInfo");
        JSONObject mapDisplay = displayInfo == null ? null : displayInfo.optJSONObject("mapDisplay");
        JSONObject guideDisplay = mapDisplay == null ? null : mapDisplay.optJSONObject("protectionGuideDisplay");
        return guideDisplay == null ? "" : guideDisplay.optString("redirectUrl");
    }

    /** 查询占用中的动物伙伴，失败返回 null */
    private JSONObject queryUsingCreature(String uid) {
        try {
            JSONObject jo = new JSONObject(AntForestPatrolRpcCall.queryUsingCreatureInfo(uid));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                Log.record(PREFIX + "查询新版动物失败，跳过");
                return null;
            }
            return jo;
        } catch (Throwable t) {
            Log.i(TAG, "queryUsingCreature err:");
            Log.printStackTrace(TAG, t);
            return null;
        }
    }
}
