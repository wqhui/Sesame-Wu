package com.surexu.sesame.model.task.antForestPatrol;

import com.surexu.sesame.model.task.antForest.AntForestRpcCall;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.Status;
import com.surexu.sesame.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 旧版保护地巡护工作流——地图掷骰、步数兑换、动物碎片合成与派遣。
 * <p>
 * 从森林模块迁出独立管理，与新版大富翁巡护互不冲突。
 * 对齐 AG:990ea45f（fix 动物收集判断——check main 字段而非遍历 pieces）。
 */
public class LegacyPatrolWorkflow {

    private static final String TAG = "AntForestPatrol";

    /** 今日步数兑换巡护次数达到上限标记 */
    private static final String FLAG_PATROL_CHANCE_LIMIT = "Forest::patrolChanceExchangeLimit";

    private LegacyPatrolWorkflow() {}

    // ---- 辅助函数 ---------------------------------------------------------

    private static JSONObject unwrapResData(JSONObject response) {
        JSONObject data = response.optJSONObject("resData");
        return data != null ? data : response;
    }

    /**
     * 按 patrolId 查询动物与碎片（供巡护地图选择逻辑使用）。
     * AG 版本通过额外 payload 区分"地图维度查询"与"动物定向查询"，
     * 此处内联构建 RPC 调用，避免改 AntForestRpcCall 签名。
     */
    private static String queryAnimalAndPieceByPatrol(int patrolId) {
        String args = "[{\"patrolId\":" + patrolId
                + ",\"withDetail\":\"N\",\"source\":\"ant_forest\""
                + ",\"timezoneId\":\"Asia/Shanghai\"}]";
        return com.surexu.sesame.hook.ApplicationHook.requestString(
                "alipay.antforest.forest.h5.queryAnimalAndPiece", args);
    }

    // ---- 数据类 ------------------------------------------------------------

    private static class PatrolRecordInfo {
        final int patrolId;
        final long startDate;
        final String reserveName;
        final JSONObject patrolConfig;
        final JSONObject userPatrol;

        PatrolRecordInfo(int patrolId, long startDate, String reserveName,
                         JSONObject patrolConfig, JSONObject userPatrol) {
            this.patrolId = patrolId;
            this.startDate = startDate;
            this.reserveName = reserveName;
            this.patrolConfig = patrolConfig;
            this.userPatrol = userPatrol;
        }
    }

    private static class PatrolTargetRecord {
        final int patrolId;
        final String reserveName;
        final String reason;

        PatrolTargetRecord(int patrolId, String reserveName, String reason) {
            this.patrolId = patrolId;
            this.reserveName = reserveName;
            this.reason = reason;
        }
    }

    private enum PatrolPieceState { MISSING, COMPLETE, UNKNOWN }

    // ---- 巡护记录 ---------------------------------------------------------

    private static List<PatrolRecordInfo> collectPatrolRecordInfo(JSONArray records) {
        List<PatrolRecordInfo> infos = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            JSONObject patrolConfig = record.optJSONObject("patrolConfig");
            if (patrolConfig == null) continue;
            JSONObject userPatrol = record.optJSONObject("userPatrol");
            if (userPatrol == null) userPatrol = new JSONObject();
            int patrolId = patrolConfig.optInt("patrolId", userPatrol.optInt("patrolId", 0));
            if (patrolId <= 0) continue;
            infos.add(new PatrolRecordInfo(
                patrolId,
                patrolConfig.optLong("startDate", userPatrol.optLong("startDate", 0L)),
                blankTo(patrolConfig.optString("reserveName"), "保护地" + patrolId),
                patrolConfig,
                userPatrol
            ));
        }
        return infos;
    }

    private static boolean hasUnreachedPatrolNode(JSONObject userPatrol) {
        int unreachedNodeCount = userPatrol.optInt("unreachedNodeCount", -1);
        if (unreachedNodeCount >= 0) return unreachedNodeCount > 0;
        JSONArray unreachedNodes = userPatrol.optJSONArray("unreachedNodes");
        return unreachedNodes != null && unreachedNodes.length() > 0;
    }

    // ---- 图鉴/动物 --------------------------------------------------------

    private static boolean isNormalPatrolAnimal(JSONObject animal) {
        if (animal.optInt("id", -1) <= 0 || !"ONLINE".equalsIgnoreCase(animal.optString("status")))
            return false;
        if (animal.optBoolean("limited", false) || animal.optBoolean("limit", false)
                || animal.optBoolean("special", false))
            return false;
        JSONObject extInfo = animal.optJSONObject("extInfo");
        if (extInfo != null && (extInfo.optBoolean("limited", false)
                || extInfo.optBoolean("limit", false) || extInfo.optBoolean("special", false)
                || extInfo.optString("shortDesc").contains("限定")))
            return false;
        return true;
    }

    private static Set<Integer> normalPatrolAnimalIds(JSONObject patrolConfig) {
        int patrolId = patrolConfig.optInt("patrolId", 0);
        JSONArray animals = patrolConfig.optJSONArray("animals");
        if (animals == null || animals.length() == 0) {
            log("巡护地图缺少动物列表[patrolId=" + patrolId + "]，不以图鉴缺片优先");
            return Collections.emptySet();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (int i = 0; i < animals.length(); i++) {
            JSONObject animal = animals.optJSONObject(i);
            if (animal == null) continue;
            if (!isNormalPatrolAnimal(animal)) {
                String shortDesc = optString(animal.optJSONObject("extInfo"), "shortDesc");
                if (shortDesc.contains("限定")) {
                    log("巡护图鉴排除活动动物[patrolId=" + patrolId
                        + ",animalId=" + animal.optInt("id", -1) + "]");
                }
                continue;
            }
            int animalId = animal.optInt("id", -1);
            if (animalId > 0) ids.add(animalId);
        }
        if (ids.isEmpty())
            log("巡护地图无可证明的常驻在线动物[patrolId=" + patrolId + "]，不以图鉴缺片优先");
        return ids;
    }

    // ---- 图鉴碎片状态（对齐 AG:990ea45f）---------------------------------

    private static PatrolPieceState getPatrolAnimalPieceState(PatrolRecordInfo record) {
        Set<Integer> normalIds = normalPatrolAnimalIds(record.patrolConfig);
        if (normalIds.isEmpty()) return PatrolPieceState.UNKNOWN;
        try {
            String raw = queryAnimalAndPieceByPatrol(record.patrolId);
            JSONObject response = unwrapResData(new JSONObject(raw));
            if (!MessageUtil.checkResultCode(TAG, response)) {
                log("巡护图鉴检查失败[" + record.reserveName + "/" + record.patrolId + "]");
                return PatrolPieceState.UNKNOWN;
            }
            JSONArray animalProps = response.optJSONArray("animalProps");
            if (animalProps == null || animalProps.length() == 0) {
                log("巡护图鉴缺少动物碎片列表[" + record.reserveName + "/" + record.patrolId + "]");
                return PatrolPieceState.UNKNOWN;
            }
            boolean matched = false;
            for (int i = 0; i < animalProps.length(); i++) {
                JSONObject animalProp = animalProps.optJSONObject(i);
                if (animalProp == null) continue;
                JSONObject animal = animalProp.optJSONObject("animal");
                if (animal == null) continue;
                int animalId = animal.optInt("id", -1);
                if (!normalIds.contains(animalId)) continue;
                matched = true;
                // fix 990ea45f: check main field instead of iterating pieces
                if (!animalProp.has("main") || animalProp.isNull("main")) {
                    log("巡护图鉴确认可推进缺片[" + record.reserveName + "/" + record.patrolId + "/"
                        + animal.optString("name", String.valueOf(animalId)) + "]");
                    return PatrolPieceState.MISSING;
                }
            }
            if (!matched) {
                log("巡护图鉴未返回当前保护地常驻在线动物碎片[" + record.reserveName + "/"
                    + record.patrolId + "]，不以图鉴缺片优先");
                return PatrolPieceState.UNKNOWN;
            }
            log("巡护图鉴无可推进的常驻动物缺片[" + record.reserveName + "/" + record.patrolId + "]");
            return PatrolPieceState.COMPLETE;
        } catch (Throwable t) {
            logEx("getPatrolAnimalPieceState err", t);
            return PatrolPieceState.UNKNOWN;
        }
    }

    // ---- 巡护地图选择 ----------------------------------------------------

    private static PatrolTargetRecord selectPatrolTargetRecord(JSONArray records) {
        List<PatrolRecordInfo> sorted = collectPatrolRecordInfo(records);
        Collections.sort(sorted, Comparator.comparingLong((PatrolRecordInfo r) -> r.startDate)
            .thenComparingInt(r -> r.patrolId));
        if (sorted.isEmpty()) {
            log("巡护记录为空，保留当前保护地");
            return null;
        }
        boolean hasUnknown = false;
        for (PatrolRecordInfo record : sorted) {
            switch (getPatrolAnimalPieceState(record)) {
                case MISSING:
                    return new PatrolTargetRecord(record.patrolId, record.reserveName, "普通动物碎片未齐");
                case UNKNOWN:
                    hasUnknown = true;
                    break;
                case COMPLETE:
                    break;
            }
        }
        if (!hasUnknown) {
            PatrolTargetRecord inventoryTarget = selectPatrolInventoryTargetRecord(sorted);
            if (inventoryTarget != null) return inventoryTarget;
        } else {
            log("巡护图鉴状态不完整，保留原地图排序，不按背包动物切换");
        }
        for (PatrolRecordInfo record : sorted) {
            if (hasUnreachedPatrolNode(record.userPatrol))
                return new PatrolTargetRecord(record.patrolId, record.reserveName, "旧到新未走完");
        }
        PatrolRecordInfo latest = sorted.get(sorted.size() - 1);
        for (int i = sorted.size() - 1; i >= 0; i--) {
            PatrolRecordInfo r = sorted.get(i);
            if (r.startDate > latest.startDate
                || (r.startDate == latest.startDate && r.patrolId > latest.patrolId))
                latest = r;
        }
        // 使用 Collections.max:
        PatrolRecordInfo maxRecord = Collections.max(sorted,
            Comparator.comparingLong((PatrolRecordInfo r) -> r.startDate)
                .thenComparingInt(r -> r.patrolId));
        return new PatrolTargetRecord(maxRecord.patrolId, maxRecord.reserveName, "全部完成后最新循环");
    }

    private static class PatrolInventoryTarget {
        final PatrolRecordInfo record;
        final int holdsNum;
        final int estimatedEnergy;
        PatrolInventoryTarget(PatrolRecordInfo r, int h, int e) {
            record = r; holdsNum = h; estimatedEnergy = e;
        }
    }

    private static PatrolTargetRecord selectPatrolInventoryTargetRecord(List<PatrolRecordInfo> records) {
        JSONObject response;
        try {
            response = new JSONObject(AntForestRpcCall.queryAnimalPropList());
        } catch (Throwable t) {
            logEx("queryAnimalPropList for patrol target err", t);
            return null;
        }
        if (!MessageUtil.checkResultCode(TAG, response)) return null;
        JSONArray animalProps = response.optJSONArray("animalProps");
        if (animalProps == null || animalProps.length() == 0) {
            log("巡护背包动物为空，保留原地图排序");
            return null;
        }
        PatrolInventoryTarget bestTarget = null;
        for (PatrolRecordInfo record : records) {
            Set<Integer> normalIds = normalPatrolAnimalIds(record.patrolConfig);
            if (normalIds.isEmpty()) continue;
            for (int index = 0; index < animalProps.length(); index++) {
                JSONObject animalProp = animalProps.optJSONObject(index);
                if (animalProp == null) continue;
                int animalId = getPatrolAnimalId(animalProp);
                if (!normalIds.contains(animalId)) continue;
                JSONObject main = animalProp.optJSONObject("main");
                if (main == null || !main.has("holdsNum") || main.isNull("holdsNum")) {
                    log("巡护背包动物缺少holdsNum字段[animalId=" + animalId + "]，保留原地图排序");
                    return null;
                }
                if (!hasAnimalPropRobEnergy(animalProp)) {
                    log("巡护背包动物缺少robEnergy字段[animalId=" + animalId + "]，保留原地图排序");
                    return null;
                }
                int holdsNum = main.optInt("holdsNum", 0);
                if (holdsNum <= 0) continue;
                int estimatedEnergy = estimateAnimalPropRobEnergy(animalProp);
                if (bestTarget == null || holdsNum < bestTarget.holdsNum
                    || (holdsNum == bestTarget.holdsNum && estimatedEnergy > bestTarget.estimatedEnergy)) {
                    bestTarget = new PatrolInventoryTarget(record, holdsNum, estimatedEnergy);
                }
            }
        }
        if (bestTarget == null) {
            log("巡护背包动物未匹配普通地图动物，保留原地图排序");
            return null;
        }
        return new PatrolTargetRecord(bestTarget.record.patrolId, bestTarget.record.reserveName,
            "普通动物已合成，背包数量最少(" + bestTarget.holdsNum + ")且能量最高("
                + bestTarget.estimatedEnergy + "g)");
    }

    private static int getPatrolAnimalId(JSONObject animalProp) {
        int animalId = optInt(animalProp.optJSONObject("animal"), "id", 0);
        if (animalId > 0) return animalId;
        JSONObject partner = animalProp.optJSONObject("partner");
        if (partner != null) {
            String idStr = partner.optString("animalId");
            try { return Integer.parseInt(idStr); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    // ---- 切换地图 --------------------------------------------------------

    private static boolean switchUserPatrolIfNeeded(int currentPatrolId, JSONObject recordPayload) {
        if (!recordPayload.optBoolean("canSwitch", false)) return false;
        JSONArray records = recordPayload.optJSONArray("records");
        if (records == null || records.length() == 0) {
            log("巡护记录缺少records，保留当前保护地");
            return false;
        }
        PatrolTargetRecord target = selectPatrolTargetRecord(records);
        if (target == null) return false;
        if (target.patrolId <= 0 || target.patrolId == currentPatrolId) {
            log("巡护⚖️-当前地图保持[" + target.reserveName + "/" + target.patrolId
                + "](" + target.reason + ")");
            return false;
        }
        try {
            JSONObject switchRes = unwrapResData(
                new JSONObject(AntForestRpcCall.switchUserPatrol(String.valueOf(target.patrolId))));
            if (MessageUtil.checkResultCode(TAG, switchRes)) {
                log("巡护⚖️-切换地图至[" + target.reserveName + "/" + target.patrolId
                    + "](" + target.reason + ")");
                return true;
            }
            log("巡护地图切换失败[" + target.reserveName + "/" + target.patrolId + "]");
        } catch (Exception e) {
            logEx("switchUserPatrolIfNeeded err", e);
        }
        return false;
    }

    // ======== 公开入口 =====================================================

    /** 查询并推进旧版巡护（掷骰子、步数兑换、动物碎片优先选图） */
    public static void queryUserPatrol() {
        boolean patrolChanceReplenishTried = false;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject jo = unwrapResData(new JSONObject(AntForestRpcCall.queryUserPatrol()));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    int currentPatrolId = optInt(jo.optJSONObject("userPatrol"), "patrolId", 0);
                    JSONObject recordPayload = unwrapResData(
                        new JSONObject(AntForestRpcCall.queryMyPatrolRecord()));
                    if (MessageUtil.checkResultCode(TAG, recordPayload)
                            && switchUserPatrolIfNeeded(currentPatrolId, recordPayload)) {
                        jo = unwrapResData(new JSONObject(AntForestRpcCall.queryUserPatrol()));
                        if (!MessageUtil.checkResultCode(TAG, jo)) {
                            log(jo.optString("resultDesc", "查询巡护任务失败"));
                            break;
                        }
                    }
                    JSONObject userPatrol = jo.optJSONObject("userPatrol");
                    if (userPatrol == null) {
                        log("巡护任务缺少userPatrol字段，跳过本轮");
                        break;
                    }
                    int currentNode = userPatrol.getInt("currentNode");
                    String currentStatus = userPatrol.getString("currentStatus");
                    int patrolId = userPatrol.getInt("patrolId");
                    JSONObject chance = userPatrol.getJSONObject("chance");
                    int leftChance = chance.getInt("leftChance");
                    int leftStep = chance.getInt("leftStep");
                    int usedStep = chance.getInt("usedStep");
                    int chanceFromStepUpperLimit = jo.optInt("chanceFromStepUpperLimit", 5);
                    int chanceStepUnit = jo.optInt("chanceStepUnit", 2000);
                    int maxExchangeStep = (chanceFromStepUpperLimit > 0 && chanceStepUnit > 0)
                        ? chanceFromStepUpperLimit * chanceStepUnit : 10000;
                    if (usedStep >= maxExchangeStep && !Status.hasFlagToday(FLAG_PATROL_CHANCE_LIMIT)) {
                        Status.flagToday(FLAG_PATROL_CHANCE_LIMIT);
                        log("今日保护地巡护兑换次数已达上限(" + chanceFromStepUpperLimit + "次)，后续不再重复尝试");
                    }
                    if ("STANDING".equals(currentStatus)) {
                        if (leftChance > 0) {
                            jo = unwrapResData(
                                new JSONObject(AntForestRpcCall.patrolGo(currentNode, patrolId)));
                            patrolKeepGoing(jo, patrolId);
                            continue;
                        } else if (!Status.hasFlagToday(FLAG_PATROL_CHANCE_LIMIT)
                                && leftStep >= chanceStepUnit && usedStep < maxExchangeStep) {
                            jo = new JSONObject(AntForestRpcCall.exchangePatrolChance(leftStep));
                            if (MessageUtil.checkResultCode(TAG, jo)) {
                                int addedChance = jo.optInt("addedChance", 0);
                                log("步数兑换⚖️[巡护次数*" + addedChance + "]");
                                int consumedStep = addedChance > 0 ? addedChance * chanceStepUnit
                                    : chanceStepUnit;
                                if (usedStep + consumedStep >= maxExchangeStep) {
                                    Status.flagToday(FLAG_PATROL_CHANCE_LIMIT);
                                    log("今日保护地巡护兑换次数已达上限(" + chanceFromStepUpperLimit
                                        + "次)，后续不再重复尝试");
                                }
                                continue;
                            } else {
                                String resultDesc = jo.optString("resultDesc");
                                if (resultDesc.contains("上限") || resultDesc.contains("已达")
                                        || resultDesc.contains("最多")) {
                                    Status.flagToday(FLAG_PATROL_CHANCE_LIMIT);
                                    log("今日保护地巡护兑换次数已达上限(" + chanceFromStepUpperLimit
                                        + "次)，后续不再重复尝试");
                                } else {
                                    log(resultDesc);
                                }
                            }
                        }
                    } else if ("GOING".equals(currentStatus)) {
                        patrolKeepGoing(jo, patrolId);
                    }
                    // Exchange replenisher not ported — skip patrol-chance replenish
                    if ("STANDING".equals(currentStatus) && leftChance <= 0
                            && !patrolChanceReplenishTried) {
                        patrolChanceReplenishTried = true;
                        // Replenish not available without Exchange framework, just skip
                    }
                } else {
                    log(jo.optString("resultDesc", jo.optString("desc", "查询巡护任务失败")));
                }
                break;
            }
        } catch (Throwable t) {
            logEx("queryUserPatrol err", t);
        }
    }

    // ---- 巡护续跑 --------------------------------------------------------

    private static void patrolKeepGoing(JSONObject response, int patrolId) {
        JSONObject current = response;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject jo = current;
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    log(jo.optString("resultDesc", jo.optString("desc", "巡护失败")));
                    return;
                }
                logPatrolRewardPiece(jo.optJSONArray("events") != null
                    ? jo.optJSONArray("events").optJSONObject(0) : null);
                current = buildNextPatrolKeepGoingResponse(jo, patrolId);
                if (current == null) return;
            }
        } catch (Throwable t) {
            logEx("patrolKeepGoing err", t);
        }
    }

    private static void logPatrolRewardPiece(JSONObject event) {
        if (event == null) return;
        String animalName = optString(
            optJSON(event, "rewardInfo", "animalProp", "animal"), "name");
        if (!animalName.isEmpty()) {
            log("巡护森林🏇🏻[" + animalName + "碎片]");
        }
    }

    private static JSONObject buildNextPatrolKeepGoingResponse(JSONObject response, int patrolId) {
        String currentStatus = response.optString("currentStatus");
        if (!"GOING".equals(currentStatus)) return null;
        JSONArray events = response.optJSONArray("events");
        if (events == null || events.length() == 0) {
            logPatrolKeepGoingStop(currentStatus, "缺少事件载荷");
            return null;
        }
        JSONObject event = events.optJSONObject(0);
        if (event == null) {
            logPatrolKeepGoingStop(currentStatus, "事件数据为空");
            return null;
        }
        JSONObject userPatrol = response.optJSONObject("userPatrol");
        if (userPatrol == null) {
            logPatrolKeepGoingStop(currentStatus, "缺少userPatrol");
            return null;
        }
        int currentNode = userPatrol.optInt("currentNode", -1);
        if (currentNode < 0) {
            logPatrolKeepGoingStop(currentStatus, "缺少当前节点");
            return null;
        }
        String materialType = optString(
            optJSON(event, "materialInfo"), "materialType");
        if (materialType.isEmpty()) {
            logPatrolKeepGoingStop(currentStatus, "缺少事件类型");
            return null;
        }
        try {
            return unwrapResData(
                new JSONObject(AntForestRpcCall.patrolKeepGoing(currentNode, patrolId, materialType)));
        } catch (Exception e) {
            logEx("buildNextPatrolKeepGoingResponse err", e);
            return null;
        }
    }

    private static void logPatrolKeepGoingStop(String currentStatus, String reason) {
        if ("GOING".equals(currentStatus)) {
            log("巡护进行中但" + reason + "，停止本轮巡护续跑");
        }
    }

    // ---- 动物派遣选择 ----------------------------------------------------

    public static class AnimalPropSelection {
        public final JSONObject animalProp;
        public final int holdsNum;
        public final int estimatedEnergy;
        AnimalPropSelection(JSONObject p, int h, int e) {
            animalProp = p; holdsNum = h; estimatedEnergy = e;
        }
    }

    public static AnimalPropSelection selectBestAnimalProp(JSONArray animalProps) {
        AnimalPropSelection best = null;
        for (int i = 0; i < animalProps.length(); i++) {
            JSONObject animalProp = animalProps.optJSONObject(i);
            if (animalProp == null) continue;
            int holdsNum = getAnimalPropHoldsNum(animalProp);
            if (holdsNum <= 0) continue;
            int estimatedEnergy = estimateAnimalPropRobEnergy(animalProp);
            if (best == null || holdsNum > best.holdsNum
                || (holdsNum == best.holdsNum && estimatedEnergy > best.estimatedEnergy)) {
                best = new AnimalPropSelection(animalProp, holdsNum, estimatedEnergy);
            }
        }
        return best;
    }

    private static int getAnimalPropHoldsNum(JSONObject animalProp) {
        return optInt(animalProp.optJSONObject("main"), "holdsNum", 0);
    }

    private static int estimateAnimalPropRobEnergy(JSONObject animalProp) {
        JSONObject partner = animalProp.optJSONObject("partner");
        JSONObject main = animalProp.optJSONObject("main");
        return maxOf(extractAnimalRobAbilityEnergy(partner),
            extractAnimalRobAbilityEnergy(main),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(partner)),
            extractAnimalRobAbilityEnergy(parseAnimalPropExtInfo(main)));
    }

    private static boolean hasAnimalPropRobEnergy(JSONObject animalProp) {
        JSONObject partner = animalProp.optJSONObject("partner");
        JSONObject main = animalProp.optJSONObject("main");
        for (JSONObject container : new JSONObject[]{
                partner, main,
                parseAnimalPropExtInfo(partner), parseAnimalPropExtInfo(main)}) {
            JSONObject robAbility = optJSON(container, "robAbility");
            if (robAbility == null)
                robAbility = optJSON(optJSON(container, "animal"), "robAbility");
            if (robAbility != null
                && (robAbility.has("robEnergyInDaily") || robAbility.has("robEnergyInRound")))
                return true;
        }
        return false;
    }

    private static int extractAnimalRobAbilityEnergy(JSONObject container) {
        if (container == null) return 0;
        JSONObject robAbility = optJSON(container, "robAbility");
        if (robAbility == null)
            robAbility = optJSON(optJSON(container, "animal"), "robAbility");
        if (robAbility == null) return 0;
        return Math.max(robAbility.optInt("robEnergyInDaily", 0),
            robAbility.optInt("robEnergyInRound", 0));
    }

    private static JSONObject parseAnimalPropExtInfo(JSONObject container) {
        if (container == null || !container.has("extInfo")) return null;
        Object extInfo = container.opt("extInfo");
        if (extInfo instanceof JSONObject) return (JSONObject) extInfo;
        if (extInfo instanceof String) {
            try {
                if (((String) extInfo).trim().startsWith("{"))
                    return new JSONObject((String) extInfo);
            } catch (JSONException ignored) {}
        }
        return null;
    }

    // ---- 派遣伙伴 --------------------------------------------------------

    public static void consumeAnimalProp(AnimalPropSelection selection) {
        if (selection == null) return;
        try {
            JSONObject animalProp = selection.animalProp;
            String propGroup = animalProp.getJSONObject("main").getString("propGroup");
            String propType = animalProp.getJSONObject("main").getString("propType");
            String name = animalProp.getJSONObject("partner").getString("name");
            JSONObject jo = new JSONObject(
                AntForestRpcCall.consumeProp(propGroup, "", propType, false));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                log("巡护派遣🐆[" + name + "]#持有" + selection.holdsNum
                    + "个，预计能量" + selection.estimatedEnergy + "g。");
            } else {
                log(jo.optString("resultDesc"));
            }
        } catch (Throwable t) {
            logEx("consumeAnimalProp err", t);
        }
    }

    // ---- 动物碎片合成 ----------------------------------------------------

    public static void queryAnimalAndPiece() {
        try {
            JSONObject response = unwrapResData(
                new JSONObject(AntForestRpcCall.queryAnimalAndPiece(0)));
            String resultCode = response.optString("resultCode");
            if (!"SUCCESS".equals(resultCode)) {
                log("查询失败: " + response.optString("resultDesc"));
                return;
            }
            JSONArray animalProps = response.optJSONArray("animalProps");
            if (animalProps == null || animalProps.length() == 0) {
                log("动物属性列表为空");
                return;
            }
            for (int animalId : collectCombinableAnimalIds(animalProps)) {
                combineAnimalPiece(animalId);
            }
        } catch (Exception e) {
            logEx("queryAnimalAndPiece err", e);
        }
    }

    private static List<Integer> collectCombinableAnimalIds(JSONArray animalProps) {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < animalProps.length(); i++) {
            JSONObject animalObject = animalProps.optJSONObject(i);
            if (animalObject == null) continue;
            JSONArray pieces = animalObject.optJSONArray("pieces");
            if (pieces == null) continue;
            int animalId = optInt(animalObject.optJSONObject("animal"), "id", -1);
            if (animalId > 0 && canCombinePieces(pieces)) ids.add(animalId);
        }
        return ids;
    }

    private static boolean canCombinePieces(JSONArray pieces) {
        for (int j = 0; j < pieces.length(); j++) {
            JSONObject piece = pieces.optJSONObject(j);
            if (piece == null || piece.optInt("holdsNum", 0) <= 0) return false;
        }
        return true;
    }

    private static void combineAnimalPiece(int animalId) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JSONObject response = unwrapResData(
                    new JSONObject(AntForestRpcCall.queryAnimalAndPiece(animalId)));
                String resultCode = response.optString("resultCode");
                if (!"SUCCESS".equals(resultCode)) {
                    log("动物碎片合成查询失败[#" + animalId + "]: "
                        + response.optString("resultDesc", ""));
                    break;
                }
                JSONArray animalProps = response.optJSONArray("animalProps");
                if (animalProps == null || animalProps.length() == 0) {
                    log("动物碎片合成查询返回空动物数据[#" + animalId + "]");
                    break;
                }
                JSONObject animalProp = animalProps.getJSONObject(0);
                JSONObject animal = animalProp.optJSONObject("animal");
                if (animal == null) {
                    log("动物碎片合成缺少animal字段[#" + animalId + "]");
                    break;
                }
                String name = animal.optString("name", "未知动物");
                JSONArray pieces = animalProp.optJSONArray("pieces");
                if (pieces == null || pieces.length() == 0) {
                    log("动物碎片合成查询缺少碎片数据[" + name + "]");
                    break;
                }
                boolean canCombine = true;
                JSONArray piecePropIds = new JSONArray();
                for (int j = 0; j < pieces.length(); j++) {
                    JSONObject piece = pieces.optJSONObject(j);
                    if (piece == null || piece.optInt("holdsNum", 0) <= 0) {
                        canCombine = false;
                        log("动物碎片不足[" + name + "]：无法继续自动合成");
                        break;
                    }
                    JSONArray propIdList = piece.optJSONArray("propIdList");
                    String propId = propIdList != null ? propIdList.optString(0) : null;
                    if (propId == null || propId.isEmpty()) {
                        canCombine = false;
                        log("动物碎片合成暂停[" + name
                            + "]：碎片未返回稳定propIdList，跳过本轮合成");
                        break;
                    }
                    piecePropIds.put(propId);
                }
                if (canCombine) {
                    JSONObject combineRes = unwrapResData(new JSONObject(
                        AntForestRpcCall.combineAnimalPiece(animalId, piecePropIds.toString())));
                    if ("SUCCESS".equals(combineRes.optString("resultCode"))) {
                        log("成功合成动物💡[" + name + "]");
                        TimeUtil.sleep(100);
                        continue;
                    } else {
                        log("动物碎片合成失败[" + name + "]: "
                            + combineRes.optString("resultDesc", ""));
                    }
                }
                break;
            }
        } catch (Exception e) {
            logEx("combineAnimalPiece err", e);
        }
    }

    // ---- Log helpers -----------------------------------------------------

    private static void log(String msg) {
        Log.record("森林巡护🦌 " + msg);
    }

    private static void logEx(String msg, Throwable t) {
        Log.record("森林巡护🦌 " + msg + ": " + t.getMessage());
        Log.printStackTrace(t);
    }

    // ---- JSON helpers ----------------------------------------------------

    private static String blankTo(String s, String fallback) {
        return s == null || s.trim().isEmpty() ? fallback : s;
    }

    private static String optString(JSONObject jo, String key) {
        return jo != null ? jo.optString(key, "") : "";
    }

    private static int optInt(JSONObject jo, String key, int def) {
        return jo != null ? jo.optInt(key, def) : def;
    }

    private static JSONObject optJSON(JSONObject container, String... path) {
        JSONObject cur = container;
        for (String key : path) {
            if (cur == null) return null;
            cur = cur.optJSONObject(key);
        }
        return cur;
    }

    private static int maxOf(int... values) {
        int m = Integer.MIN_VALUE;
        for (int v : values) if (v > m) m = v;
        return m;
    }
}