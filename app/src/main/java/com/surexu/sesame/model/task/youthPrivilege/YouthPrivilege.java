package com.surexu.sesame.model.task.youthPrivilege;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import com.surexu.sesame.data.ModelFields;
import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.IntegerModelField;
import com.surexu.sesame.data.modelFieldExt.SelectModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.entity.AreaCode;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;

/**
 * 青春特权模块（青春豆签到 / 青春任务 / 青春体验金 / 每月理财福利 / 青春豆十连抽）。
 * <p>
 * 与森林模块里原有的「青春特权 | 森林道具」互不重复：双击卡、保护罩、加速器的领取仍由
 * {@code AntForestV2} 的 {@code Privilege} 负责，本模块只负责青春豆这一条链路。
 * <p>
 * 所有写操作都遵循「先查状态 → 再动作 → 回查确认」的顺序；付费动作（十连抽）在结果不确定时
 * 只标记待确认、不重复消费。
 */
public class YouthPrivilege extends ModelTask {

    private static final String TAG = YouthPrivilege.class.getSimpleName();

    private static final String PREFIX = "青春特权🌸";

    /** 服务端返回的成功标识 */
    private static final String RPC_SUCCESS = "SUCCESS";

    /** 签到动作：可签到 / 已签到 */
    private static final String ACTION_CHECK_IN = "CHECK_IN";
    private static final String ACTION_CHECKED_IN = "DO_TASK";

    /** 任务状态 */
    private static final String STATUS_COMPLETE = "COMPLETE";
    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_TO_APPLY = "TO_APPLY";

    /** 任务动作 */
    private static final String ACTION_DO_NOTHING = "DO_NOTHING";
    private static final String ACTION_SIGNUP = "SIGNUP";
    private static final String ACTION_COMPLETE = "COMPLETE";

    /** 仅处理浏览类任务，其余类型服务端未验证闭环 */
    private static final String TASK_TYPE_BROWSER = "BROWSER";

    /** 浏览奖励任务固定 id */
    private static final String FEEDS_TASK_ID = "DO_FEEDS_TASK";

    /** 每月理财福利的模块码 */
    private static final String MONTHLY_MODULE_ID = "FIN_MONTHLY";

    // ===== 当日标记 =====
    private static final String FLAG_CHECK_IN_DONE = "youthPrivilege::checkInDone";
    private static final String FLAG_TASKS_DONE = "youthPrivilege::tasksDone";
    private static final String FLAG_TRIAL_TRIGGERED = "youthPrivilege::trialPrizeTriggered";
    private static final String FLAG_MONTHLY_DONE = "youthPrivilege::monthlyDone";
    /** 十连抽待确认标记：当日已发出请求但尚未确认额度变化时为 true，禁止再次消费 */
    private static final String FLAG_MULTI_DRAWS_PENDING = "youthPrivilege::multiDrawsPending";

    private BooleanModelField checkIn;
    private BooleanModelField youthTasks;
    private BooleanModelField trialPrize;
    private BooleanModelField monthly;
    private BooleanModelField multiDraws;
    private SelectModelField cityCodeList;
    private IntegerModelField executeInterval;

    @Override
    public String getName() {
        return "青春特权";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.MEMBER;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(checkIn = new BooleanModelField("youthPrivilegeCheckIn", "青春特权 | 签到青春豆", false));
        modelFields.addField(youthTasks = new BooleanModelField("youthPrivilegeTasks", "青春特权 | 青春任务与浏览奖励", false));
        modelFields.addField(trialPrize = new BooleanModelField("youthPrivilegeTrialPrize", "青春特权 | 青春体验金", false));
        modelFields.addField(monthly = new BooleanModelField("youthPrivilegeMonthly", "青春特权 | 每月理财福利", false));
        modelFields.addField(multiDraws = new BooleanModelField("youthPrivilegeMultiDraws", "青春特权 | 青春豆十连抽", false));
        modelFields.addField(cityCodeList = new SelectModelField("youthPrivilegeCityCodeList", "青春特权 | 理财福利城市",
                new LinkedHashSet<>(), AreaCode::getList));
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
            if (checkIn.getValue()) {
                handleCheckIn();
            }
            if (youthTasks.getValue()) {
                handleYouthTasks();
            }
            if (trialPrize.getValue()) {
                handleTrialPrize();
            }
            if (monthly.getValue()) {
                handleMonthlyPrivilege();
            }
            if (multiDraws.getValue()) {
                handleMultiDraws();
            }
        } catch (Throwable th) {
            Log.i(TAG, "run err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private int interval() {
        Integer value = executeInterval.getValue();
        return value == null ? 800 : Math.max(value, 500);
    }

    private void pause(int interval) {
        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ============================ 签到青春豆 ============================ */

    private void handleCheckIn() {
        if (Status.hasFlagToday(FLAG_CHECK_IN_DONE)) {
            return;
        }
        try {
            JSONObject model = new JSONObject(YouthPrivilegeRpcCall.queryCheckInModel());
            if (!isYouthSuccess(model)) {
                Log.error(TAG + " " + "青春特权签到模型查询失败:" + model);
                return;
            }
            JSONObject checkInInfo = model.optJSONObject("studentCheckInInfo");
            String action = checkInInfo == null ? "" : checkInInfo.optString("action");
            if (ACTION_CHECK_IN.equals(action)) {
                JSONObject result = new JSONObject(YouthPrivilegeRpcCall.checkIn());
                if (!isYouthSuccess(result)) {
                    Log.error(TAG + " " + "青春特权签到执行失败:" + result);
                    return;
                }
                confirmCheckIn();
            } else if (ACTION_CHECKED_IN.equals(action)) {
                Status.flagToday(FLAG_CHECK_IN_DONE);
                Log.forest(PREFIX + "签到已完成#action=" + action);
            } else {
                Log.forest(PREFIX + "签到暂不处理#action=" + (action.isEmpty() ? "UNKNOWN" : action));
            }
        } catch (Throwable th) {
            Log.i(TAG, "checkIn err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /** 动作成功后必须回查确认，避免「请求成功但服务端未推进」被误判为完成 */
    private void confirmCheckIn() {
        try {
            pause(interval());
            JSONObject confirmation = new JSONObject(YouthPrivilegeRpcCall.queryCheckInModel());
            if (!isYouthSuccess(confirmation)) {
                Log.error(TAG + " " + "青春特权签到回查失败:" + confirmation);
                return;
            }
            JSONObject checkInInfo = confirmation.optJSONObject("studentCheckInInfo");
            String action = checkInInfo == null ? "" : checkInInfo.optString("action");
            if (ACTION_CHECKED_IN.equals(action)) {
                Status.flagToday(FLAG_CHECK_IN_DONE);
                Log.forest(PREFIX + "签到回查确认完成");
            } else {
                Log.error(TAG + " " + "青春特权签到执行成功但未确认进展#action="
                        + (action.isEmpty() ? "UNKNOWN" : action) + " raw=" + confirmation);
            }
        } catch (Throwable th) {
            Log.i(TAG, "confirmCheckIn err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* ============================ 青春任务与浏览奖励 ============================ */

    /**
     * 简化版任务流：只处理服务端已确认闭环的浏览类任务。
     * <p>
     * 每轮「查状态 → 报名/完成 → 下一轮回查」，最多 3 轮；某轮没有可执行动作即认为服务端已无待处理项。
     */
    private void handleYouthTasks() {
        if (Status.hasFlagToday(FLAG_TASKS_DONE)) {
            return;
        }
        try {
            boolean allResolved = false;
            for (int round = 0; round < 3; round++) {
                JSONObject model = new JSONObject(YouthPrivilegeRpcCall.queryTaskModel());
                if (!isYouthSuccess(model)) {
                    Log.error(TAG + " " + "青春特权任务查询失败:" + model);
                    return;
                }

                boolean progressed = false;
                List<JSONObject> tasks = collectYouthTasks(model);
                for (JSONObject task : tasks) {
                    String status = task.optString("taskStatus");
                    String actionType = task.optString("taskAction");
                    if (STATUS_COMPLETE.equals(status) || ACTION_DO_NOTHING.equals(actionType)) {
                        continue;
                    }
                    String taskCode = task.optString("taskCode");
                    String taskSource = task.optString("taskSource");
                    String taskType = task.optString("taskType");
                    if (taskCode.isEmpty() || taskSource.isEmpty() || taskType.isEmpty()) {
                        Log.error(TAG + " " + "青春特权任务缺少服务端执行参数:" + task);
                        continue;
                    }
                    if (!TASK_TYPE_BROWSER.equals(taskType)) {
                        Log.record(PREFIX + "任务[跳过非浏览任务] code=" + taskCode
                                + " type=" + taskType + " status=" + (status.isEmpty() ? "UNKNOWN" : status));
                        continue;
                    }
                    if (STATUS_TO_APPLY.equals(status) || ACTION_SIGNUP.equals(actionType)) {
                        JSONObject response = new JSONObject(
                                YouthPrivilegeRpcCall.taskSignUp(taskCode, taskSource, taskType));
                        logTaskAction("报名", taskCode, response);
                        progressed = true;
                    } else if (STATUS_PROCESSING.equals(status) || ACTION_COMPLETE.equals(actionType)) {
                        JSONObject response = new JSONObject(
                                YouthPrivilegeRpcCall.taskComplete(taskCode, taskSource, taskType));
                        logTaskAction("完成", taskCode, response);
                        progressed = true;
                    }
                }

                JSONObject feeds = model.optJSONObject("feedsTaskVO");
                if (feeds != null && STATUS_PROCESSING.equals(feeds.optString("feedsTaskStatus"))) {
                    JSONObject response = new JSONObject(YouthPrivilegeRpcCall.triggerFeedsPrize());
                    logTaskAction("浏览奖励", FEEDS_TASK_ID, response);
                    progressed = true;
                }

                if (!progressed) {
                    allResolved = true;
                    break;
                }
                pause(interval());
            }
            if (allResolved) {
                Status.flagToday(FLAG_TASKS_DONE);
                Log.forest(PREFIX + "任务服务端已无待处理项");
            } else {
                Log.forest(PREFIX + "任务仍有待回查项，下轮继续");
            }
        } catch (Throwable th) {
            Log.i(TAG, "youthTasks err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void logTaskAction(String action, String taskCode, JSONObject response) {
        if (isYouthSuccess(response)) {
            Log.forest(PREFIX + "任务[" + action + "]已受理 code=" + taskCode);
        } else {
            Log.error(TAG + " " + "青春特权任务[" + action + "]失败 code=" + taskCode + " raw=" + response);
        }
    }

    /** 收集 studentTaskModule 下的任务（taskGroupList[].taskList[] + checkInRecommendTask） */
    private List<JSONObject> collectYouthTasks(JSONObject model) {
        List<JSONObject> tasks = new ArrayList<>();
        JSONObject module = model.optJSONObject("studentTaskModule");
        if (module == null) {
            return tasks;
        }
        JSONArray groups = module.optJSONArray("taskGroupList");
        if (groups != null) {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null) {
                    continue;
                }
                JSONArray taskList = group.optJSONArray("taskList");
                if (taskList == null) {
                    continue;
                }
                for (int j = 0; j < taskList.length(); j++) {
                    JSONObject task = taskList.optJSONObject(j);
                    if (task == null) {
                        continue;
                    }
                    // 进度字段可能只下发在分组上，缺省时从分组继承
                    if (!task.has("currentCount") && group.has("currentCount")) {
                        try {
                            task.put("currentCount", group.get("currentCount"));
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!task.has("totalCount") && group.has("totalCount")) {
                        try {
                            task.put("totalCount", group.get("totalCount"));
                        } catch (Throwable ignored) {
                        }
                    }
                    tasks.add(task);
                }
            }
        }
        JSONObject recommend = module.optJSONObject("checkInRecommendTask");
        if (recommend != null) {
            tasks.add(recommend);
        }
        return tasks;
    }

    /* ============================ 青春体验金 ============================ */

    /**
     * 青春体验金（日奖励 + 月奖励）。
     * <p>
     * 安全约束：同一天最多触发一次领取；已有未确认订单时只回查、不再触发，
     * 因为触发接口没有幂等键，重复调用可能重复发奖或直接失败。
     */
    private void handleTrialPrize() {
        try {
            List<JSONObject> daily = queryTrialAwards(false);
            if (daily == null) {
                return;
            }
            List<JSONObject> confirmed = filterConfirmed(daily);
            if (!confirmed.isEmpty()) {
                Log.forest(PREFIX + "体验金当日发放已确认#" + describeAwards(confirmed));
                return;
            }
            if (!daily.isEmpty()) {
                Log.forest(PREFIX + "体验金当日已有订单但券状态未确认，保留后续查询");
                return;
            }
            if (Status.hasFlagToday(FLAG_TRIAL_TRIGGERED)) {
                Log.forest(PREFIX + "体验金当日已触发过领取，本轮仅回查");
                return;
            }

            Status.flagToday(FLAG_TRIAL_TRIGGERED);
            JSONObject response = new JSONObject(YouthPrivilegeRpcCall.triggerTrialPrize());
            if (!response.optBoolean("success")) {
                Log.error(TAG + " " + "青春体验金领取失败, 本轮不重复触发 code=" + response.optString("resultCode")
                        + " needRetry=" + response.opt("needRetry") + " raw=" + response);
            }

            pause(interval());
            List<JSONObject> afterDaily = queryTrialAwards(false);
            List<JSONObject> afterConfirmed = afterDaily == null ? new ArrayList<>() : filterConfirmed(afterDaily);
            // 日奖励不以月奖励代替，日奖励未确认时保留后续查询
            if (!afterConfirmed.isEmpty()) {
                Log.forest(PREFIX + "体验金当日发放确认#" + describeAwards(afterConfirmed));
            } else {
                Log.forest(PREFIX + "体验金当日到账未确认，保留后续查询");
            }
        } catch (Throwable th) {
            Log.i(TAG, "trialPrize err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /** 查询体验金奖励；失败返回 null，成功返回去重后的奖励列表 */
    private List<JSONObject> queryTrialAwards(boolean month) {
        try {
            String[] range = trialRange(month);
            JSONObject response = new JSONObject(
                    YouthPrivilegeRpcCall.queryTrialPrizes(month, range[0], range[1]));
            JSONArray list = response.optJSONArray("result");
            if (!response.optBoolean("success") || list == null) {
                Log.error(TAG + " " + "青春体验金查询失败 month=" + month + " raw=" + response);
                return null;
            }
            List<JSONObject> awards = new ArrayList<>();
            List<String> seen = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                JSONObject award = list.optJSONObject(i);
                if (award == null) {
                    continue;
                }
                String orderId = award.optString("sendOrderId");
                if (!orderId.isEmpty() && seen.contains(orderId)) {
                    continue;
                }
                seen.add(orderId);
                awards.add(award);
            }
            return awards;
        } catch (Throwable th) {
            Log.i(TAG, "queryTrialAwards err:");
            Log.printStackTrace(TAG, th);
            return null;
        }
    }

    /** 到账确认：发放成功 + 有订单号 + 至少一张券有券号 */
    private List<JSONObject> filterConfirmed(List<JSONObject> awards) {
        List<JSONObject> confirmed = new ArrayList<>();
        for (JSONObject award : awards) {
            if (!RPC_SUCCESS.equals(award.optString("sendStatus"))) {
                continue;
            }
            if (award.optString("sendOrderId").isEmpty()) {
                continue;
            }
            JSONArray vouchers = award.optJSONArray("voucherInfo");
            if (vouchers == null) {
                continue;
            }
            for (int i = 0; i < vouchers.length(); i++) {
                JSONObject voucher = vouchers.optJSONObject(i);
                if (voucher != null && !voucher.optString("voucher_id").isEmpty()) {
                    confirmed.add(award);
                    break;
                }
            }
        }
        return confirmed;
    }

    private String describeAwards(List<JSONObject> awards) {
        StringBuilder builder = new StringBuilder();
        for (JSONObject award : awards) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append("订单=").append(award.optString("sendOrderId"))
                    .append(" 金额=").append(award.optString("amount"))
                    .append(" 券状态=").append(award.optString("finEquityStatus"));
        }
        return builder.toString();
    }

    /** 体验金查询区间（按北京时间取当日或当月首末日） */
    private String[] trialRange(boolean month) {
        SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        day.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        String start;
        String end;
        if (month) {
            Calendar first = (Calendar) calendar.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            start = day.format(first.getTime());
            Calendar last = (Calendar) calendar.clone();
            last.set(Calendar.DAY_OF_MONTH, last.getActualMaximum(Calendar.DAY_OF_MONTH));
            end = day.format(last.getTime());
        } else {
            start = day.format(new Date());
            end = start;
        }
        return new String[]{start + " 00:00:00", end + " 23:59:59"};
    }

    /* ============================ 每月理财福利 ============================ */

    /**
     * 每月理财福利：需要区划码，配置里手动选择城市（与仓库既有的经纬度手动填写约定一致）。
     */
    private void handleMonthlyPrivilege() {
        if (Status.hasFlagToday(FLAG_MONTHLY_DONE)) {
            return;
        }
        try {
            String adCode = selectedCityCode();
            if (adCode.isEmpty()) {
                Log.record(PREFIX + "每月理财福利未选择城市，跳过#请先在配置中选择「理财福利城市」");
                return;
            }
            boolean progressed = false;
            for (int round = 0; round < 3; round++) {
                JSONObject response = new JSONObject(YouthPrivilegeRpcCall.queryYouth100(adCode));
                if (!isYouthSuccess(response)) {
                    Log.error(TAG + " " + "青春每月权益查询失败 raw=" + response);
                    return;
                }
                boolean claimed = claimMonthlyItems(response);
                if (!claimed) {
                    break;
                }
                progressed = true;
                pause(interval());
            }
            if (progressed) {
                Status.flagToday(FLAG_MONTHLY_DONE);
                Log.forest(PREFIX + "每月理财福利已处理");
            }
        } catch (Throwable th) {
            Log.i(TAG, "monthly err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /** 领取 FIN_MONTHLY 模块下可领取的权益，返回是否发起了领取 */
    private boolean claimMonthlyItems(JSONObject response) {
        JSONArray feeds = response.optJSONArray("feeds");
        if (feeds == null) {
            return false;
        }
        boolean claimed = false;
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.optJSONObject(i);
            if (feed == null) {
                continue;
            }
            JSONArray modules = feed.optJSONArray("modules");
            if (modules == null) {
                continue;
            }
            for (int j = 0; j < modules.length(); j++) {
                JSONObject module = modules.optJSONObject(j);
                if (module == null || !MONTHLY_MODULE_ID.equals(module.optString("moduleId"))) {
                    continue;
                }
                JSONArray items = module.optJSONArray("items");
                if (items == null) {
                    continue;
                }
                for (int k = 0; k < items.length(); k++) {
                    JSONObject item = items.optJSONObject(k);
                    if (item == null) {
                        continue;
                    }
                    String privilegeId = item.optString("privilegeId");
                    if (privilegeId.isEmpty()) {
                        continue;
                    }
                    String status = item.optString("cardStatus");
                    JSONObject actionButton = item.optJSONObject("actionButton");
                    String actionType = actionButton == null ? "" : actionButton.optString("actionType");
                    if ("COOLDOWN".equals(status) || "RECEIVED".equals(status)) {
                        continue;
                    }
                    if (!"AVAILABLE".equals(status) || !"CLAIM".equals(actionType)) {
                        continue;
                    }
                    JSONObject result = new JSONObject(
                            YouthPrivilegeRpcCall.receiveMonthlyPrivilege(privilegeId, MONTHLY_MODULE_ID));
                    if (isYouthSuccess(result)) {
                        Log.forest(PREFIX + "每月理财福利[" + item.optString("title", privilegeId) + "]已领取");
                        claimed = true;
                    } else {
                        Log.error(TAG + " " + "青春每月权益领取失败 id=" + privilegeId + " raw=" + result);
                    }
                }
            }
        }
        return claimed;
    }

    /** 取配置中选中的城市区划码（多选时取第一个） */
    private String selectedCityCode() {
        java.util.Set<String> values = cityCodeList.getValue();
        if (values == null || values.isEmpty()) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    /* ============================ 青春豆十连抽 ============================ */

    /**
     * 青春豆十连抽。
     * <p>
     * 十连抽是付费动作且没有幂等键：只有服务端明确返回可抽（DRAW）、余额足够、
     * 且当日没有待确认请求时才发起；发出后必须回查额度状态，未确认则当日不再重试。
     */
    private void handleMultiDraws() {
        try {
            JSONObject index = new JSONObject(YouthPrivilegeRpcCall.queryLotteryIndex());
            if (!isYouthSuccess(index)) {
                Log.error(TAG + " " + "青春豆十连抽首页查询失败:" + index);
                return;
            }
            String status = index.optString("multiDrawsLotteryStatus");
            if ("LIMIT".equals(status)) {
                Status.clearFlag(FLAG_MULTI_DRAWS_PENDING);
                Log.forest(PREFIX + "十连抽额度已用完，当前余额#" + index.optString("totalAmount"));
                return;
            }
            if (!"DRAW".equals(status)) {
                Log.forest(PREFIX + "十连抽当前不可执行#status=" + status);
                return;
            }
            if (Status.hasFlagToday(FLAG_MULTI_DRAWS_PENDING)) {
                Log.forest(PREFIX + "十连抽已有请求待确认，仅回查额度，暂不再次消费");
                return;
            }
            BigDecimal cost = toDecimal(index.optString("multiDrawsCost"));
            BigDecimal balance = toDecimal(index.optString("totalAmount"));
            if (cost == null || balance == null || cost.signum() <= 0 || balance.signum() < 0) {
                Log.error(TAG + " " + "青春豆十连抽缺少有效成本或余额:" + index);
                return;
            }
            if (balance.compareTo(cost) < 0) {
                Log.forest(PREFIX + "十连抽余额不足#余额=" + balance + " 成本=" + cost);
                return;
            }

            Status.flagToday(FLAG_MULTI_DRAWS_PENDING);
            JSONObject result = null;
            try {
                result = new JSONObject(YouthPrivilegeRpcCall.multiDrawsLottery());
            } catch (Throwable th) {
                Log.error(TAG + " 青春豆十连抽响应异常，保留回查");
                Log.printStackTrace(TAG, th);
            }
            if (result != null && isYouthSuccess(result)) {
                JSONArray prizes = result.optJSONArray("lotteryPrizeInfoList");
                if (prizes == null) {
                    Log.error(TAG + " " + "青春豆十连抽响应缺少实际奖励列表:" + result);
                }
                for (int i = 0; i < (prizes == null ? 0 : prizes.length()); i++) {
                    JSONObject prize = prizes == null ? null : prizes.optJSONObject(i);
                    if (prize == null) {
                        continue;
                    }
                    Log.forest(PREFIX + "十连抽🎁[" + prize.optString("title") + "] " + prize.optString("subTitle"));
                }
            } else if (result != null) {
                Log.error(TAG + " " + "青春豆十连抽失败，不立即重试:" + result);
            }

            pause(interval());
            JSONObject confirmed = new JSONObject(YouthPrivilegeRpcCall.queryLotteryIndex());
            if (!isYouthSuccess(confirmed)) {
                Log.error(TAG + " " + "青春豆十连抽回查失败，保留待确认:" + confirmed);
                return;
            }
            if ("LIMIT".equals(confirmed.optString("multiDrawsLotteryStatus"))) {
                Status.clearFlag(FLAG_MULTI_DRAWS_PENDING);
                Log.forest(PREFIX + "十连抽额度已确认消耗，剩余余额#" + confirmed.optString("totalAmount"));
            } else {
                Log.error(TAG + " " + "青春豆十连抽额度尚未确认变化，不重复抽取:" + confirmed);
            }
        } catch (Throwable th) {
            Log.i(TAG, "multiDraws err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private BigDecimal toDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /* ============================ 公共 ============================ */

    private boolean isYouthSuccess(JSONObject response) {
        return response != null && response.optBoolean("success") && RPC_SUCCESS.equals(response.optString("resultCode"));
    }
}
