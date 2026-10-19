package com.sesame.wu.model.task.goldenbeans;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sesame.wu.data.ConfigV2;
import com.sesame.wu.data.ModelFields;
import com.sesame.wu.data.modelFieldExt.SelectModelField;
import com.sesame.wu.data.task.TaskAttemptPolicy;
import com.sesame.wu.data.task.TaskAward;
import com.sesame.wu.data.task.TaskAttemptPolicy.Outcome;
import com.sesame.wu.model.base.TaskAlternative;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.MessageUtil;
import com.sesame.wu.util.RandomUtil;
import com.sesame.wu.util.Status;
import com.sesame.wu.util.TaskBlacklist;
import com.sesame.wu.util.TimeUtil;
import com.sesame.wu.util.idMap.GoldenBeansTaskListMap;
import com.sesame.wu.util.idMap.UserIdMap;

/**
 * 金豆夺宝的入口日常与任务列表处理。
 * <p>
 * 每个入口独立执行：主页查询 → 每日签到 → 营销弹窗 → 任务列表。
 * 任务列表只处理 sceneCode 与本入口一致的任务；需真实付款、换豆承接类任务直接跳过；
 * 其余 TODO 任务走服务端完成契约，失败且命中不可重试错误时自动加入黑名单。
 */
public final class GoldenBeansTasks {

    /** 任务状态：已完成待领取 */
    private static final String STATUS_FINISHED = "FINISHED";
    /** 任务状态：已完成待领取（服务端另一套命名） */
    private static final String STATUS_TO_RECEIVE = "TO_RECEIVE";
    /** 任务状态：已领取 */
    private static final String STATUS_RECEIVED = "RECEIVED";
    /** 任务状态：已结束 */
    private static final String STATUS_DONE = "DONE";
    /** 任务状态：待完成 */
    private static final String STATUS_TODO = "TODO";

    /** 金豆求签当日完成标记：求签只在农场（MASTER）入口提供，全模块共用一个标记 */
    private static final String FLAG_FORTUNE_DRAWN = "goldenBeans::fortune::drawn";

    private final SelectModelField blacklist;
    private final boolean autoBlacklist;

    /** 备用接口同轮待核对（见 TaskAlternative.verify） */
    private final Map<String, String> pendingVerifyTasks = new LinkedHashMap<>();

    /** 同轮核对配置（见 TaskAlternative.verify） */
    private static final TaskAlternative.VerifyConfig VERIFY_CFG = new TaskAlternative.VerifyConfig(
            "goldenbeans", "GoldenBeansTaskList", "金豆夺宝任务", "金豆", "🧾完成", false,
            msg -> Log.goldenBeans(msg));

    // 固定延迟基础上叠加 0~60ms 随机抖动，避免触发时间过于规整
    private static void sleepWithJitter(long baseMillis) {
        TimeUtil.sleep(Math.max(baseMillis, 200) + RandomUtil.nextInt(0, 61));
    }

    /**
     * @param blacklist     黑名单配置字段，可为 null
     * @param autoBlacklist 是否自动维护默认黑白名单
     */
    public GoldenBeansTasks(SelectModelField blacklist, boolean autoBlacklist) {
        this.blacklist = blacklist;
        this.autoBlacklist = autoBlacklist;
    }

    /**
     * 处理单个入口。
     *
     * @return 该入口的任务列表是否已无待推进项
     */
    public boolean processEntry(GoldenBeansEntry entry, int interval,
                                boolean signEnabled, boolean fortuneEnabled,
                                boolean popupEnabled, boolean taskEnabled) {
        try {
            JSONObject indexJo = GoldenBeansSupport.parse(
                    goldenbeansRpcCall.homeOf(entry.bizType, entry.source));
            if (!GoldenBeansSupport.ok(indexJo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]主页⚠️查询失败["
                        + GoldenBeansSupport.describe(indexJo) + "]");
                return false;
            }

            // 求签只在农场（MASTER）入口提供，且必须排在签到前：
            // 签到后的同步响应里没有 fortuneDrawInfo，只能以主页响应为准
            if (fortuneEnabled && entry == GoldenBeansEntry.FARM) {
                JSONObject fortuneSync = doFortuneDraw(indexJo, entry, interval);
                if (fortuneSync != null) {
                    indexJo = fortuneSync;
                }
            }

            if (signEnabled) {
                JSONObject signedSync = doSign(indexJo, entry, interval);
                if (signedSync != null) {
                    indexJo = signedSync;
                }
            }

            if (popupEnabled) {
                clickPopup(indexJo, entry, interval);
            }

            if (taskEnabled) {
                return runTaskList(entry, interval);
            }
            return true;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "processEntry err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /**
     * 每日签到：从主页 signInfo 中找出今日未签到的 signKey 并提交。
     *
     * @return 签到后的同步响应，供后续弹窗与任务使用
     */
    /** 金豆签到当日完成标记：按入口区分（农场 / 炼金各自的签到列表互不相通） */
    private static String signFlag(GoldenBeansEntry entry) {
        return "goldenBeans::sign::" + entry.bizType;
    }

    private JSONObject doSign(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        if (Status.hasFlagToday(signFlag(entry))) {
            return null;
        }
        try {
            JSONObject signInfo = indexJo.optJSONObject("signInfo");
            if (signInfo == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️未返回签到信息");
                return null;
            }
            JSONArray signList = signInfo.optJSONArray("signList");
            if (signList == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️签到列表为空");
                return null;
            }
            for (int i = 0; i < signList.length(); i++) {
                JSONObject sign = signList.optJSONObject(i);
                if (sign != null && sign.optBoolean("today", false) && sign.optBoolean("signed", false)) {
                    // 本轮主页 signInfo 里今日项已 signed=true：服务端回读确认，落当日标记
                    Status.flagToday(signFlag(entry));
                    break;
                }
                if (sign == null || !sign.optBoolean("today", false)) {
                    continue;
                }
                String signKey = sign.optString("signKey", "").trim();
                if (signKey.isEmpty()) {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️缺少服务端signKey");
                    return null;
                }
                JSONObject signResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.checkInOf(entry.bizType, entry.source, signKey));
                if (!GoldenBeansSupport.ok(signResponse)) {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️失败["
                            + GoldenBeansSupport.describe(signResponse) + "]");
                    return null;
                }
                sleepWithJitter(interval);
                JSONObject syncResponse = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.pullOf(entry.bizType, entry.source,
                                "JAR_INFO", "SIGN", "MARKETING_POPUP", "TASK_LIST"));
                if (GoldenBeansSupport.todaySigned(syncResponse)) {
                    // 服务端回读已确认今日签到：落当日标记，后续运行不再查询/提交该入口签到
                    Status.flagToday(signFlag(entry));
                    int awardCount = GoldenBeansSupport.awardCount(signResponse);
                    if (awardCount <= 0) {
                        awardCount = sign.optInt("awardCount", 0);
                    }
                    int continuousDays = sign.optInt("currentContinuousCount",
                            sign.optInt("continuousCount", 0));
                    String dayInfo = continuousDays > 0 ? "[第" + continuousDays + "天]" : "";
                    String awardText = awardCount > 0 ? "#获得[" + awardCount + "豆]" : "";
                    Log.goldenBeans("金豆[" + entry.alias + "]签到📅" + dayInfo + awardText);
                } else {
                    Log.goldenBeans("金豆[" + entry.alias + "]签到⚠️未通过服务端状态确认");
                }
                return syncResponse;
            }
            Log.goldenBeans("金豆[" + entry.alias + "]签到📅今日已签到");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "doSign err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return null;
    }

    /**
     * 每日求签：主页 {@code fortuneDrawInfo.todayDrawn} 为 false 时提交一次。
     * <p>
     * 提交响应必须同时带上 {@code beanDelta} 与 {@code fortuneType} 才算服务端真的签出结果；
     * 缺任一项时重拉主页，以 {@code fortuneDrawInfo.todayDrawn} 作为最终判据
     * （与签到一样，只信服务端回读）。确认成功后落当日标记，后续运行不再查询与提交。
     *
     * @return 重拉后的主页响应，供后续签到、弹窗与任务使用；无需重拉或重拉失败时返回 null
     */
    private JSONObject doFortuneDraw(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        if (Status.hasFlagToday(FLAG_FORTUNE_DRAWN)) {
            return null;
        }
        try {
            JSONObject fortuneInfo = indexJo.optJSONObject("fortuneDrawInfo");
            if (fortuneInfo == null) {
                return null;
            }
            if (fortuneInfo.optBoolean("todayDrawn", false)) {
                // 主页已标记今日求签：落当日标记，后续运行不再查询/提交
                Status.flagToday(FLAG_FORTUNE_DRAWN);
                Log.goldenBeans("金豆[" + entry.alias + "]求签🔮今日已求签");
                return null;
            }

            JSONObject fortuneResponse = GoldenBeansSupport.parse(
                    goldenbeansRpcCall.fortuneDrawOf(entry.bizType, entry.source));
            boolean succeeded = GoldenBeansSupport.ok(fortuneResponse);
            Integer beanDelta = null;
            JSONObject fortuneType = null;
            if (fortuneResponse != null) {
                if (fortuneResponse.has("beanDelta")) {
                    beanDelta = fortuneResponse.optInt("beanDelta", 0);
                }
                fortuneType = fortuneResponse.optJSONObject("fortuneType");
            }
            boolean confirmed = succeeded && beanDelta != null && fortuneType != null;

            JSONObject refreshedIndex = null;
            if (!confirmed) {
                // 响应不可信：重拉主页，以服务端的 todayDrawn 再核一次
                sleepWithJitter(interval);
                refreshedIndex = GoldenBeansSupport.parse(
                        goldenbeansRpcCall.homeOf(entry.bizType, entry.source));
                JSONObject refreshedInfo = refreshedIndex == null
                        ? null : refreshedIndex.optJSONObject("fortuneDrawInfo");
                confirmed = GoldenBeansSupport.ok(refreshedIndex)
                        && refreshedInfo != null
                        && refreshedInfo.optBoolean("todayDrawn", false);
            }
            if (!confirmed) {
                Log.goldenBeans("金豆[" + entry.alias + "]求签⚠️未确认完成["
                        + GoldenBeansSupport.describe(fortuneResponse) + "]");
                return refreshedIndex;
            }

            Status.flagToday(FLAG_FORTUNE_DRAWN);
            if (beanDelta != null && fortuneType != null) {
                Log.goldenBeans("金豆[" + entry.alias + "]求签🔮["
                        + fortuneType.optString("typeName", "") + "]#获得[" + beanDelta + "豆]");
            } else {
                Log.goldenBeans("金豆[" + entry.alias + "]求签🔮服务端已确认今日求签完成");
            }
            return refreshedIndex;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "doFortuneDraw err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return null;
    }

    /** 营销弹窗触发（浏览类动作） */
    private void clickPopup(JSONObject indexJo, GoldenBeansEntry entry, int interval) {
        try {
            JSONObject marketingTask = indexJo.optJSONObject("marketingPopupTask");
            if (marketingTask == null) {
                return;
            }
            String taskId = marketingTask.optString("taskId", "").trim();
            String triggerType = marketingTask.optString("triggerType", "").trim();
            if (triggerType.isEmpty()) {
                triggerType = goldenbeansRpcCall.TRIGGER_MARKETING_POPUP;
            }
            if (taskId.isEmpty()) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️缺少服务端taskId");
                return;
            }
            JSONObject triggerResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.fireOf(
                    entry.bizType, entry.source, taskId, triggerType));
            if (!GoldenBeansSupport.ok(triggerResponse)) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️触发失败[" + taskId + "]"
                        + GoldenBeansSupport.describe(triggerResponse));
                return;
            }
            sleepWithJitter(interval);
            JSONObject syncResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "MARKETING_POPUP"));
            if (GoldenBeansSupport.ok(syncResponse)) {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗🖱️点击[" + taskId + "]");
            } else {
                Log.goldenBeans("金豆[" + entry.alias + "]弹窗⚠️回查失败[" + taskId + "]");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "clickPopup err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }

    /**
     * 任务列表处理：领取已完成任务奖励、完成服务端允许直接完成的任务。
     *
     * @return 该入口是否已无待推进任务
     */
    private boolean runTaskList(GoldenBeansEntry entry, int interval) {
        boolean unresolved = false;
        try {
            JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
            if (!GoldenBeansSupport.ok(syncJo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️列表查询失败["
                        + GoldenBeansSupport.describe(syncJo) + "]");
                return false;
            }
            JSONArray taskList = syncJo.optJSONArray("taskList");
            if (taskList == null) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️未返回任务列表");
                return true;
            }

            int total = 0;
            int handled = 0;
            boolean changed = false;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                // 两个入口的任务场景不同，只处理属于当前入口的任务
                if (!entry.taskSceneCode.equals(task.optString("sceneCode", "").trim())) {
                    continue;
                }
                total++;
                String taskId = task.optString("taskId", "").trim();
                String taskStatus = task.optString("taskStatus", "").trim().toUpperCase();
                String actionType = task.optString("actionType", "").trim();
                String blacklistKey = blacklistKey(task);
                String taskName = displayName(task);

                // 黑名单任务跳过；已完成待领取的仍执行领奖，兼容以标题为键的旧黑名单项
                if (isBlacklisted(blacklistKey, taskName)) {
                    if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                        sleepWithJitter(interval);
                        // 领奖失败要计入未完成，下轮还会重试（与下面非黑名单分支保持一致）
                        if (claimAward(entry, taskId, taskName)) {
                            changed = true;
                        } else {
                            unresolved = true;
                        }
                    } else {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]黑名单跳过");
                    }
                    continue;
                }

                if (STATUS_RECEIVED.equals(taskStatus) || STATUS_DONE.equals(taskStatus)) {
                    handled++;
                    continue;
                }

                if (STATUS_FINISHED.equals(taskStatus) || STATUS_TO_RECEIVE.equals(taskStatus)) {
                    sleepWithJitter(interval);
                    if (claimAward(entry, taskId, taskName)) {
                        handled++;
                        changed = true;
                    } else {
                        unresolved = true;
                    }
                    continue;
                }

                if (STATUS_TODO.equals(taskStatus)) {
                    if (isPayTask(taskId)) {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]需真实付款#跳过");
                        continue;
                    }
                    if (goldenbeansRpcCall.TASK_TYPE_EXCHANGE.equals(taskId)) {
                        Log.record("金豆[" + entry.alias + "]任务⏭️[" + taskName + "]"
                                + (entry == GoldenBeansEntry.ALCHEMY ? "芝麻粒换豆处理" : "肥料换豆处理"));
                        continue;
                    }
                    sleepWithJitter(interval);
                    // 多次任务（rightsTimesLimit>1）一次补齐：逐档取不同的策略键，否则第一档落下
                    // 当日标记后后续档位全被挡下、任务停在第一档
                    int rightsTimes = task.optInt("rightsTimes", 0);
                    int rightsTimesLimit = task.optInt("rightsTimesLimit", 0);
                    int remain = rightsTimesLimit > rightsTimes ? rightsTimesLimit - rightsTimes : 1;
                    boolean anyDone = false;
                    for (int cnt = 0; cnt < remain; cnt++) {
                        // 最后一档才走列表复核：中间档是"里程碑"语义（列表要打满才变完成），
                        // 按响应推进即可，否则中间档会被判"未完成"、break 掉后续档位
                        if (!finishTask(entry, taskId, taskName, rightsTimes + cnt, cnt == remain - 1)) {
                            break; // 做不了就收手，不再空转剩余次数
                        }
                        anyDone = true;
                        sleepWithJitter(interval);
                    }
                    if (anyDone) {
                        handled++;
                        changed = true;
                    } else {
                        Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]完成失败["
                                + (actionType.isEmpty() ? "UNKNOWN" : actionType) + "]");
                        unresolved = true;
                    }
                    continue;
                }

                Log.record("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]未知状态[" + taskStatus + "]");
                unresolved = true;
            }

            // 核对本轮 doFarmTask 的结果（响应不可信，以任务列表为准）
            if (verifyPendingTasks(entry)) {
                changed = true;
            }
            Log.record("金豆[" + entry.alias + "]任务🗂️共[" + total + "]个#完成[" + handled + "]个");
            if (changed) {
                sleepWithJitter(interval);
                goldenbeansRpcCall.pullOf(entry.bizType, entry.source, "FARM_TASK", "TASK_LIST");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "runTaskList err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
        return !unresolved;
    }

    private boolean isBlacklisted(String blacklistKey, String taskName) {
        if (blacklist == null || blacklist.getValue() == null) {
            return false;
        }
        return blacklist.getValue().contains(blacklistKey) || blacklist.getValue().contains(taskName);
    }

    /**
     * 黑名单键：使用服务端稳定标识 taskId（缺失回退 groupId），
     * 避免展示标题变化导致黑名单匹配失效。
     */
    private String blacklistKey(JSONObject task) {
        String taskId = task.optString("taskId", "").trim();
        if (!taskId.isEmpty()) {
            return taskId;
        }
        String groupId = task.optString("groupId", "").trim();
        if (!groupId.isEmpty()) {
            return groupId;
        }
        return displayName(task);
    }

    /** 任务展示名：优先服务端标题（含 taskDisplayConfig.title） */
    private String displayName(JSONObject task) {
        String title = task.optString("title", "").trim();
        if (title.isEmpty()) {
            JSONObject displayConfig = task.optJSONObject("taskDisplayConfig");
            if (displayConfig != null) {
                title = displayConfig.optString("title", "").trim();
            }
        }
        if (title.isEmpty()) {
            title = task.optString("taskId", "").trim();
        }
        return title;
    }

    /** 支付类任务需要用户真实付款，无法自动完成 */
    private boolean isPayTask(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return false;
        }
        String upper = taskId.toUpperCase();
        return upper.contains("ZHIFU") || upper.contains("_PAY");
    }

    private boolean finishTask(GoldenBeansEntry entry, String taskId, String taskName, int stage,
                               boolean lastStage) {
        if (taskId == null || taskId.isEmpty()) {
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]缺少taskId#跳过");
            return false;
        }
        // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
        // 完成与否一律以任务列表为准（探针 probeGoldenBeansStatus 复核），响应不可信；
        // 兜底后的核对仍交给本模块的 verifyPendingTasks，故 listField/bizKey 传 null（黑名单由 verify 管）
        TaskAttemptPolicy.Site site = new TaskAttemptPolicy.Site(
                null, "金豆[" + entry.alias + "]任务", null, entry.taskSceneCode,
                (k) -> probeGoldenBeansStatus(entry, taskId));
        if (!lastStage) {
            // 多档里程碑的中间档：列表要打满才变完成，列表复核在这里永远判"未完成"，
            // 会把成功的提交改判成做不了、break 掉后续档位（与森林兑换同形态）
            site = site.withTrustResponse();
        }
        // key 带档位：多次任务每推进一档都是新的尝试，否则后续档位会被当日标记挡下
        Outcome outcome = TaskAttemptPolicy.handle(
                "goldenbeans::" + entry.alias + "/" + taskId + "#" + stage, taskName, null,
                () -> attemptFinishTask(entry, taskId, taskName), msg -> Log.goldenBeans(msg), site);
        return outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
    }

    /** 任务提交：做不了的不拉黑、交给兜底方案；其它错误码才算真做不了（仍计入自动拉黑） */
    private Outcome attemptFinishTask(GoldenBeansEntry entry, String taskId, String taskName) {
        try {
            JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.submitTaskOf(
                    entry.bizType, entry.source, entry.taskSceneCode, taskId));
            if (GoldenBeansSupport.ok(jo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务🧾完成[" + taskName + "]");
                return Outcome.DONE;
            }
            String failMessage = GoldenBeansSupport.describe(jo);
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 另一种实现方案（见 TaskAlternative）；乐园游戏类任务会被提交接口以 400000040 拒绝。
            // 本模块自行伪申报并登记同轮核对（verifyPendingTasks），故返回 FORGED 而不是交给通用类。
            // 场景码/来源用本入口自己的（各入口不同），不能用兜底默认的庄园
            if (TaskAlternative.hit(jo, entry.taskSceneCode)) {
                TaskAlternative.trigger(pendingVerifyTasks, taskId, taskName, taskId,
                        entry.taskSceneCode,
                        goldenbeansRpcCall.VERSION, entry.taskSceneCode, entry.source,
                        "金豆[" + entry.alias + "]任务", msg -> Log.goldenBeans(msg));
                return Outcome.FORGED;
            }
            // 命中不可自动完成的任务时按错误特征自动加入黑名单
            MessageUtil.checkResultCodeAndMarkTaskBlackList("GoldenBeansTaskList", taskId, jo);
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]完成失败[" + failMessage + "]");
            return Outcome.UNABLE;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "finishTask err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return Outcome.RETRY;
    }

    private boolean claimAward(GoldenBeansEntry entry, String taskId, String taskName) {
        if (taskId == null || taskId.isEmpty()) {
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️[" + taskName + "]缺少taskId#跳过领奖");
            return false;
        }
        try {
            JSONObject jo = GoldenBeansSupport.parse(goldenbeansRpcCall.claimAwardOf(
                    entry.bizType, entry.source, entry.taskSceneCode, taskId));
            if (GoldenBeansSupport.ok(jo)) {
                Log.goldenBeans("金豆[" + entry.alias + "]任务🎖️领取[" + taskName + "]"
                        + GoldenBeansSupport.awardText(jo));
                return true;
            }
            // 领奖收口：先按任务列表复核"已领到"，未确认才交自动拉黑（顺序由 TaskAward 固定）
            if (TaskAward.confirmReceivedOrBlackList("金豆[" + entry.alias + "]任务🎖️领取",
                    k -> probeGoldenBeansStatus(entry, taskId), taskId, taskName,
                    () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("GoldenBeansTaskList", taskId, jo),
                    msg -> Log.goldenBeans(msg))) {
                return true;
            }
            Log.goldenBeans("金豆[" + entry.alias + "]任务⚠️领取[" + taskName + "]失败["
                    + GoldenBeansSupport.describe(jo) + "]");
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "claimAward err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
        return false;
    }

    /** 列表状态探针：重拉本入口任务列表，按 taskId 匹配；仅待办视为未完成（消失即已完成）。 */
    private TaskAttemptPolicy.ProbeResult probeGoldenBeansStatus(GoldenBeansEntry entry, String taskId) {
        try {
            JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
            if (!GoldenBeansSupport.ok(syncJo)) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONArray taskList = syncJo.optJSONArray("taskList");
            if (taskList == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || !taskId.equals(task.optString("taskId", "").trim())) {
                    continue;
                }
                String status = task.optString("taskStatus", "").trim().toUpperCase();
                if (STATUS_TODO.equals(status)) {
                    return TaskAttemptPolicy.ProbeResult.TODO;
                }
                // DONE 与 RECEIVED 同义（本文件状态分派处：两者都算"已领取"），FINISHED / TO_RECEIVE 才是"待领"
                if (STATUS_RECEIVED.equals(status) || STATUS_DONE.equals(status)
                        || "HAS_RECEIVED".equals(status)) {
                    return TaskAttemptPolicy.ProbeResult.RECEIVED;
                }
                return TaskAttemptPolicy.ProbeResult.FINISHED;
            }
            // 任务已从列表消失：视为已完成且已领
            return TaskAttemptPolicy.ProbeResult.GONE;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "probeGoldenBeansStatus err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    /**
     * 核对「已触发但响应不可信」的任务：等几秒后重拉列表，
     * 仍为 TODO 的才计入自动拉黑。
     * <p>为什么以列表为准：{@code doFarmTask} 会回 102 等错码但任务其实已生效，
     * 服务端异步推进状态，只有列表里的 {@code taskStatus} 才是最终判据。
     *
     * @return 是否有任务确认完成（用于决定是否再同步一次列表）
     */
    private boolean verifyPendingTasks(GoldenBeansEntry entry) {
        return TaskAlternative.verify(pendingVerifyTasks, VERIFY_CFG, () -> {
            Set<String> notDone = new LinkedHashSet<>();
            collectNotDoneIds(goldenbeansRpcCall.pullOf(
                    entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"), notDone);
            return notDone;
        });
    }

    private static void collectNotDoneIds(String response, Set<String> out) {
        try {
            JSONObject data = GoldenBeansSupport.parse(response);
            if (data == null) return;
            JSONArray taskList = data.optJSONArray("taskList");
            if (taskList == null) return;
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null) continue;
                if ("TODO".equals(task.optString("taskStatus", "").trim().toUpperCase())) {
                    String id = task.optString("taskId", "").trim();
                    if (!id.isEmpty()) out.add(id);
                }
            }
        } catch (Throwable t) {
            Log.record("GoldenBeansTasks collectNotDoneIds err: " + t.getMessage());
        }
    }

    /**
     * 初始化任务黑白名单。
     * <p>
     * 先把两个入口的任务列表同步到本地 idMap（供配置界面选择），
     * 再把默认黑名单任务写入模块黑名单配置。
     */
    public void initTaskListMap() {
        try {
            GoldenBeansTaskListMap.load();
            // 默认黑名单：重试也不会有不同结果的任务（键=服务端taskId，值=展示名）
            // 1) 需要真实付款 / 真实业务动作
            // 2) 需要用户亲自确认
            // 3) 芝麻炼金入口的外部游戏：缺少可验证的完成闭环
            // 清单集中登记在 TaskBlacklist，此处只负责写入
            Map<String, String> defaultBlackList =
                    TaskBlacklist.presetBlackListNamed("goldenbeans", "GoldenBeansTaskList");
            Set<String> defaultKeys = new LinkedHashSet<>(defaultBlackList.keySet());

            for (Map.Entry<String, String> item : defaultBlackList.entrySet()) {
                GoldenBeansTaskListMap.add(item.getKey(), item.getValue());
            }

            // 两个入口的任务场景不同，需要分别同步各自的列表
            for (GoldenBeansEntry entry : GoldenBeansEntry.ALL) {
                JSONObject syncJo = GoldenBeansSupport.parse(goldenbeansRpcCall.pullOf(
                        entry.bizType, entry.source, "FARM_TASK", "TASK_LIST"));
                if (!GoldenBeansSupport.ok(syncJo)) {
                    continue;
                }
                JSONArray taskList = syncJo.optJSONArray("taskList");
                if (taskList == null) {
                    continue;
                }
                for (int i = 0; i < taskList.length(); i++) {
                    JSONObject task = taskList.optJSONObject(i);
                    if (task == null || !entry.taskSceneCode.equals(task.optString("sceneCode", "").trim())) {
                        continue;
                    }
                    String key = blacklistKey(task);
                    if (!key.isEmpty()) {
                        GoldenBeansTaskListMap.add(key, displayName(task));
                    }
                }
            }
            GoldenBeansTaskListMap.save();
            Log.goldenBeans("同步任务🉑金豆夺宝任务列表");

            if (!autoBlacklist) {
                return;
            }
            ConfigV2 config = ConfigV2.INSTANCE;
            ModelFields modelFields = config.getModelFieldsMap().get("goldenbeans");
            if (modelFields == null) {
                return;
            }
            SelectModelField taskListField = (SelectModelField) modelFields.get("GoldenBeansTaskList");
            if (taskListField == null) {
                return;
            }

            // 补齐默认黑名单任务
            Set<String> currentValues = taskListField.getValue();
            if (currentValues != null) {
                for (String task : defaultKeys) {
                    if (!currentValues.contains(task)) {
                        taskListField.add(task, 0);
                    }
                }
            }
            if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                Log.goldenBeans("黑白名单🈲金豆夺宝任务自动设置: " + taskListField.getValue());
            } else {
                Log.goldenBeans("黑白名单⚠️金豆夺宝任务设置失败");
            }
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "initTaskListMap err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
        }
    }
}
