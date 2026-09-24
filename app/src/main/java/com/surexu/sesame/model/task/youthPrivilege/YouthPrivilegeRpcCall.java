package com.surexu.sesame.model.task.youthPrivilege;

import com.surexu.sesame.hook.ApplicationHook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 青春特权（青春豆 / 青春体验金 / 每月理财福利）接口封装。
 * <p>
 * 说明：任务参数（taskCode / taskSource / taskType）始终由 queryTaskModel 的服务端下发结果提供，
 * 不能由本地文案推断，否则服务端会返回不支持 rpc 调用或任务全局配置不存在。
 */
public class YouthPrivilegeRpcCall {

    /** 青春特权渠道信息，服务端要求固定值 */
    public static final String CH_INFO = "searchxsth";

    private static final String MGW_PREFIX = "com.alipay.mobileopl.youthprivilege.rpc.mgw.";

    /** 青春豆签到与十连抽共用的场景码 */
    private static final String STUDENT_SCENE = "STUDENT_MONEY_CHECK_IN";

    private YouthPrivilegeRpcCall() {
    }

    private static String payload(JSONObject body) {
        return new JSONArray().put(body).toString();
    }

    private static String requestMgw(String method, JSONObject body) {
        return ApplicationHook.requestString(MGW_PREFIX + method, payload(body));
    }

    /* ============================ 签到 ============================ */

    /** 签到模型查询（服务端下发的 studentCheckInInfo.action 决定下一步） */
    public static String queryCheckInModel() {
        JSONObject body = new JSONObject();
        try {
            body.put("chInfo", CH_INFO);
            body.put("queryAd", true);
            body.put("skipTaskModule", false);
        } catch (Throwable ignored) {
        }
        return requestMgw("queryCheckInModel", body);
    }

    /** 执行青春豆签到 */
    public static String checkIn() {
        JSONObject body = new JSONObject();
        try {
            body.put("source", CH_INFO);
        } catch (Throwable ignored) {
        }
        return requestMgw("checkIn", body);
    }

    /* ============================ 青春任务 ============================ */

    /** 青春任务模型查询（含 taskGroupList / checkInRecommendTask / feedsTaskVO） */
    public static String queryTaskModel() {
        JSONObject body = new JSONObject();
        try {
            body.put("chInfo", CH_INFO);
            body.put("skipTaskList", false);
        } catch (Throwable ignored) {
        }
        return requestMgw("queryTaskModel", body);
    }

    /** 任务报名（浏览类任务需要先报名再完成） */
    public static String taskSignUp(String taskCode, String taskSource, String taskType) {
        return taskAction("taskSignUp", taskCode, taskSource, taskType);
    }

    /** 任务完成上报 */
    public static String taskComplete(String taskCode, String taskSource, String taskType) {
        return taskAction("taskComplete", taskCode, taskSource, taskType);
    }

    private static String taskAction(String method, String taskCode, String taskSource, String taskType) {
        JSONObject body = new JSONObject();
        try {
            body.put("taskCode", taskCode);
            body.put("taskSource", taskSource);
            body.put("taskType", taskType);
        } catch (Throwable ignored) {
        }
        return requestMgw(method, body);
    }

    /* ============================ 青春豆十连抽 ============================ */

    /** 十连抽首页（含 multiDrawsLotteryStatus / multiDrawsCost / totalAmount） */
    public static String queryLotteryIndex() {
        JSONObject body = new JSONObject();
        try {
            body.put("sceneCode", STUDENT_SCENE);
        } catch (Throwable ignored) {
        }
        return ApplicationHook.requestString(
                "alipay.membertangram.biz.rpc.student.queryLotteryIndex", payload(body));
    }

    /** 执行十连抽（付费动作，无幂等键，结果不确定时不得重复调用） */
    public static String multiDrawsLottery() {
        JSONObject body = new JSONObject();
        try {
            body.put("sceneCode", STUDENT_SCENE);
            body.put("systemVersion", android.os.Build.VERSION.RELEASE);
        } catch (Throwable ignored) {
        }
        return ApplicationHook.requestString(
                "alipay.membertangram.biz.rpc.student.multiDrawsLottery", payload(body));
    }

    /* ============================ 浏览奖励 ============================ */

    /** 滑动浏览 15 秒奖励领取 */
    public static String triggerFeedsPrize() {
        JSONObject body = new JSONObject();
        try {
            body.put("bizId", "DO_FEEDS_TASK");
            body.put("sceneCode", STUDENT_SCENE);
        } catch (Throwable ignored) {
        }
        return ApplicationHook.requestString(
                "alipay.membertangram.biz.rpc.student.triggerPointPrize", payload(body));
    }

    /* ============================ 青春体验金 ============================ */

    /**
     * 青春体验金奖励查询。
     *
     * @param month true 查当月区间，false 查当日
     * @param start 开始时间，格式 yyyy-MM-dd HH:mm:ss
     * @param end   结束时间，格式 yyyy-MM-dd HH:mm:ss
     */
    public static String queryTrialPrizes(boolean month, String start, String end) {
        JSONObject body = new JSONObject();
        try {
            body.put("playEntrance", "YEB_YONG_TYJ_PROMO");
            body.put("playActionCode", month ? "CAMP_MONTH_QUERY" : "CAMP_DAY_QUERY");
            body.put("startTime", start);
            body.put("endTime", end);
        } catch (Throwable ignored) {
        }
        return ApplicationHook.requestString(
                "com.alipay.yebpromobff.promosdk2024.prize.query", payload(body));
    }

    /** 青春体验金触发领取 */
    public static String triggerTrialPrize() {
        JSONObject body = new JSONObject();
        try {
            body.put("playEntrance", "YEB_YONG_TYJ_PROMO");
            body.put("playActionCode", "CAMP_TRIGGER");
        } catch (Throwable ignored) {
        }
        return ApplicationHook.requestString(
                "com.alipay.yebpromobff.promosdk2024.prize.trigger", payload(body));
    }

    /* ============================ 每月理财福利 ============================ */

    /** 每月理财福利首页查询（需要区划码 adCode） */
    public static String queryYouth100(String adCode) {
        JSONObject body = new JSONObject();
        try {
            body.put("sceneCode", "YOUTH100");
            body.put("chInfo", CH_INFO);
            body.put("adCode", adCode);
        } catch (Throwable ignored) {
        }
        return requestMgw("youth100.homepage.query", body);
    }

    /** 领取每月理财福利 */
    public static String receiveMonthlyPrivilege(String itemId, String moduleCode) {
        JSONObject body = new JSONObject();
        try {
            body.put("itemId", itemId);
            body.put("moduleCode", moduleCode);
        } catch (Throwable ignored) {
        }
        return requestMgw("youth100.privilege.receive", body);
    }
}
