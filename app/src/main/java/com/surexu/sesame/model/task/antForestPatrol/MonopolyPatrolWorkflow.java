package com.surexu.sesame.model.task.antForestPatrol;

import android.net.Uri;

import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.MessageUtil;
import com.surexu.sesame.util.TimeUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 新版大富翁巡护工作流——掷骰子、事件确认、任务领骰子、保护证书兑换。
 * <p>
 * 任务闭环采用 AntOcean 模式（query → 遍历 → complete → receive），
 * 不依赖 AG 的 TaskFlow 引擎。
 */
public class MonopolyPatrolWorkflow {

    private static final String TAG = "AntForestPatrol";

    private MonopolyPatrolWorkflow() {}

    // ---- 响应解包 ---------------------------------------------------------

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

    private static boolean checkRes(String msg, JSONObject response) {
        boolean ok = MessageUtil.checkResultCode(TAG, response);
        if (!ok) Log.record(TAG + " " + msg + response.optString("resultDesc",
                response.optString("desc", "")));
        return ok;
    }

    // ======== 新版大富翁巡护主入口 ========================================

    /**
     * @param tasksEnabled 是否自动完成/领取任务来补充骰子
     */
    public static void run(boolean tasksEnabled) {
        JSONObject state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
        if (!checkRes("查询新巡护入口失败:", state)) return;

        JSONObject region = state.optJSONObject("regionInfo");
        JSONObject map = state.optJSONObject("mapInfo");
        if (region == null || map == null || state.optJSONObject("userInfo") == null) {
            Log.record(TAG + " 新巡护入口缺少区域、地图或用户状态");
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        dateFormat.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        dateFormat.setLenient(false);

        for (JSONObject activity : new JSONObject[]{region, map}) {
            try {
                String startStr = activity.optString("startDate");
                String endStr = activity.optString("endDate");
                long now = System.currentTimeMillis();
                if (!startStr.isEmpty() && dateFormat.parse(startStr).getTime() > now) {
                    Log.record("森林巡护🦌 新巡护当前区域或地图不在开放期，停止本轮");
                    return;
                }
                if (!endStr.isEmpty() && dateFormat.parse(endStr).getTime() <= now) {
                    Log.record("森林巡护🦌 新巡护当前区域或地图已结束，停止本轮");
                    return;
                }
            } catch (Exception ignored) {}
        }

        boolean guideRoll = state.optJSONObject("userInfo") != null
                && state.optJSONObject("userInfo").optBoolean("firstEnterMonopoly", false);
        JSONObject pendingEvent = state.optJSONObject("eventInfo");
        boolean tasksMayHaveChanged = true;

        if (tasksEnabled) runTasks(region.optString("regionCode"));

        JSONObject props = parsePatrolResponse(AntForestPatrolRpcCall.triggerMonopolyHomeProps());
        if (!checkRes("新巡护首页道具处理失败:", props)) return;

        state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
        if (!checkRes("刷新新巡护状态失败:", state)) return;
        pendingEvent = pendingEvent != null ? pendingEvent : state.optJSONObject("eventInfo");

        Set<String> confirmedEvents = new HashSet<>();

        while (!Thread.currentThread().isInterrupted()) {
            JSONObject event = pendingEvent;
            if (event != null && event.optBoolean("needConfirm", false)) {
                String eventId = event.optString("eventId");
                String eventType = event.optString("eventType");
                String action;
                if ("CHARITY".equals(eventType)) {
                    action = "confirm";
                } else if ("SPECIAL".equals(eventType)) {
                    action = "skip";
                } else {
                    action = null;
                }
                JSONObject displayInfo = event.optJSONObject("displayInfo");
                String flowType = displayInfo != null ? displayInfo.optString("flowType") : "";
                if (eventId.isEmpty() || action == null
                        || !"SINGLE_ACTION_CONFIRM".equals(flowType)) {
                    Log.record(TAG + " 新巡护事件缺少可执行决策，保留当前事件");
                    return;
                }
                if (confirmedEvents.contains(eventId)) {
                    Log.record("森林巡护🦌 新巡护事件[" + eventId + "]已提交确认，等待后续调度刷新");
                    return;
                }
                JSONObject confirmation = parsePatrolResponse(
                        AntForestPatrolRpcCall.confirmMonopolyEvent(eventId, action));
                if (!checkRes("新巡护事件确认失败:", confirmation)) return;
                confirmedEvents.add(eventId);
                Log.record("森林巡护🦌 新巡护事件完成[" + eventId
                        + "] 奖励=" + confirmation.optJSONArray("eventResult"));
                state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
                if (!checkRes("刷新新巡护事件状态失败:", state)) return;
                pendingEvent = state.optJSONObject("eventInfo");
                tasksMayHaveChanged = true;
                continue;
            }

            int diceCount = state.optInt("totalDiceCount", -1);
            if (diceCount < 0) {
                Log.record(TAG + " 新巡护缺少可用骰子数量");
                return;
            }
            if (diceCount == 0) {
                if (tasksEnabled && tasksMayHaveChanged) {
                    JSONObject regionInfo = state.optJSONObject("regionInfo");
                    String regionCode = regionInfo != null ? regionInfo.optString("regionCode") : "";
                    runTasks(regionCode);
                    tasksMayHaveChanged = false;
                    state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
                    if (!checkRes("刷新新巡护任务奖励失败:", state)) return;
                    pendingEvent = state.optJSONObject("eventInfo");
                    continue;
                }
                Log.record("森林巡护🦌 新巡护当前无可用骰子，后续调度继续查询");
                return;
            }

            JSONObject userInfo = state.optJSONObject("userInfo");
            int previousSteps = userInfo != null ? userInfo.optInt("stepCount", -1) : -1;
            JSONObject roll = parsePatrolResponse(AntForestPatrolRpcCall.rollMonopolyDice(guideRoll));
            if (!checkRes("新巡护掷骰失败:", roll)) return;
            guideRoll = false;
            pendingEvent = roll.optJSONObject("eventInfo");
            int steps = roll.optJSONObject("userInfo") != null
                    ? roll.optJSONObject("userInfo").optInt("stepCount", -1) : -1;
            if (roll.optInt("totalDiceCount", -1) == diceCount
                    && steps == previousSteps && pendingEvent == null) {
                Log.record(TAG + " 新巡护掷骰后未确认状态变化，保留后续调度");
                return;
            }
            tasksMayHaveChanged = true;
            Log.record("森林巡护🦌 新巡护掷骰🎲[" + roll.optInt("diceNumber")
                    + "] 剩余" + roll.optInt("totalDiceCount") + "次");
            TimeUtil.sleep(500);
            state = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
            if (!checkRes("刷新新巡护掷骰状态失败:", state)) return;
            pendingEvent = pendingEvent != null ? pendingEvent : state.optJSONObject("eventInfo");
        }
    }

    // ---- 任务处理（AntOcean 模式，替代 AG 的 TaskFlow）-------------------

    private static void runTasks(String regionCode) {
        if (regionCode == null || regionCode.isEmpty()) return;
        String sceneCode;
        if ("hongshandongwuyuan".equals(regionCode)) {
            sceneCode = "ANTFOREST_MONOPOLY_TASK_HSDWY";
        } else {
            Log.record("森林巡护🦌 当前区域[" + regionCode + "]没有任务场景，继续地图巡护");
            return;
        }
        try {
            JSONObject response = parsePatrolResponse(
                    AntForestPatrolRpcCall.listMonopolyTasks(regionCode, sceneCode));
            if (!response.optBoolean("success")) {
                Log.record(TAG + " 巡护任务查询失败或缺少任务列表");
                return;
            }
            JSONArray tasks = response.optJSONArray("taskInfoList");
            if (tasks == null || tasks.length() == 0) {
                Log.record("森林巡护🦌 当前区域无可用巡护任务");
                return;
            }
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) continue;
                JSONObject base = task.optJSONObject("taskBaseInfo");
                if (base == null) continue;
                String taskType = base.optString("taskType");
                String taskScene = base.optString("sceneCode");
                String status = base.optString("taskStatus");
                if (taskType.isEmpty() || !sceneCode.equals(taskScene)) continue;
                JSONObject rights = task.optJSONObject("taskRights");

                if ("FINISHED".equals(status)) {
                    // 已完成待领奖
                    JSONObject receiveRes = parsePatrolResponse(
                            AntForestPatrolRpcCall.receiveMonopolyTask(taskType, taskScene));
                    if (receiveRes.optBoolean("success")) {
                        String title = "";
                        try {
                            JSONObject bizInfo = new JSONObject(
                                    base.optString("bizInfo", "{}"));
                            title = bizInfo.optString("title", taskType);
                        } catch (JSONException ignored) {}
                        Log.record("森林巡护🦌 大富翁任务领奖[" + title + "]");
                    } else {
                        Log.record(TAG + " 巡护任务领奖失败:"
                                + receiveRes.optString("resultDesc",
                                        receiveRes.optString("errorMsg")));
                    }
                } else if ("TODO".equals(status)) {
                    String taskMode = base.optString("taskMode", "");
                    String actionType = base.optString("taskProdPlayType", "");
                    if ("NORMAL".equals(taskMode) && "VISIT_FLOAT_BALL".equals(actionType)) {
                        // 浏览类任务：等时长后完成
                        try {
                            JSONObject prodPlayParam = new JSONObject(
                                    base.optString("prodPlayParam", "{}"));
                            long seconds = prodPlayParam.optLong("timeCount", 0);
                            if (seconds > 0) {
                                TimeUtil.sleep(seconds * 1000);
                            }
                        } catch (JSONException ignored) {}
                        JSONObject finishRes = parsePatrolResponse(
                                AntForestPatrolRpcCall.finishMonopolyTask(taskType, taskScene));
                        if (finishRes.optBoolean("success")) {
                            // 完成后再领奖
                            JSONObject recv = parsePatrolResponse(
                                    AntForestPatrolRpcCall.receiveMonopolyTask(taskType, taskScene));
                            Log.record("森林巡护🦌 大富翁任务完成并领奖["
                                    + taskType + "] " + (recv.optBoolean("success") ? "✓" : "✗"));
                        }
                    } else {
                        // 非自动完成类型（如步数任务），跳过
                        Log.record("森林巡护🦌 大富翁任务[" + taskType
                                + "]状态=" + status + " 类型=" + actionType + "，跳过");
                    }
                }
                // RECEIVED 状态直接跳过
                TimeUtil.sleep(300);
            }
        } catch (Exception e) {
            Log.record(TAG + " 新版巡护任务失败，保留地图机会处理: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }

    // ======== 保护证书兑换 =================================================

    /**
     * 按当前地图下发项目兑换保护证书（消耗森林能量）。
     * 独立入口，不依赖巡护掷骰流程。
     */
    public static void exchangeCertificate() {
        JSONObject entry = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
        if (!checkRes("查询巡护证书入口失败:", entry)) return;
        JSONObject mapDisplay = optJSON(entry, "displayInfo", "mapDisplay");
        String redirect = optString(optJSON(mapDisplay, "protectionGuideDisplay"), "redirectUrl");
        if (redirect.isEmpty()) {
            Log.record("森林巡护🦌 当前地图没有证书项目");
            return;
        }
        Uri uri = Uri.parse(redirect);
        String projectId = uri.getQueryParameter("projectId");
        if (projectId == null || projectId.isEmpty()) {
            String url = uri.getQueryParameter("url");
            if (url != null) {
                projectId = Uri.parse(url).getQueryParameter("projectId");
            }
        }
        if (projectId == null || projectId.isEmpty()) {
            Log.record(TAG + " 当前地图证书链接缺少 projectId");
            return;
        }

        JSONObject before = parsePatrolResponse(AntForestPatrolRpcCall.queryCertificate(projectId));
        if (!checkRes("查询巡护证书资格失败:", before)) return;
        JSONObject project = before.optJSONObject("exchangeableTree");
        if (project == null) {
            Log.record(TAG + " 证书查询缺少 exchangeableTree");
            return;
        }
        if (!"AVAILABLE".equals(before.optString("applyAction"))
                || project.optInt("certCount", 0) > 0) {
            Log.record("森林巡护🦌 当前证书不可兑换或已领取["
                    + before.optString("applyAction") + "]");
            return;
        }
        long cost = project.optLong("energy", -1L);
        long balance = before.optLong("currentEnergy", -1L);
        if (cost < 0 || balance < 0) {
            Log.record(TAG + " 证书查询缺少实时成本或余额");
            return;
        }
        if (balance < cost || project.optBoolean("overLimit")
                || !project.optBoolean("hasBudget", true)) {
            Log.record("森林巡护🦌 当前证书资源或额度不足，成本" + cost + "g，余额" + balance + "g");
            return;
        }
        try {
            JSONObject exchanged = parsePatrolResponse(
                    AntForestPatrolRpcCall.exchangeCertificate(project.optLong("projectId")));
            if (checkRes("兑换巡护证书失败:", exchanged)) {
                Log.record("森林巡护🦌 保护证书兑换请求成功["
                        + project.optString("projectName") + "]，成本" + cost + "g");
            }
        } catch (Exception e) {
            Log.record(TAG + " 巡护证书兑换结果不确定，先回查: " + e.getMessage());
        }
        JSONObject after = parsePatrolResponse(AntForestPatrolRpcCall.queryCertificate(projectId));
        if (checkRes("回查巡护证书失败:", after)) {
            int afterCert = optInt(after.optJSONObject("exchangeableTree"), "certCount", 0);
            int beforeCert = project.optInt("certCount", 0);
            if (afterCert > beforeCert) {
                Log.record("森林巡护🦌 已确认获得当前地图保护证书");
            } else {
                Log.record("森林巡护🦌 证书回查状态[" + after.optString("applyAction")
                        + "]，本轮不再兑换");
            }
        }
        JSONObject refreshed = parsePatrolResponse(AntForestPatrolRpcCall.queryMonopolyEntryInfo());
        if (checkRes("回查巡护地图失败:", refreshed)) {
            JSONObject userInfo = refreshed.optJSONObject("userInfo");
            String mapCode = userInfo != null ? userInfo.optString("currentMapCode") : "";
            Log.record("森林巡护🦌 当前巡护地图[" + mapCode + "]");
        }
    }

    // ---- JSON helpers ----------------------------------------------------

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
}