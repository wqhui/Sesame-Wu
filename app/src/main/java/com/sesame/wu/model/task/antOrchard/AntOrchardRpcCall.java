package com.sesame.wu.model.task.antOrchard;

import android.util.Base64;

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sesame.wu.hook.ApplicationHook;
import com.sesame.wu.util.RandomUtil;
import com.sesame.wu.util.idMap.UserIdMap;

public class AntOrchardRpcCall {

    private static final String VERSION = "20250812.01";

    public static String orchardIndex() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardIndex", "[{\"inHomepage\":\"true\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String mowGrassInfo() {
        return ApplicationHook.requestString("com.alipay.antorchard.mowGrassInfo", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"showRanking\":true,\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String batchHireAnimalRecommend(String orchardUserId) {
        return ApplicationHook.requestString("com.alipay.antorchard.batchHireAnimalRecommend", "[{\"orchardUserId\":\"" + orchardUserId + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String batchHireAnimal(List<String> recommendGroupList) {
        return ApplicationHook.requestString("com.alipay.antorchard.batchHireAnimal", "[{\"recommendGroupList\":[" + String.join(",", recommendGroupList) + "],\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sceneType\":\"weed\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String extraInfoGet() {
        return ApplicationHook.requestString("com.alipay.antorchard.extraInfoGet", "[{\"from\":\"entry\",\"requestType\":\"NORMAL\",\"sceneCode\":\"FUGUO\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String extraInfoSet() {
        return ApplicationHook.requestString("com.alipay.antorchard.extraInfoSet", "[{\"bizCode\":\"fertilizerPacket\",\"bizParam\":{\"action\":\"queryCollectFertilizerPacket\"},\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String querySubplotsActivity(String treeLevel) {
        return ApplicationHook.requestString("com.alipay.antorchard.querySubplotsActivity", "[{\"activityType\":[\"WISH\",\"BATTLE\",\"HELP_FARMER\",\"DEFOLIATION\",\"CAMP_TAKEOVER\"],\"inHomepage\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\"," + "\"source" + "\":\"ch_appcenter__chsub_9patch\",\"treeLevel\":\"" + treeLevel + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String triggerSubplotsActivity(String activityId, String activityType, String optionKey) {
        return ApplicationHook.requestString("com.alipay.antorchard.triggerSubplotsActivity", "[{\"activityId\":\"" + activityId + "\",\"activityType\":\"" + activityType + "\",\"optionKey\":\"" + optionKey + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\"," + "\"source" + "\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String receiveOrchardRights(String activityId, String activityType) {
        return ApplicationHook.requestString("com.alipay.antorchard.receiveOrchardRights", "[{\"activityId\":\"" + activityId + "\",\"activityType\":\"" + activityType + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 七日礼包 */
    public static String drawLottery() {
        return ApplicationHook.requestString("com.alipay.antorchard.drawLottery", "[{\"lotteryScene\":\"receiveLotteryPlus\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* ================= 农场抽抽乐 ================= */

    public static String enterDrawActivity(String activityId) {
        try {
            JSONObject args = new JSONObject();
            args.put("activityId", activityId);
            args.put("context", new JSONObject().put("appMode", "normal"));
            args.put("requestType", "RPC");
            args.put("sceneCode", "ANTORCHARD_DRAW_TIMES");
            args.put("source", "antorchard");
            return ApplicationHook.requestString("com.alipay.antiepdrawprod.enterDrawActivityantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String listDrawTasks() {
        try {
            JSONObject args = new JSONObject();
            args.put("extend", new JSONObject().put("appMode", "normal"));
            args.put("requestType", "RPC");
            args.put("sceneCode", "ANTORCHARD_DRAW_TIMES_TASK");
            args.put("source", "antorchard");
            return ApplicationHook.requestString("com.alipay.antieptask.listTaskantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String finishDrawTask(String sceneCode, String taskType) {
        try {
            JSONObject args = new JSONObject();
            args.put("outBizNo", taskType + "_" + System.currentTimeMillis() + "_" + RandomUtil.getRandomString(8));
            args.put("sceneCode", sceneCode);
            args.put("source", "antorchard");
            args.put("taskType", taskType);
            return ApplicationHook.requestString("com.alipay.antieptask.finishTaskantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 农场抽抽乐-完成任务（互备腿）。
     * <p>首选接口 {@code com.alipay.antieptask.finishTaskantorchard} 对本场景常回 400000040，
     * 换另一种调用格式 {@code com.alipay.antiep.finishTask}（NORMAL + userId + version）再试。
     */
    public static String finishDrawTaskV2(String sceneCode, String taskType, String userId) {
        String outBizNo = userId + System.currentTimeMillis();
        String requestData = "[{\"outBizNo\":\"" + outBizNo + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode
                + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\",\"userId\":\"" + userId
                + "\",\"version\":\"20250812.01\"}]";
        return ApplicationHook.requestString("com.alipay.antiep.finishTask", requestData);
    }

    public static String receiveDrawTaskAward(String sceneCode, String taskType) {
        try {
            JSONObject args = new JSONObject();
            args.put("ignoreLimit", true);
            args.put("requestType", "RPC");
            args.put("sceneCode", sceneCode);
            args.put("source", "antorchard");
            args.put("taskType", taskType);
            return ApplicationHook.requestString("com.alipay.antieptask.receiveTaskAwardantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String syncDrawBalance(String activityId) {
        try {
            JSONObject args = new JSONObject();
            args.put("activityId", activityId);
            args.put("context", new JSONObject().put("appMode", "normal"));
            args.put("requestType", "RPC");
            args.put("sceneCode", "ANTORCHARD_DRAW_TIMES");
            args.put("source", "taskaward");
            return ApplicationHook.requestString("com.alipay.antiepdrawprod.drawSyncantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String batchDraw(String activityId, int times, String userId) {
        try {
            JSONObject args = new JSONObject();
            args.put("activityId", activityId);
            args.put("requestType", "RPC");
            args.put("sceneCode", "ANTORCHARD_DRAW_TIMES");
            args.put("source", "antorchard");
            args.put("times", times);
            args.put("userId", userId);
            return ApplicationHook.requestString("com.alipay.antiepdrawprod.batchDrawantorchard", new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    public static String orchardSyncIndex() {
        return ApplicationHook.requestString("com.alipay.antorchard.orchardSyncIndex", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"syncIndexTypes\":\"QUERY_MAIN_ACCOUNT_INFO\",\"version\":\"" + VERSION + "\"}]");
    }

    /**
     * 施肥。{@code plantScene} 必须是当前已切换到的场景（{@code main}/{@code yeb}）：
     * 报文里声明成别的场景，服务端会回 P03「平行场景信息异常」，余额宝(摇钱树)场景就施肥失败。
     */
    public static String orchardSpreadManure(String plantScene, Boolean useBatchSpread, String wua) {
        String useBatchSpreadStr = Boolean.TRUE.equals(useBatchSpread) ? "true" : "false";
        String scene = (plantScene == null || plantScene.isEmpty()) ? "main" : plantScene;
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSpreadManure", "[{\"plantScene\":\"" + scene + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"useBatchSpread\":" + useBatchSpreadStr + ",\"version\":\"" + VERSION + "\",\"wua\":\"" + (wua != null ? wua : "") + "\"}]");
    }

    public static String receiveTaskAward(String sceneCode, String taskType) {
        return ApplicationHook.requestString("com.alipay.antiep.receiveTaskAward", "[{\"ignoreLimit\":false,\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskType\":\"" + taskType + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardListTask() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardListTask", "[{\"plantHiddenMMC\":\"false\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* ================= 农场首页浮球游戏 ================= */

    /**
     * 首页信息流（feeds）：浮球游戏任务挂在 {@code feedsInfoList[].itemOutExtMap.orchardBenefitInfo} 里。
     * <p>过滤条件与前端一致：只要「qianyi / game」两类卡片，itemIdList 传空数组表示不限具体条目。
     */
    public static String orchardIndexFeeds() {
        try {
            JSONArray filterItemList = new JSONArray();
            filterItemList.put(new JSONObject().put("itemType", "qianyi").put("itemIdList", new JSONArray()));
            filterItemList.put(new JSONObject().put("itemType", "game").put("itemIdList", new JSONArray()));
            JSONObject args = new JSONObject();
            args.put("pageNum", 1);
            args.put("requestType", "NORMAL");
            args.put("sceneCode", "ORCHARD");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("version", VERSION);
            args.put("filterItemList", filterItemList);
            return ApplicationHook.requestString("com.alipay.antorchard.indexFeeds", new JSONArray().put(args).toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 首页投放位（indexDelivery）：浮球游戏任务挂在 {@code indexDeliveryList[].deliveryBenefitInfo} 里。
     */
    public static String orchardIndexDelivery() {
        try {
            JSONObject args = new JSONObject();
            args.put("requestType", "NORMAL");
            args.put("sceneCode", "ORCHARD");
            args.put("source", "ch_appcenter__chsub_9patch");
            args.put("version", VERSION);
            return ApplicationHook.requestString("com.alipay.antorchard.orchardIndexDelivery", new JSONArray().put(args).toString());
        } catch (Throwable t) {
            return null;
        }
    }

    public static String orchardSign() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSign", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"signScene\":\"ANTFARM_ORCHARD_SIGN_V2\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String finishTask(String sceneCode, String taskType) {
        String userId = UserIdMap.getCurrentUid();
        String args = "[{\"outBizNo\":\"" + userId + System.currentTimeMillis() + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"ANTFARM_ORCHARD\",\"taskType\":\"" + taskType + "\",\"userId\":\"" + userId + "\",\"version\":\"" + VERSION + "\"}]";
        return ApplicationHook.requestString("com.alipay.antiep.finishTask", args);
    }

    /**
     * 浮球游戏完成任务（{@code FLOAT_BALL_TASK}）专用：outBizNo 由调用方给定。
     * <p>与肥料任务的 {@link #finishTask(String, String)} 区别有两点：
     * 来源走首页入口（{@code ch_appcenter__chsub_9patch}，与浮球任务下发来源一致），
     * outBizNo 由调用方按「当天 + 任务」固定，重复提交时由服务端按幂等号去重，不会重复领奖。
     *
     * @param sceneCode 权益下发的 iepSceneCode
     * @param taskType  权益下发的 iepTaskId
     * @param outBizNo  调用方生成的幂等号（当天固定）
     * @param source    请求来源
     */
    public static String finishTask(String sceneCode, String taskType, String outBizNo, String source) {
        try {
            JSONObject args = new JSONObject();
            args.put("outBizNo", outBizNo);
            args.put("requestType", "NORMAL");
            args.put("sceneCode", sceneCode);
            args.put("source", source);
            args.put("taskType", taskType);
            args.put("version", VERSION);
            return ApplicationHook.requestString("com.alipay.antiep.finishTask", new JSONArray().put(args).toString());
        } catch (Throwable t) {
            return null;
        }
    }

    public static String triggerTbTask(String taskId, String taskPlantType) {
        return ApplicationHook.requestString("com.alipay.antfarm.triggerTbTask", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"taskId\":\"" + taskId + "\",\"taskPlantType\":\"" + taskPlantType + "\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSeedList() {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSeedList", "[{\"from\":\"SEED_LIST\",\"page\":0,\"pvuuid\":\"" + System.currentTimeMillis() + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcollect__chsub_my-recentlyUsed\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String orchardSelectSeed(String seedCode) {
        return ApplicationHook.requestString("com.alipay.antfarm.orchardSelectSeed", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"seedCode\":\"" + seedCode + "\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 砸金蛋 */
    public static String queryGameCenter() {
        return ApplicationHook.requestString("com.alipay.antorchard.queryGameCenter", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String noticeGame(String appId) {
        return ApplicationHook.requestString("com.alipay.antorchard.noticeGame", "[{\"appId\":\"" + appId + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    public static String submitUserAction(String gameId) {
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction", "[{\"actionCode\":\"enterGame\",\"gameId\":\"" + gameId + "\",\"paladinxVersion\":\"2.0.13\",\"source\":\"gameFramework\"}]");
    }

    /**
     * 浮球游戏「进入游戏」：游戏中心通用的用户行为上报（actionCode=enterGame）。
     * <p>这是浮球任务链路的第二步（第一步是 {@link #noticeGame(String)} 通知农场侧要进游戏）。
     */
    public static String enterHomeGame(String appId) {
        try {
            JSONObject args = new JSONObject();
            args.put("actionCode", "enterGame");
            args.put("gameId", appId);
            args.put("source", "gameFramework");
            return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserAction", new JSONArray().put(args).toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 浮球游戏事件上报（首帧 / 加载完成 / 游玩）。
     *
     * @param appId     游戏小程序 appId
     * @param eventId   GAME_FIRST_FRAME / loading_completed / game_play
     * @param eventKey  幂等号（同一事件重复上报时服务端据此去重）
     * @param elapsedMs 游玩时长（毫秒），仅 game_play 事件需要，其他事件传 0
     */
    public static String reportHomeGameEvent(String appId, String eventId, String eventKey, long elapsedMs) {
        try {
            JSONObject attributes = new JSONObject();
            attributes.put("CH_INFO", "lianyun_nc_sydb");
            attributes.put("PLAY_SCENE", "NORMAL");
            attributes.put("SCENE_ID", "nongchangleyuan");
            if ("GAME_FIRST_FRAME".equals(eventId)) {
                attributes.put("ALIVE_ENTER", "0");
            }
            // 注意：协议里的键就是 GAME_ELAPASED_TIME（少一个 S），由线上实际请求与成功响应确认过，不要"纠正"拼写
            if (elapsedMs > 0) {
                attributes.put("GAME_ELAPASED_TIME", elapsedMs);
            }
            String source;
            if ("GAME_FIRST_FRAME".equals(eventId)) {
                source = "paladinx_auto";
            } else if ("loading_completed".equals(eventId)) {
                source = "my.reportLoadingCompleted";
            } else {
                source = "my.reportGamePlay";
            }
            JSONObject args = new JSONObject();
            args.put("appId", appId);
            args.put("eventId", eventId);
            args.put("eventAttrMap", attributes);
            args.put("idempotentNo", eventKey);
            args.put("source", source);
            return ApplicationHook.requestString("com.alipay.gameevent.biz.rpc.submitEvent", new JSONArray().put(args).toString());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 首帧 / 加载完成事件不需要带游玩时长 */
    public static String reportHomeGameEvent(String appId, String eventId, String eventKey) {
        return reportHomeGameEvent(appId, eventId, eventKey, 0L);
    }

    /** 默认单次上报时长（秒），沿用历史行为 */
    private static final int DEFAULT_PLAY_TIME_SECONDS = 32;

    public static String submitUserPlayDurationAction(String gameAppId, String source) {
        return submitUserPlayDurationAction(gameAppId, DEFAULT_PLAY_TIME_SECONDS, source);
    }

    /**
     * 上报游戏游玩时长（P2E 时长上报统一入口）。
     *
     * @param gameAppId 游戏小程序 appId
     * @param playTime  本次上报的游玩时长（秒），可由服务端下发的任务契约提供
     * @param source    来源标识
     */
    public static String submitUserPlayDurationAction(String gameAppId, int playTime, String source) {
        int seconds = playTime > 0 ? playTime : DEFAULT_PLAY_TIME_SECONDS;
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.submitUserPlayDurationAction", "[{\"gameAppId\":\"" + gameAppId + "\",\"playTime\":" + seconds + ",\"source\":\"" + source + "\",\"statisticTag\":\"\"}]");
    }

    public static String smashedGoldenEgg() {
        return ApplicationHook.requestString("com.alipay.antorchard.smashedGoldenEgg", "[{\"requestType\":\"NORMAL\",\"seneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }

    /* 助力好友 */
    public static String achieveBeShareP2P(String friendUserId) {
        String shareId = Base64.encodeToString((friendUserId + "-" + RandomUtil.getRandom(5) + "ANTFARM_ORCHARD_SHARE_P2P").getBytes(), Base64.NO_WRAP);
        String args = "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ANTFARM_ORCHARD_SHARE_P2P\",\"shareId\":\"" + shareId + "\",\"source\":\"share\"}]";
        return ApplicationHook.requestString("com.alipay.antiep.achieveBeShareP2P", args);
    }

    public static String switchPlantScene(String sceneName) {
        return ApplicationHook.requestString("com.alipay.antorchard.switchPlantScene", "[{\"plantScene\":\"" + sceneName + "\",\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ANTFARM_ORCHARD\"}]");
    }

    public static String choosePrize(String orderId) {
        return ApplicationHook.requestString("com.alipay.antfarmlite.choosePrize", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"sendOrderId\":\"" + orderId + "\",\"source\":\"ANTFARM_ORCHARD\"}]");
    }

    public static String yebPlantSceneRevenuePage() {
        // gamecenteruprod 系列接口不吃 sceneCode（那是 antorchard 系列的参数），
        // 传 ORCHARD 会被服务端判为 error 3000「系统出错」；同系列的 queryPointBallList 用极简参数即可
        return ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.yebPlantSceneRevenuePage", "[{}]");
    }

    public static String triggerYebMoneyTree() {
        return ApplicationHook.requestString("com.alipay.yebbffweb.needle.yebHome.moneyTree.trigger", "[{\"sceneType\":\"default\",\"type\":\"trigger\"}]");
    }

    /**
     * 领取回访奖励
     */
    public static String receiveOrchardVisitAward() {
        return ApplicationHook.requestString("com.alipay.antorchard.receiveOrchardVisitAward", "[{\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"ch_appcenter__chsub_9patch\",\"version\":\"" + VERSION + "\"}]");
    }


    public static String queryOptionalPlay() {
        String args1 = "[{\"bizType\":\"ANTORCHARD\",\"commonDegradeFilterRequest\":{\"appMode\":\"normal\",\"deviceLevel\":\"high\",\"initialized\":true,\"platform\":\"Android\",\"unityDeviceLevel\":\"high\"},\"playTypeList\":[\"TASK_TRIGGER\",\"TOP_UP_COUPON\"],\"recentAppRecordList\":[],\"requestType\":\"NORMAL\",\"sceneCode\":\"ORCHARD\",\"source\":\"H5\",\"version\":\"" + VERSION + "\"}]";
        return ApplicationHook.requestString("com.alipay.charitygamecenter.queryOptionalPlay", args1);
    }

    public static String receiveTaskAwardantorchard(int awardCountForReceive, String sceneCode, String taskType) {
        String args1 = "[{\"awardCountForReceive\":" + awardCountForReceive + ",\"ignoreLimit\":true,\"requestType\":\"RPC\",\"sceneCode\":\"" + sceneCode + "\",\"source\":\"antorchard\",\"taskType\":\"" + taskType + "\"}]";
        return ApplicationHook.requestString("com.alipay.antieptask.receiveTaskAwardantorchard", args1);
    }


    /**
     * 带参数的 orchardSyncIndex（适配第二个文件中的调用）
     * 注意：这里参数被忽略，调用无参版本
     */
    public static String orchardSyncIndex(String param) {
        return orchardSyncIndex();
    }

    /*
     * 适配施肥调用（两个参数）- 已删除，使用统一版本
     */
    // public static String orchardSpreadManure(String wua, String source) {
    //     return orchardSpreadManure(false, wua);
    // }
}