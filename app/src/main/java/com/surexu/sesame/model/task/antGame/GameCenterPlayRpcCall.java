package com.surexu.sesame.model.task.antGame;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.task.antOrchard.AntOrchardRpcCall;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.TimeUtil;

/**
 * 游戏中心通用接口封装与权益解析。
 * <p>
 * 汇集「游戏乐园首页 / 外部游戏中心 / 浏览任务完成 / P2E 游戏时长上报 / 频道浮球」等在多个模块间
 * 复用的接口，并统一识别服务端下发的「游戏奖励机会」（deliveryBenefitList）。
 * <p>
 * 关键约定：游戏权益（可上报的游戏任务次数）与抽奖配额是两条独立的收敛链路，
 * 本类只负责暴露服务端契约证据，具体完成动作仍由各模块自己决定。
 */
public class GameCenterPlayRpcCall {

    private static final String TAG = GameCenterPlayRpcCall.class.getSimpleName();

    /** 单次上报时长（秒）：首次多报 1 秒，避免服务端按边界值判定不足 */
    private static final int FIRST_CHUNK_SECONDS = 31;
    /** 后续单次上报时长（秒） */
    private static final int CHUNK_SECONDS = 30;

    /**
     * 查询外部游戏中心首页（会员游戏乐园、频道游戏等场景的入口数据）。
     *
     * @param sceneId     场景标识
     * @param moduleId    模块标识，可为空串
     * @param guideType   引导类型，可为空串
     * @param source      来源标识
     * @param passThrough 透传参数，可为空串
     */
    public static String queryExternalGameCenter(String sceneId, String moduleId, String guideType,
                                                 String source, String passThrough) {
        String args1 = "[{\"__git\":\"9e159d58cce04c13a\",\"channelTaskPassThrough\":\"" + passThrough
                + "\",\"deviceLevel\":\"high\",\"guideType\":\"" + guideType
                + "\",\"moduleId\":\"" + moduleId + "\",\"sceneId\":\"" + sceneId
                + "\",\"source\":\"" + source + "\",\"unityDeviceLevel\":\"high\"}]";
        return ApplicationHook.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.external.gamecenter.queryHomePage", args1);
    }

    /**
     * 查询游戏中心首页。
     *
     * @param source          来源标识
     * @param trafficDriverId 流量位标识
     */
    public static String queryGameCenterHome(String source, String trafficDriverId) {
        String args1 = "[{\"source\":\"" + source + "\",\"sourceTab\":\"index\",\"trafficDriverId\":\""
                + trafficDriverId + "\"}]";
        return ApplicationHook.requestString("com.alipay.gamecenterhome.biz.rpc.queryHomePage", args1);
    }

    /**
     * 提交浏览类任务完成（外部游戏中心浏览奖励）。
     *
     * @param sceneId      场景标识
     * @param sceneExtInfo 服务端下发的场景签名
     */
    public static String completeExternalBrowseTask(String sceneId, String sceneExtInfo) {
        String args1 = "[{\"sceneExtInfo\":\"" + sceneExtInfo + "\",\"sceneId\":\"" + sceneId + "\"}]";
        return ApplicationHook.requestString(
                "com.alipay.gamecenteruprod.biz.rpc.external.gamecenter.completeBrowseTask", args1);
    }

    /**
     * 咨询频道游戏浮球（海洋频道等场景，返回服务端下发的时长等参数）。
     *
     * @param gameId         游戏标识
     * @param gameModuleId   游戏模块标识
     * @param source         来源标识
     * @param trafficDriverId 流量位标识
     */
    public static String consultGameFloatingBall(String gameId, String gameModuleId, String source,
                                                String trafficDriverId) {
        String args1 = "[{\"gameId\":\"" + gameId + "\",\"gameModuleId\":\"" + gameModuleId
                + "\",\"source\":\"" + source + "\",\"trafficDriverId\":\"" + trafficDriverId + "\"}]";
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.floatingball.consult", args1);
    }

    /**
     * 提交频道游戏浮球完成动作。
     *
     * @param gameId              游戏标识
     * @param gameModuleId        游戏模块标识
     * @param source              来源标识
     * @param trafficDriverId     流量位标识
     * @param floatingBallTypeList 浮球类型列表（原样回传服务端下发的结构）
     */
    public static String completeGameFloatingBall(String gameId, String gameModuleId, String source,
                                                 String trafficDriverId, String floatingBallTypeList) {
        String args1 = "[{\"gameId\":\"" + gameId + "\",\"gameModuleId\":\"" + gameModuleId
                + "\",\"source\":\"" + source + "\",\"oriChInfo\":\"" + source
                + "\",\"trafficDriverId\":\"" + trafficDriverId
                + "\",\"floatingBallTypeList\":" + floatingBallTypeList + "}]";
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.floatingball.complete", args1);
    }

    /**
     * 上报单次游戏游玩时长（P2E）。
     *
     * @param gameAppId 游戏小程序 appId
     * @param playTime  本次上报时长（秒）
     * @param source    来源标识
     */
    public static String submitPlayDuration(String gameAppId, int playTime, String source) {
        return AntOrchardRpcCall.submitUserPlayDurationAction(gameAppId, playTime, source);
    }

    /**
     * 按服务端下发的时长分段等待并上报游戏时长（P2E）。
     * <p>
     * 首段 31 秒、其后每段 30 秒，逐段上报，任一段失败即停止并返回 false。
     *
     * @param gameAppId    游戏小程序 appId
     * @param totalSeconds 服务端要求的游玩时长（秒）
     * @param source       来源标识
     * @return 是否全部上报成功
     */
    public static boolean reportPlayDurationInChunks(String gameAppId, int totalSeconds, String source) {
        if (gameAppId == null || gameAppId.isEmpty() || totalSeconds <= 0) {
            Log.i(TAG, "上报游戏时长参数不完整: appId=" + gameAppId + " seconds=" + totalSeconds);
            return false;
        }
        int remaining = totalSeconds;
        boolean firstChunk = true;
        while (remaining > 0) {
            int chunk = Math.min(firstChunk ? FIRST_CHUNK_SECONDS : CHUNK_SECONDS, remaining);
            TimeUtil.sleep(chunk * 1000L);
            if (!isAccepted(submitPlayDuration(gameAppId, chunk, source))) {
                Log.i(TAG, "游戏时长上报失败: appId=" + gameAppId + " chunk=" + chunk);
                return false;
            }
            remaining -= chunk;
            firstChunk = false;
        }
        return true;
    }

    /**
     * 解析响应并判断是否成功。
     * <p>
     * 游戏中心各接口的成功标识并不统一（success / isSuccess / resultCode / code），
     * 这里按并集判定，与上游保持一致。
     */
    public static boolean isAccepted(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        try {
            return isAcceptedJson(new JSONObject(response));
        } catch (Throwable th) {
            Log.i(TAG, "解析游戏中心响应失败: " + response);
            return false;
        }
    }

    /** 判断已解析的响应是否成功 */
    public static boolean isAcceptedJson(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
            return true;
        }
        String resultCode = jo.optString("resultCode");
        if ("100".equalsIgnoreCase(resultCode) || "200".equalsIgnoreCase(resultCode)
                || "SUCCESS".equalsIgnoreCase(resultCode)) {
            return true;
        }
        return "100000000".equalsIgnoreCase(jo.optString("code"));
    }

    /** 按路径取值，路径不存在时返回 null */
    public static JSONObject optObject(JSONObject jo, String... path) {
        if (jo == null) {
            return null;
        }
        JSONObject current = jo;
        for (String key : path) {
            if (current == null) {
                return null;
            }
            current = current.optJSONObject(key);
        }
        return current;
    }

    /**
     * 服务端下发的「游戏奖励机会」，与抽奖配额相互独立。
     * <p>
     * 保留原始任务与权益对象，模块可据此自行决定点击、完成、领奖或业务上报动作。
     */
    public static class DeliveryBenefitCandidate {

        private final String appId;
        private final String taskId;
        private final String title;
        private final String taskStatus;
        private final int rightTimes;
        private final int rightTimesLimit;
        private final JSONObject rawGame;
        private final JSONObject rawBenefit;

        public DeliveryBenefitCandidate(String appId, String taskId, String title, String taskStatus,
                                        int rightTimes, int rightTimesLimit,
                                        JSONObject rawGame, JSONObject rawBenefit) {
            this.appId = appId == null ? "" : appId;
            this.taskId = taskId == null ? "" : taskId;
            this.title = title == null ? "" : title;
            this.taskStatus = taskStatus == null ? "" : taskStatus;
            this.rightTimes = Math.max(rightTimes, 0);
            this.rightTimesLimit = Math.max(rightTimesLimit, 0);
            this.rawGame = rawGame;
            this.rawBenefit = rawBenefit;
        }

        public String getAppId() {
            return appId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getTitle() {
            return title;
        }

        public String getTaskStatus() {
            return taskStatus;
        }

        public int getRightTimes() {
            return rightTimes;
        }

        public int getRightTimesLimit() {
            return rightTimesLimit;
        }

        public JSONObject getRawGame() {
            return rawGame;
        }

        public JSONObject getRawBenefit() {
            return rawBenefit;
        }

        /** 去重键：同一 App 下的同一任务 */
        public String key() {
            return appId + ":" + taskId;
        }

        /** 快照键：状态或次数任一变化即视为新快照 */
        public String snapshotKey() {
            return key() + ":" + taskStatus + ":" + rightTimes + ":" + rightTimesLimit;
        }

        /** 是否仍有待领取的权益（与抽奖配额无关） */
        public boolean hasPendingReward() {
            return !"RECEIVED".equalsIgnoreCase(taskStatus) && rightTimes < rightTimesLimit;
        }

        /** 剩余可领取次数 */
        public int remainingRewards() {
            return Math.max(rightTimesLimit - rightTimes, 0);
        }
    }

    /**
     * 递归抽取响应中的游戏奖励机会，按出现顺序返回并按 key 去重。
     */
    public static List<DeliveryBenefitCandidate> collectDeliveryBenefitCandidates(Object source) {
        return new ArrayList<>(collectDeliveryBenefitCandidateMap(source).values());
    }

    /**
     * 与 {@link #collectDeliveryBenefitCandidates(Object)} 相同，但返回按 key 索引的映射，
     * 便于调用方按候选键回查同一任务的上报后状态。
     */
    public static Map<String, DeliveryBenefitCandidate> collectDeliveryBenefitCandidateMap(Object source) {
        Map<String, DeliveryBenefitCandidate> candidates = new LinkedHashMap<>();
        appendDeliveryBenefitCandidates(source, candidates);
        return candidates;
    }

    private static void appendDeliveryBenefitCandidates(Object source,
                                                        Map<String, DeliveryBenefitCandidate> candidates) {
        if (source instanceof JSONObject) {
            JSONObject obj = (JSONObject) source;
            String appId = obj.optString("appId", "").trim();
            String title = obj.optString("title", "").trim();
            if (title.isEmpty()) {
                title = appId;
            }
            JSONArray benefits = obj.optJSONArray("deliveryBenefitList");
            if (!appId.isEmpty() && benefits != null) {
                for (int i = 0; i < benefits.length(); i++) {
                    JSONObject benefit = benefits.optJSONObject(i);
                    if (benefit == null
                            || !"IEP_REQUEST".equalsIgnoreCase(benefit.optString("benefitType", ""))) {
                        continue;
                    }
                    String tracer = benefit.optString("iepTaskTracer", "");
                    String taskId = benefit.optString("iepTaskId", "").trim();
                    if (taskId.isEmpty()) {
                        taskId = extractTracerField(tracer, "taskType");
                    }
                    int rightTimesLimit = benefit.optInt("rightTimesLimit", 0);
                    if (taskId.isEmpty() || rightTimesLimit <= 0) {
                        continue;
                    }
                    String taskStatus = benefit.optString("taskStatus", "").trim();
                    if (taskStatus.isEmpty()) {
                        taskStatus = extractTracerField(tracer, "taskStatus");
                    }
                    DeliveryBenefitCandidate candidate = new DeliveryBenefitCandidate(
                            appId, taskId, title, taskStatus,
                            benefit.optInt("rightTimes", 0), rightTimesLimit, obj, benefit);
                    if (!candidates.containsKey(candidate.key())) {
                        candidates.put(candidate.key(), candidate);
                    }
                }
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                appendDeliveryBenefitCandidates(obj.opt(keys.next()), candidates);
            }
        } else if (source instanceof JSONArray) {
            JSONArray array = (JSONArray) source;
            for (int i = 0; i < array.length(); i++) {
                appendDeliveryBenefitCandidates(array.opt(i), candidates);
            }
        }
    }

    /** 从 ~ 分隔的 tracer 串中取出指定字段的值 */
    public static String extractTracerField(String tracer, String field) {
        if (tracer == null || tracer.isEmpty() || field == null || field.isEmpty()) {
            return "";
        }
        for (String part : tracer.split("~")) {
            if (part.startsWith(field + ":")) {
                return part.substring(field.length() + 1);
            }
        }
        return "";
    }
}
