package com.sesame.wu.model.task.antSports;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.sesame.wu.util.compat.XC_MethodHook;
import com.sesame.wu.util.XHelpers;
import com.sesame.wu.data.ConfigV2;
import com.sesame.wu.data.ModelFields;
import com.sesame.wu.data.ModelGroup;
import com.sesame.wu.data.TokenConfig;
import com.sesame.wu.data.modelFieldExt.BooleanModelField;
import com.sesame.wu.data.modelFieldExt.ChoiceModelField;
import com.sesame.wu.data.modelFieldExt.IntegerModelField;
import com.sesame.wu.data.modelFieldExt.SelectModelField;
import com.sesame.wu.data.task.ModelTask;
import com.sesame.wu.data.task.TaskAttemptPolicy;
import com.sesame.wu.data.task.TaskAttemptPolicy.Outcome;
import com.sesame.wu.data.task.TaskAward;
import com.sesame.wu.entity.AlipayAntSportsTaskList;
import com.sesame.wu.entity.WalkPathThemeMapList;
import com.sesame.wu.entity.AlipayUser;
import com.sesame.wu.entity.WalkPath;
import com.sesame.wu.hook.ApplicationHook;
import com.sesame.wu.hook.Toast;
import com.sesame.wu.model.base.TaskAlternative;
import com.sesame.wu.model.base.TaskCommon;
import com.sesame.wu.model.extensions.ExtensionsHandle;
import com.sesame.wu.model.task.antStall.AntStall;
import com.sesame.wu.model.task.antStall.AntStallRpcCall;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.MessageUtil;
import com.sesame.wu.util.RandomUtil;
import com.sesame.wu.util.Status;
import com.sesame.wu.util.StringUtil;
import com.sesame.wu.util.TaskBlacklist;
import com.sesame.wu.util.TimeUtil;
import com.sesame.wu.util.idMap.AntSportsTaskListMap;
import com.sesame.wu.util.idMap.AntStallTaskListMap;
import com.sesame.wu.util.idMap.PathThemeMapListMap;
import com.sesame.wu.util.idMap.UserIdMap;

public class AntSports extends ModelTask {

    private static final String TAG = AntSports.class.getSimpleName();

    private BooleanModelField walk;
    private ChoiceModelField PathThemeMapList;
    private BooleanModelField walkMinimumCompleteCount;
    private BooleanModelField receiveCoinAsset;
    private ChoiceModelField donateCharityCoinType;
    private IntegerModelField donateCharityCoinAmount;
    private BooleanModelField coinExchangeDoubleCard;
    private IntegerModelField minExchangeCount;
    private IntegerModelField earliestSyncStepTime;
    private IntegerModelField latestExchangeTime;
    private IntegerModelField syncStepCount;
    private IntegerModelField syncStepMax;
    private IntegerModelField latestSyncStepTime;
    private IntegerModelField syncStepInterval;
    private IntegerModelField syncStepBatches;
    private BooleanModelField syncStepCatchUp;

    /** 上次增加步数的时刻（当日分钟 + 1）；0 表示当天还没增加过。 */
    private static final String FLAG_LAST_SYNC_MINUTE = "sport::lastSyncMinute";

    /**
     * 追赶时单次最多补「几份」。
     *
     * <p>长时间不打开时，按时间进度算出来的欠账可能一次就是一两万步；
     * 即便开了「落后时按进度补齐」，也要有个硬上限兜住，避免出现一次补出天量步数。
     */
    private static final int CATCH_UP_MAX_BATCHES = 4;
    /** 气泡任务自递归上限：服务端回「成功」但状态不前进时原实现会无限加深调用栈 */
    private static final int MAX_SELF_RETRY = 3;
    /** 权益列表翻页上限：服务端恒回 hasMore=true 时原实现会无 sleep 空转 */
    private static final int MAX_BENEFIT_PAGE = 50;
    /** 字符串数字安全解析：字段缺失/非法时返回默认值，避免 Integer.parseInt("") 抛异常打断整轮 */
    private static int parseIntQuietly(String s, int def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
    /** 当日主动推送已确认不可用（新版支付宝移除该接口）：不再重试，避免每次运行都刷日志。 */
    private static final String FLAG_PUSH_UNAVAILABLE = "sport::syncStepPushUnavailable";
    private BooleanModelField tiyubiz;
    private BooleanModelField club;
    private ChoiceModelField clubTrainItemType;
    private ChoiceModelField clubTradeMemberType;
    private SelectModelField clubTradeMemberList;
    private BooleanModelField sportsTasks;
    private BooleanModelField AutoAntSportsTaskList;
    private SelectModelField AntSportsTaskList;
    private BooleanModelField neverLand;

    // 处理签到
    private BooleanModelField QUERY_SIGN;
    // 处理任务中心

    private BooleanModelField QUERY_TASK_CENTER;

    // 处理气泡任务
    private BooleanModelField QUERY_BUBBLE_TASK;

    // 兑换权益
    private BooleanModelField QUERY_ITEM_LIST;

    //能量泵
    private BooleanModelField WALK_GRID;

    private IntegerModelField WALK_GRID_LIMIT;

    private IntegerModelField WALK_GRID_MAX;

    private BooleanModelField MapListSwitch;

    private BooleanModelField awardspecialActivityReceive;

    //private SelectModelField neverLandOptions;
    private SelectModelField neverLandBenefitList;
    private ChoiceModelField energyStrategy;

    @Override
    public String getName() {
        return "运动";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.SPORTS;
    }

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(walk = new BooleanModelField("walk", "行走路线 | 开启", false));
        // modelFields.addField(PathThemeMapList = new SelectModelField("PathThemeMapList", "行走路线 | 路线主题", new LinkedHashSet<>(), WalkPathThemeMapList::getList, "请选择要行走的主题，选择多条则随机走其中一个主题下的路线"));
        // 确保 nickNames 和 values 已初始化
        WalkPathThemeMapList.getList();
        modelFields.addField(PathThemeMapList = new ChoiceModelField("PathThemeMapList", "行走路线 | 路线主题", 0, WalkPathThemeMapList.nickNames));
        modelFields.addField(walkMinimumCompleteCount = new BooleanModelField("walkMinimumCompleteCount", "全主题路线(选最少完成数) | 开启", false));
        //modelFields.addField(walkCustomPathIdList = new SelectModelField("walkCustomPathIdList", "行走路线 | 自定义路线列表", new LinkedHashSet<>(), WalkPath::getThemeListFromRpc, "请选择要行走的路线，选择多条则随机走其中一条"));
        modelFields.addField(sportsTasks = new BooleanModelField("sportsTasks", "运动任务", false));
        modelFields.addField(AutoAntSportsTaskList = new BooleanModelField("AutoAntSportsTaskList", "运动任务 | 自动黑白名单", true));
        modelFields.addField(AntSportsTaskList = new SelectModelField("AntSportsTaskList", "运动任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntSportsTaskList::getList));
        modelFields.addField(receiveCoinAsset = new BooleanModelField("receiveCoinAsset", "收运动币", false));
        //modelFields.addField(donateCharityCoinType = new ChoiceModelField("donateCharityCoinType", "捐运动币 | 方式", DonateCharityCoinType.ZERO, DonateCharityCoinType.nickNames));
        //modelFields.addField(donateCharityCoinAmount = new IntegerModelField("donateCharityCoinAmount", "捐运动币 | 数量" + "(每次)", 100));
        //modelFields.addField(coinExchangeDoubleCard = new BooleanModelField("coinExchangeDoubleCard", "运动币兑换限时能量双击卡", false));
        modelFields.addField(club = new BooleanModelField("club", "抢好友 | 开启", false));
        modelFields.addField(clubTrainItemType = new ChoiceModelField("clubTrainItemType", "抢好友 | 训练动作", TrainItemType.NONE, TrainItemType.nickNames));
        modelFields.addField(clubTradeMemberType = new ChoiceModelField("clubTradeMemberType", "抢好友 | 抢购动作", TradeMemberType.NONE, TradeMemberType.nickNames));
        modelFields.addField(clubTradeMemberList = new SelectModelField("clubTradeMemberList", "抢好友 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(tiyubiz = new BooleanModelField("tiyubiz", "文体中心", false));
        // 配置值是「今日额外增加的步数」，不是要显示到的绝对值：真人自己走的步数会自然叠加。
        // 默认值与旧版一致（22000），保证升级后「可开箱步数」不缩水——
        // 走路功能按每天约 20 次开箱算，每次消耗 500~1100 步，需要 1 万~2.2 万步才够。
        modelFields.addField(syncStepCount = new IntegerModelField("syncStepCount", "步数自动补偿", 22000, 0, 50000));
        syncStepCount.setDescription("在真实步数上自动补足");
        modelFields.addField(syncStepMax = new IntegerModelField("syncStepMax", "同步步数 | 单日上限", 23000, 10000, 100000));
        syncStepMax.setDescription("达到后不再补偿");
        modelFields.addField(earliestSyncStepTime = new IntegerModelField("earliestSyncStepTime", "同步步数 | 最早同步时间(24小时制)", 0, 0, 23));
        modelFields.addField(latestSyncStepTime = new IntegerModelField("latestSyncStepTime", "同步步数 | 最晚同步时间(24小时制)", 23, 0, 23));
        modelFields.addField(syncStepInterval = new IntegerModelField("syncStepInterval", "同步步数 | 累加间隔(分钟)", 60, 10, 180));
        syncStepInterval.setDescription("两次补偿的最小间隔");
        modelFields.addField(syncStepBatches = new IntegerModelField("syncStepBatches", "同步步数 | 分批次数", 12, 1, 60));
        syncStepBatches.setDescription("补偿量分几次补完");
        modelFields.addField(syncStepCatchUp = new BooleanModelField("syncStepCatchUp", "步数补偿 | 落后时按进度补齐", true));
        syncStepCatchUp.setDescription("勾选：打开频率低时按时间进度一次补齐落下的步数；取消：每次固定只补一份");
        modelFields.addField(latestExchangeTime = new IntegerModelField("latestExchangeTime", "行走捐 | 最晚捐步时间(24小时制)", 22, 0, 23));
        modelFields.addField(minExchangeCount = new IntegerModelField("minExchangeCount", "行走捐 | 最小捐步步数", 10));
        modelFields.addField(neverLand = new BooleanModelField("neverLand", "健康岛 | 开启", false));
        modelFields.addField(QUERY_SIGN = new BooleanModelField("QUERY_SIGN", "健康岛 | 每日签到", false));
        modelFields.addField(QUERY_TASK_CENTER = new BooleanModelField("QUERY_TASK_CENTER", "健康岛 | 做任务 加能量", false));
        modelFields.addField(QUERY_BUBBLE_TASK = new BooleanModelField("QUERY_BUBBLE_TASK", "健康岛 | 领取能量球奖励", false));
        modelFields.addField(QUERY_ITEM_LIST = new BooleanModelField("QUERY_ITEM_LIST", "健康岛 | 健康能量兑好礼", false));
        modelFields.addField(WALK_GRID = new BooleanModelField("WALK_GRID", "健康岛 | 能量泵", false));
        modelFields.addField(WALK_GRID_MAX = new IntegerModelField("WALK_GRID_MAX", "健康岛 | 单次执行能量泵最大次数(不限:0)", 5));
        modelFields.addField(WALK_GRID_LIMIT = new IntegerModelField("WALK_GRID_LIMIT", "健康岛 | 使用能量泵剩余能量值(低于该值停止使用)", 10000));
        modelFields.addField(MapListSwitch = new BooleanModelField("MapListSwitch", "健康岛 | 自动切岛", false));
        modelFields.addField(awardspecialActivityReceive = new BooleanModelField("awardspecialActivityReceive", "健康岛 | 领取活动岛奖励", false));
        return modelFields;
    }

    public static final String DISPLAY_NAME = "悦动健康岛";
    public static final ModelGroup MODULE_GROUP = ModelGroup.SPORTS;

    @Override
    public void boot(ClassLoader classLoader) {
        try {
            XHelpers.findAndHookMethod("com.alibaba.health.pedometer.core.datasource.PedometerAgent", classLoader, "readDailyStep", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int originStep = (Integer) param.getResult();
                    int forged = Status.getIntFlagToday("sport::forgedStep");
                    if (forged > 0) {
                        // 叠加口径：显示 = 真实步数 + 今日已伪造累计。
                        // 只加不覆盖，真人自己走的步数不会被抹掉，走了多少照样反映出来
                        param.setResult(originStep + forged);
                    }
                }
            });
            Log.i(TAG, "hook readDailyStep successfully");
        } catch (Throwable t) {
            Log.i(TAG, "hook readDailyStep err:");
            Log.printStackTrace(TAG, t);
        }
    }

    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.other("任务暂停⏸️支付宝运动:当前为仅收能量时间");
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        try {
            syncStep();

            if (walk.getValue()) {
                // 走路预算用「真实步数 + 今日已增加」，不再借「额外增加步数」配置：
                // 这个功能本来就是把步数花在线路图上，按实际步数判断「步数多就多花点」才是原意。
                // 读不到真实步数时传 0，退化成 walkGo 里的默认开箱步数范围，不影响走路本身。
                int realStep = queryRealStepCount();
                walk(realStep < 0 ? 0 : realStep + Status.getIntFlagToday("sport::forgedStep"));
            }

            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initAntSports")) {
                initAntSportsTaskListMap(AutoAntSportsTaskList.getValue(), sportsTasks.getValue());
                Status.flagToday("BlackList::initAntSports");
            }

            //初始化行走主题列表
            if (!Status.hasFlagToday("WalkPathTheme::init")) {
                initWalkPathThemeMap();
                Status.flagToday("WalkPathTheme::init");
            }

            //if (donateCharityCoinType.getValue() != DonateCharityCoinType.ZERO) {
            //    queryProjectList();
            //}

            //if (coinExchangeDoubleCard.getValue()) {
            //    coinExchangeItem("AMS2024032927086104");
           // }

            if (minExchangeCount.getValue() > 0) {
                queryWalkStep();
            }

            if (tiyubiz.getValue()) {
                userTaskGroupQuery("SPORTS_DAILY_SIGN_GROUP");
                userTaskGroupQuery("SPORTS_DAILY_GROUP");
                userTaskRightsReceive();
                pathFeatureQuery();
                //{"error":3000,"errorMessage":"系统出错，正在排查","errorNo":3,"errorTip":"3000"}
                //participate();
            }

            if (club.getValue()) {
                queryClubHome();
            }

            if (sportsTasks.getValue()) {
                sportsTasks();
            }

            if (receiveCoinAsset.getValue()) {
                receiveCoinAsset();
                AntSportsRpcCall.pickAllEnergyBall();
            }

            //执行悦动健康岛
            //if (neverLand.getValue() && checkAuth()) {
            if (neverLand.getValue()) {
                neverlandrun();
            }

        } catch (Throwable t) {
            Log.i(TAG, "start.run err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 分批增加步数。
     *
     * <p>配置值是「今日要额外增加的步数」，不是要显示到的绝对值：真人自己走的步数会自然叠加，
     * 所以显示值 = 真实步数 + 今日已增加累计。分批而不是一次到位，是为了避免
     * 「真实 300 → 瞬间 23000」这种最明显的机器特征。
     *
     * <p>「单日上限」只作<b>还要不要增加</b>的门槛：到了就停，但绝不把本次增加量截断到上限——
     * 一次性从 5000 跳到 23000 才是最容易露馅的形态。
     */
    private void syncStep() {
        int total = syncStepCount.getValue();
        if (total <= 0) {
            return;                           // 未启用补偿，静默
        }
        int startHour = earliestSyncStepTime.getValue();
        int endHour = latestSyncStepTime.getValue();
        int intervalMin = Math.max(1, syncStepInterval.getValue());
        int cap = syncStepMax.getValue();

        // 时间窗口：只在 [最早, 最晚] 之间增加；最晚早于最早时按跨零点窗口处理
        int windowLen = endHour >= startHour ? endHour - startHour : endHour + 24 - startHour;
        if (windowLen <= 0) {
            windowLen = 1;
        }
        String[] hms = Log.getFormatTime().split(":");
        int nowMinutes = Integer.parseInt(hms[0]) * 60 + Integer.parseInt(hms[1]);
        int startMinutes = startHour * 60;
        int elapsed = nowMinutes - startMinutes;
        if (elapsed < 0) {
            elapsed += 24 * 60;
            if (endHour >= startHour) {
                Log.record("步数补偿⏭️未到最早同步时间[" + startHour + "点]");
                return;                       // 不跨零点的窗口，还没到最早时间
            }
        }
        if (elapsed > windowLen * 60) {
            Log.record("步数补偿⏭️已过最晚同步时间[" + endHour + "点]");
            return;                           // 已过最晚时间
        }

        int forged = Status.getIntFlagToday("sport::forgedStep");
        int remain = total - forged;
        if (remain <= 0) {
            Log.record("步数补偿✅今日已补满[" + forged + "步]");
            return;                           // 今日该增加的量已发满
        }

        // 节奏：窗口内任意时刻，只要距上次增加超过「累加间隔」就再加一次；
        // 当天还没增加过则立即增加（否则用户会觉得功能没生效）。
        // 不按窗口均分到固定时刻——那样触发时间是可预测的，反而更像脚本。
        int windowMin = windowLen * 60;
        int batches = Math.max(1, syncStepBatches.getValue());
        int perBatch = (total + batches - 1) / batches;          // 一份多大

        int lastMinute = Status.getIntFlagToday(FLAG_LAST_SYNC_MINUTE);
        if (lastMinute > 0 && nowMinutes - lastMinute < intervalMin) {
            // 这条最频繁，写运行日志即可，避免刷屏结果日志
            Log.i(TAG, "步数补偿⏭️距上次仅" + (nowMinutes - lastMinute) + "分，未到间隔" + intervalMin + "分");
            return;                           // 距上次不足间隔，等下一轮
        }

        int base = queryRealStepCount();
        if (base < 0) {
            Log.record("步数补偿⚠️读取当前步数失败，本轮跳过");
            return;                           // 步数读不到，这轮不冒险
        }
        // 注意：服务端这个值【已经包含】之前累计增加的量，不能再加一次 forged，
        // 否则等于双倍累加、base 会提前撞到上限，只跑一两批就停。
        if (base >= cap) {
            Log.record("步数补偿⏭️已达单日上限[" + base + "/" + cap + "步]");
            return;                           // 已到上限 → 停止增加（注意：不是截断到上限）
        }

        // 本批增加量：至少一份；若落后于「按时间进度应到的累计」就补齐欠账。
        // 进度线是为了保证当天能补足设定值——打开次数不确定，只按一份一份加可能补不满。
        // 正常情况下打开频率够，每次就是一份，不会一次窜到满。
        // 落后时是否按时间进度补齐：关掉就每次只补一份，不会一次窜很多
        int want = perBatch;
        if (Boolean.TRUE.equals(syncStepCatchUp.getValue())) {
            int expected = (int) ((long) total * elapsed / windowMin);
            // 再套一层硬上限，防长时间不打开时一次补出天量
            int maxCatchUp = perBatch * CATCH_UP_MAX_BATCHES;
            want = Math.max(perBatch, Math.min(expected - forged, maxCatchUp));
        }
        int batch = Math.min(remain, want + RandomUtil.nextInt(0, 101));
        int target = base + batch;
        // 主动推送（RpcManager.a）只是「顺便告诉服务端」；真正让步数显示出来的是
        // readDailyStep hook 里的 originStep + forged 叠加。两者必须解耦：
        // 新版支付宝已移除 RpcManager.a，若把记账写在推送成功分支里，异常一抛就永远不记账，
        // forged 恒为 0，hook 也就什么都不加——表现为「步数补偿完全没效果」。
        // 当日已确认该接口不可用就不再试：避免每次运行都重试刷日志
        if (!Status.hasFlagToday(FLAG_PUSH_UNAVAILABLE)) {
            try {
                ClassLoader classLoader = ApplicationHook.getClassLoader();
                Class<?> rpcManagerClass = classLoader.loadClass("com.alibaba.health.pedometer.intergation.rpc.RpcManager");
                Object rpcManager = XHelpers.callStaticMethod(rpcManagerClass, "a");
                if ((Boolean) XHelpers.callMethod(rpcManager, "a", target, Boolean.FALSE, "system")) {
                    Log.i(TAG, "步数补偿主动推送成功[" + target + "步]");
                } else {
                    Log.record("步数补偿⚠️主动推送被拒[" + target + "步]");
                }
            } catch (Throwable t) {
                // XHelpers 把 NoSuchMethodException 包进 RuntimeException，这里统一降级
                if (t.getCause() instanceof NoSuchMethodException) {
                    Log.record("步数补偿⚠️主动推送接口不可用，已跳过");
                    Status.flagToday(FLAG_PUSH_UNAVAILABLE);
                } else {
                    Log.record("步数补偿⚠️主动推送失败[" + target + "步]");
                    Log.printStackTrace(TAG, t);
                }
            }
        }
        // 无论推送结果如何都记账：hook 才是让步数生效的路径
        Status.setIntFlagToday("sport::forgedStep", forged + batch);
        // +1 作哨兵：0 表示当天还没触发过，避免与「零点触发」混淆
        Status.setIntFlagToday(FLAG_LAST_SYNC_MINUTE, nowMinutes + 1);
        Toast.show("同步步数🏃🏻‍♂️[" + target + "步]");
        Log.other("步数补偿🏃🏻‍♂️[+" + batch + "步 → 共" + target + "步][" + (forged + batch) + "/" + total + "]");
    }

    /** 读服务端当前记录的真实步数；失败返回 -1。 */
    private int queryRealStepCount() {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryWalkStep());
            if (MessageUtil.checkResultCode(TAG, jo)) {
                return jo.optInt("stepCount");
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryRealStepCount err:");
            Log.printStackTrace(TAG, t);
        }
        return -1;
    }

    public static void initAntSportsTaskListMap(boolean AutoAntSportsTaskList, boolean sportsTasks) {
        try {
            //初始化AntSportsTaskListMap
            AntSportsTaskListMap.load();
            // 预置黑名单集中登记在 TaskBlacklist
            Set<String> blackList = TaskBlacklist.presetBlackList("AntSports", "AntSportsTaskList");

            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一逛树");
            for (String task : blackList) {
                AntSportsTaskListMap.add(task, task);
            }

            if (sportsTasks) {
                JSONObject jo = new JSONObject(AntSportsRpcCall.queryCoinTaskPanel());
                if (MessageUtil.checkSuccess(TAG, jo)) {
                    jo = jo.optJSONObject("data");
                    if (jo == null) {
                        return;
                    }
                    if (jo.has("taskList")) {
                        JSONArray taskLists = jo.optJSONArray("taskList");
                        if (taskLists == null) {
                            return;
                        }
                        for (int i = 0; i < taskLists.length(); i++) {
                            JSONObject taskList = taskLists.optJSONObject(i);
                            if (taskList == null) {
                                continue;
                            }
                            String taskName = taskList.optString("taskName", "");
                            // 缺任务名的条目无法匹配黑名单，登记进去只会污染配置
                            if (!taskName.isEmpty()) {
                                AntSportsTaskListMap.add(taskName, taskName);
                            }
                        }
                    }
                }

                //保存任务到配置文件
                AntSportsTaskListMap.save();
                Log.record("同步任务🉑运动任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntSportsTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntSports = config.getModelFieldsMap().get("AntSports");
                    SelectModelField AntSportsTaskList = (SelectModelField) AntSports.get("AntSportsTaskList");
                    if (AntSportsTaskList == null) {
                        return;
                    }
                    // 2. 批量添加黑名单任务（确保存在）
                    Set<String> currentValues = AntSportsTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {
                        for (String task : blackList) {
                            if (!currentValues.contains(task)) {
                                AntSportsTaskList.add(task, 0);
                            }
                        }
                    }
                    currentValues = AntSportsTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {

                        MessageUtil.registerTaskWhiteList("AntSportsTaskList", whiteList);
                    }
                    // 4. 保存配置
                    if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                        Log.record("黑白名单🈲运动任务自动设置: " + AntSportsTaskList.getValue());
                    } else {
                        Log.record("运动任务黑白名单设置失败");
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "initSportsTaskListMap err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void initWalkPathThemeMap() {
        //初始化PathThemeMapListMap
        PathThemeMapListMap.load();
        try {
            String result = AntSportsRpcCall.queryThemeList();
            JSONObject jo = new JSONObject(result);
            JSONObject data = jo.optJSONObject("data");
            if (data != null) {
                JSONArray themeList = data.optJSONArray("themeList");
                if (themeList != null) {
                    for (int i = 0; i < themeList.length(); i++) {
                        JSONObject theme = themeList.optJSONObject(i);
                        String themeId = theme.optString("themeId");
                        String themeName = theme.optString("themeName");
                        if (themeId != null && !themeId.isEmpty() && themeName != null && !themeName.isEmpty()) {
                            PathThemeMapListMap.add(themeId, themeName);
                        }
                    }
                }
            }
            PathThemeMapListMap.save();
            Log.record("同步路线主题" + PathThemeMapListMap.getMap());

        } catch (Throwable t) {
            Log.i(TAG, "initWalkPathThemeMap err:");
            Log.printStackTrace(TAG, t);
        }

    }

    // 运动
    private void sportsTasks() {
        try {
            signInCoinTask();
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryCoinTaskPanel());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            jo = jo.optJSONObject("data");
            if (jo == null) {
                return;
            }
            if (!jo.has("taskList")) {
                return;
            }
            JSONArray taskList = jo.optJSONArray("taskList");
            if (taskList == null) {
                return;
            }
            for (int i = 0; i < taskList.length(); i++) {
                jo = taskList.optJSONObject(i);
                if (jo == null) {
                    continue;
                }
                String taskName = jo.optString("taskName", "");
                String taskStatus = jo.optString("taskStatus", "");
                if (TaskStatus.HAS_RECEIVED.name().equals(taskStatus)) {
                    return;
                }

                if (TaskStatus.WAIT_RECEIVE.name().equals(taskStatus)) {
                    String assetId = jo.optString("assetId", "");
                    int prizeAmount = jo.optInt("prizeAmount", 0);
                    String taskId = jo.optString("taskId", "");
                    // 领奖是否成功以任务列表「奖励是否已领到」为准：响应不可信（102 等也可能已发放）
                    // 复核已在 receiveCoinAsset 内部（拉黑之前）完成，这里无需重复
                    if (receiveCoinAsset(assetId, prizeAmount, taskName, taskId)) {
                        TimeUtil.sleep(1000);
                    }
                    continue;
                }
                //黑名单任务跳过
                if (AntSportsTaskList.getValue().contains(taskName)) {
                    continue;
                }
                if (!jo.has("taskAction")) {
                    continue;
                }
                if (TaskStatus.WAIT_COMPLETE.name().equals(taskStatus)) {
                    String taskAction = jo.optString("taskAction", "");
                    String taskId = jo.optString("taskId", "");
                    if (jo.optBoolean("multiTask")) {
                        int currentNum = jo.optInt("currentNum", 0) + 1;
                        int limitConfigNum = jo.optInt("limitConfigNum", 0);
                        taskName = taskName.replaceAll("（.*/.*）", "(" + currentNum + "/" + limitConfigNum + ")");
                    }
                    if (jo.optBoolean("needSignUp") && !signUpTask(taskId)) {
                        continue;
                    }
                    if (completeTask(taskAction, taskId, taskName, jo.optString("sceneCode", ""))) {
                        TimeUtil.sleep(2000);
                    }
                    continue;
                }

                //兜底操作
                String taskAction = jo.optString("taskAction", "");
                String taskId = jo.optString("taskId", "");
                completeTask(taskAction, taskId, taskName, jo.optString("sceneCode", ""));
            }
        } catch (Throwable t) {
            Log.i(TAG, "sportsTasks err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private Boolean signUpTask(String taskId) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.signUpTask(taskId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "signUpTask err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private Boolean completeTask(String taskAction, String taskId, String taskName, String sceneCode) {
        // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
        Outcome outcome = TaskAttemptPolicy.handle("sports::" + taskId, taskName, null,
                () -> attemptCompleteTask(taskAction, taskId, taskName, sceneCode), Log::other,
                new TaskAttemptPolicy.Site("AntSportsTaskList", "运动任务", taskId, sceneCode,
                        (k) -> probeSportsStatus(taskId)));
        return outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
    }

    /** 运动任务完成上报：不写任务黑名单；运动历史上从未出现 400000040，TaskAlternative 属休眠兜底 */
    private Outcome attemptCompleteTask(String taskAction, String taskId, String taskName, String sceneCode) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.completeTask(taskAction, taskId));
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntSportsTaskList", taskName, jo);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.other("运动任务🧾完成[得运动币:" + taskName + "]");
                TimeUtil.sleep(1000);
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 不支持rpc调用（400000040）→ 由 TaskAttemptPolicy 代为伪申报
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.i(TAG, "completeTask err:");
            Log.printStackTrace(TAG, t);
        }
        return Outcome.RETRY;
    }

    /** 列表状态探针：重拉运动币任务面板，按 taskId 匹配该任务当前状态。 */
    private static TaskAttemptPolicy.ProbeResult probeSportsStatus(String taskId) {
        // 运动币的状态词组与通用映射不同，这里把映射显式写在探针里
        return TaskAttemptPolicy.probeByNestedList(TAG, AntSportsRpcCall::queryCoinTaskPanel,
                jo -> MessageUtil.checkSuccess(TAG, jo),
                jo -> {
                    JSONObject data = jo.optJSONObject("data");
                    return data == null ? null : data.optJSONArray("taskList");
                },
                t -> {
                    if (!taskId.equals(t.optString("taskId"))) {
                        return null;
                    }
                    String status = t.optString("taskStatus");
                    if ("WAIT_COMPLETE".equals(status) || "TODO".equals(status)) {
                        return "TODO";
                    }
                    if ("WAIT_RECEIVE".equals(status)) {
                        return "FINISHED";
                    }
                    if ("HAS_RECEIVED".equals(status) || "RECEIVED".equals(status)) {
                        return "RECEIVED";
                    }
                    return "FINISHED";
                });
    }

    /** 列表状态探针：重拉浏览任务列表，按标题匹配；完成后服务端移出，故仍在列表即视为未完成。 */
    private static TaskAttemptPolicy.ProbeResult probeBrowseStatus(String taskName) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryTaskInfo());
            // 原来 data/taskInfos 是链式裸取，缺任何一层都会抛 JSONException 被 catch 成 UNKNOWN，
            // 探针就永远拿不到结论；拆成变量逐层判空
            JSONObject data = jo.optJSONObject("data");
            if (!MessageUtil.checkSuccess(TAG, jo) || data == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONArray taskInfos = data.optJSONArray("taskInfos");
            if (taskInfos == null) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            for (int i = 0; i < taskInfos.length(); i++) {
                JSONObject t = taskInfos.optJSONObject(i);
                if (t == null) {
                    continue;
                }
                if (taskName.equals(t.optString("title"))) {
                    return TaskAttemptPolicy.ProbeResult.TODO;
                }
            }
            // 任务已从列表消失：视为已完成且已领
            return TaskAttemptPolicy.ProbeResult.GONE;
        } catch (Throwable t) {
            Log.error("probeBrowseStatus err:");
            Log.printStackTrace(TAG, t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    /** 运动「做任务得运动币:签到」当日完成标记（服务端回读 signed=true 才落） */
    private static final String FLAG_SIGN_IN_COIN_TASK = "sports::signInCoinTask";

    private void signInCoinTask() {
        if (Status.hasFlagToday(FLAG_SIGN_IN_COIN_TASK)) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.signInCoinTask());

            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data == null) {
                return;
            }
            if (!data.optBoolean("signed", false)) {
                JSONObject subscribeConfig;
                if (data.has("subscribeConfig")) {
                    subscribeConfig = data.optJSONObject("subscribeConfig");
                    if (subscribeConfig == null) {
                        return;
                    }
                    Log.other("运动任务🧾[做任务得运动币:签到" + subscribeConfig.optString("subscribeExpireDays", "") + "天]奖励" + data.optString("toast", "") + "运动币");
                } else {
                    //                        Log.record("没有签到");
                }
            } else {
                // 服务端回读确认「今日已签到」：落当日标记，后续运行不再调签到接口
                Status.flagToday(FLAG_SIGN_IN_COIN_TASK);
                Log.record("运动签到今日已签到");
            }
        } catch (Throwable t) {
            Log.i(TAG, "signInCoinTask err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void receiveCoinAsset() {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryCoinBubbleModule());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data == null) {
                return;
            }
            if (!data.has("recBubbleList")) {
                return;
            }
            JSONArray ja = data.optJSONArray("recBubbleList");
            if (ja == null) {
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.optJSONObject(i);
                if (jo == null) {
                    continue;
                }
                // 原实现判的是外层 data 有没有 assetId：气泡的资产在单条记录里，外层永远没有，
                // 于是整个功能一次都不会发请求。改为逐条判，缺字段只跳过当前气泡
                String assetId = jo.optString("assetId");
                if (assetId.isEmpty()) {
                    continue;
                }
                int coinAmount = jo.optInt("coinAmount", 0);
                String simpleSourceName = jo.optString("simpleSourceName");
                if (receiveCoinAsset(assetId, coinAmount, simpleSourceName, jo.optString("taskId"))) {
                    TimeUtil.sleep(500);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "receiveCoinAsset err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private Boolean receiveCoinAsset(String assetId, int coinAmount, String title, String taskId) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.receiveCoinAsset(assetId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.other("运动中心🧊领取[" + title + "]奖励[" + coinAmount + "运动能量]");
                return true;
            }
            // 领奖收口：先按任务列表复核「已领到」，未确认才交自动拉黑（顺序由 TaskAward 固定）
            TaskAttemptPolicy.StatusProbe probe = (taskId == null || taskId.isEmpty())
                    ? null : k -> probeSportsStatus(taskId);
            if (TaskAward.confirmReceivedOrBlackList("运动中心🧊领取", probe, taskId, title,
                    () -> MessageUtil.checkResultCodeAndMarkTaskBlackList("AntSportsTaskList", title, jo),
                    Log::other)) {
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "receiveCoinAsset err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    /*
     * 新版行走路线 -- begin
     */
    //选择最小路线逻辑
    private String getWalkPathMinCompleteCount() {
        int minCompleteCount = 0;
        String minPathId = null;
        String minThemeName = null;
        String MinName = null;
        String MinCityPathName = null;
        boolean inited = false;
        try {
            String result = AntSportsRpcCall.queryThemeList();
            JSONObject jo = new JSONObject(result);
            JSONObject data = jo.optJSONObject("data");
            if (data != null) {
                //获取线路主题列表
                JSONArray themeList = data.optJSONArray("themeList");
                if (themeList != null) {
                    for (int i = 0; i < themeList.length(); i++) {
                        JSONObject theme = themeList.optJSONObject(i);
                        String themeId = theme.optString("themeId");
                        String themeName = theme.optString("themeName");
                        //Log.other("  " + themeName + "(" + themeId + ")");
                        JSONObject queryWorldMapJo = new JSONObject(AntSportsRpcCall.queryWorldMap(themeId));
                        if (MessageUtil.checkSuccess(TAG, queryWorldMapJo)) {
                            JSONObject queryWorldMapData = queryWorldMapJo.optJSONObject("data");
                            if (queryWorldMapData == null) {
                                continue;
                            }
                            //获取线路城市列表
                            JSONArray cityList = queryWorldMapData.optJSONArray("cityList");
                            if (cityList == null) {
                                continue;
                            }
                            for (int j = 0; j < cityList.length(); j++) {
                                JSONObject city = cityList.optJSONObject(j);
                                if (city == null) {
                                    continue;
                                }
                                String cityId = city.optString("cityId", "");
                                String name;
                                if (city.has("name")) {
                                    name = city.optString("name", "");
                                } else {
                                    name = null;
                                }
                                //Log.other("      " + name + "(" + cityId + ")");
                                if (cityId.equals("000000") || cityId.equals("232700") || cityId.equals("620900") || cityId.equals("653100") || cityId.equals("710100")) {
                                    continue;
                                }
                                JSONObject queryCityPathJo = new JSONObject(AntSportsRpcCall.queryCityPath(cityId));
                                if (MessageUtil.checkSuccess(TAG, queryCityPathJo)) {
                                    JSONObject queryCityPathData = queryCityPathJo.optJSONObject("data");
                                    if (queryCityPathData == null) {
                                        continue;
                                    }
                                    //获取城市包含的路线
                                    JSONArray cityPathList = queryCityPathData.optJSONArray("cityPathList");
                                    if (cityPathList == null) {
                                        continue;
                                    }
                                    for (int k = 0; k < cityPathList.length(); k++) {
                                        JSONObject cityPath = cityPathList.optJSONObject(k);
                                        if (cityPath == null) {
                                            continue;
                                        }
                                        String pathId = cityPath.optString("pathId", "");
                                        String queryCityPathName = cityPath.optString("name", "");
                                        int completeCount = cityPath.optInt("completeCount");
                                        boolean locked = cityPath.optBoolean("locked", true);
                                        if (!inited && !locked) {
                                            minCompleteCount = completeCount;
                                            minThemeName = themeName;
                                            MinName = name;
                                            MinCityPathName = queryCityPathName;
                                            minPathId = pathId;
                                            inited = true;
                                            //Log.other("暂定走第一个主题[" + themeName + "]城市[" + name + "]线路[" + queryCityPathName + "](" + pathId + ")行走" + minCompleteCount + "次");
                                        }
                                        //Log.other("        " + queryCityPathName + "(" + pathId + ")" + completeCount);
                                        if (completeCount < minCompleteCount && !locked) {
                                            minCompleteCount = completeCount;
                                            minThemeName = themeName;
                                            MinName = name;
                                            MinCityPathName = queryCityPathName;
                                            minPathId = pathId;
                                            //Log.other("目前查询到主题[" + themeName + "]城市[" + name + "]线路[" + queryCityPathName + "](" + pathId + ")行走" + minCompleteCount + "次");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Log.other("切换路线🚶🏻‍♂️选择主题[" + minThemeName + "]城市[" + MinName + "]线路[" + MinCityPathName + "](" + minPathId + ")目前" + minCompleteCount + "次");
        } catch (Throwable t) {
            Log.i(TAG, "getWalkPathMinCompleteCount err:");
            Log.printStackTrace(TAG, t);
        }
        return minPathId;
    }

    private void walk(int syncStepCount) {
        String goingPathId = queryGoingPathId();
        do {
            String tempPathId = (String) ExtensionsHandle.handleAlphaRequest("antSports", "walk", null);
            if (tempPathId != null) {
                goingPathId = tempPathId;
            }
            TimeUtil.sleep(1000);
            if (isNeedJoinNewPath(goingPathId)) {
                if (walkMinimumCompleteCount.getValue()) {
                    goingPathId = getWalkPathMinCompleteCount();
                } else {
                    String joinPathId = queryJoinPathId();
                    if (checkJoinPathId(joinPathId)) {
                        if (!joinPath(joinPathId)) {
                            return;
                        }
                        goingPathId = joinPathId;
                    }
                }
            }
        } while (walkGo(queryPath(goingPathId), syncStepCount));
    }

    private Boolean isNeedJoinNewPath(String goingPathId) {
        if (goingPathId.isEmpty()) {
            return true;
        }
        try {
            JSONObject jo = queryPath(goingPathId);
            jo = jo.optJSONObject("userPathStep");
            if (jo == null) {
                return false;
            }
            if (jo.optBoolean("dayLimit")) {
                return true;
            }
            String pathCompleteStatus = jo.optString("pathCompleteStatus", "");
            if (PathCompleteStatus.COMPLETED.name().equals(pathCompleteStatus)) {
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "isNeedJoinNewPath err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private Boolean hasTreasureBox() {
        if (Status.hasFlagToday("sport::treasureBoxLimit")) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryMailList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            JSONArray ja = jo.optJSONArray("userMailList");
            if (ja == null) {
                return false;
            }
            int count = 0;
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.optJSONObject(i);
                if (jo == null) {
                    continue;
                }
                if (!"SPORTSPROD_GOPATH_AWARD_BOX".equals(jo.optString("templateId", ""))) {
                    continue;
                }
                if (!TimeUtil.isToday(jo.optLong("receiveTime", 0L))) {
                    break;
                }
                count++;
            }
            if (count < 20) {
                return true;
            }
            Status.flagToday("sport::treasureBoxLimit");
        } catch (Throwable t) {
            Log.i(TAG, "hasTreasureBox err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private Boolean walkGo(JSONObject pathData, int syncStepCount) {
        //按照每天走路20次收获宝箱奖励得健康能量
        int MIN_STEP_FOR_TREASURE = 500;
        int MAX_STEP_FOR_TREASURE = 1000;
        if (syncStepCount > 20000) {
            int walkcountmax = syncStepCount / 20;
            int walkcountmin = (syncStepCount - 10000) / 20;
            MAX_STEP_FOR_TREASURE = walkcountmax;
            MIN_STEP_FOR_TREASURE = walkcountmin;
        }
        try {
            if (pathData == null || !pathData.has("path")) {
                return false;
            }
            JSONObject path = pathData.optJSONObject("path");
            if (path == null) {
                return false;
            }
            JSONObject userPathStep = pathData.optJSONObject("userPathStep");
            if (userPathStep == null) {
                return false;
            }
            int minGoStepCount = path.optInt("minGoStepCount", 0);
            int pathStepCount = path.optInt("pathStepCount", 0);
            if (path.has("dailyMaxGoStepCount")) {
                pathStepCount = path.optInt("dailyMaxGoStepCount", 0);
            }
            int forwardStepCount = userPathStep.optInt("forwardStepCount", 0);
            int remainStepCount = userPathStep.optInt("remainStepCount", 0);
            boolean dayLimit = userPathStep.optBoolean("dayLimit", false);
            int useStepCount = Math.min(Math.min(remainStepCount, hasTreasureBox() ? RandomUtil.nextInt(MIN_STEP_FOR_TREASURE, MAX_STEP_FOR_TREASURE) : remainStepCount), Math.max(pathStepCount - forwardStepCount % pathStepCount, minGoStepCount));
            if (useStepCount < minGoStepCount || dayLimit) {
                return false;
            }
            String pathId = path.optString("pathId", "");
            String pathName = path.optString("name", "");
            return walkGo(pathName, pathId, useStepCount);
        } catch (Throwable t) {
            Log.i(TAG, "walkGo err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private Boolean walkGo(String pathName, String pathId, int useStepCount) {
        boolean result = false;
        try {
            String date = Log.getFormatDate();
            JSONObject jo = new JSONObject(AntSportsRpcCall.walkGo(date, pathId, useStepCount));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                result = true;
                Log.other("行走路线🚶🏻‍♂️行走[" + pathName + "]#前进了" + useStepCount + "步");
                jo = jo.optJSONObject("data");
                if (jo == null) {
                    return false;
                }
                if (jo.has("completeInfo")) {
                    Log.other("行走路线🚶🏻‍♂️完成[" + pathName + "]");
                }
                parseRewardsByJSONObjectData(jo);
            }
        } catch (Throwable t) {
            Log.i(TAG, "walkGo err:");
            Log.printStackTrace(TAG, t);
        }
        return result;
    }

    private JSONObject queryWorldMap(String themeId) {
        JSONObject theme = null;
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryWorldMap(themeId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                theme = jo.optJSONObject("data");
                if (theme == null) {
                    return null;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryWorldMap err:");
            Log.printStackTrace(TAG, t);
        }
        return theme;
    }

    private JSONObject queryCityPath(String cityId) {
        JSONObject city = null;
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryCityPath(cityId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                city = jo.optJSONObject("data");
                if (city == null) {
                    return null;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryCityPath err:");
            Log.printStackTrace(TAG, t);
        }
        return city;
    }

    private static JSONObject queryPath(String pathId) {
        JSONObject path = null;
        try {
            String date = Log.getFormatDate();
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryPath(date, pathId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                path = jo.optJSONObject("data");
                if (path == null) {
                    return null;
                }
                parseRewardsByJSONObjectData(path);
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryPath err:");
            Log.printStackTrace(TAG, t);
        }
        return path;
    }

    private static void openTreasureBox(JSONArray treasureBoxList) {
        try {
            for (int i = 0; i < treasureBoxList.length(); i++) {
                JSONObject treasureBox = treasureBoxList.optJSONObject(i);
                if (treasureBox == null) {
                    continue;
                }
                receiveEvent(treasureBox.optString("boxNo", ""));
                TimeUtil.sleep(1000);
            }
        } catch (Throwable t) {
            Log.i(TAG, "openTreasureBox err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void receiveEvent(String eventBillNo) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.receiveEvent(eventBillNo));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                // 服务端成功但缺 data/rewards 时，裸 get 会抛 JSONException 被外层吞掉，
                // 表现为「领了事件却没有任何奖励日志」；改为逐层 opt 判空，缺字段直接返回
                JSONObject data = jo.optJSONObject("data");
                if (data == null) {
                    Log.i(TAG, "receiveEvent 跳过[" + eventBillNo + "]#缺少字段 data");
                    return;
                }
                JSONArray rewards = data.optJSONArray("rewards");
                if (rewards == null) {
                    Log.i(TAG, "receiveEvent 跳过[" + eventBillNo + "]#缺少字段 rewards");
                    return;
                }
                parseRewardsByJSONArrayRewards(rewards, 0);
            }
        } catch (Throwable t) {
            Log.i(TAG, "receiveEvent err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void parseRewardsByJSONArrayRewards(JSONArray rewards, int rewardsType) {
        String rewardsTypeName;
        switch (rewardsType) {
            case 0:
                rewardsTypeName = "宝箱奖励";
                break;
            case 1:
                rewardsTypeName = "中奖奖励";
                break;
            case 2:
                rewardsTypeName = "终点奖励";
                break;
            default:
                rewardsTypeName = "未知奖励";
                break;
        }
        try {
            for (int i = 0; i < rewards.length(); i++) {
                JSONObject jo = rewards.optJSONObject(i);
                if (jo == null) {
                    continue;
                }
                if (jo.has("rewardStatus") && !"SUCCESS".equals(jo.optString("rewardStatus", ""))) {
                    // rewardStatus : SUCCESS NOT_HIT
                    continue;
                }
                Log.other("行走路线🚶🏻‍♂️收获" + rewardsTypeName + "[" + jo.optString("rewardName", "") + "*" + jo.optInt("count", 0) + "]");
            }
        } catch (Throwable t) {
            Log.i(TAG, "parseRewardsByJSONArrayRewards err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void parseRewardsByJSONObjectData(JSONObject data) {
        try {
            JSONArray treasureBoxList = data.optJSONArray("treasureBoxList");
            if (treasureBoxList == null) {
                return;
            }
            openTreasureBox(treasureBoxList);
            if (data.has("brandRewardVOs")) {
                JSONArray brandRewardVOs = data.optJSONArray("brandRewardVOs");
                if (brandRewardVOs == null) {
                    return;
                }
                parseRewardsByJSONArrayRewards(brandRewardVOs, 1);
            }
            if (data.has("completeInfo")) {
                data = data.optJSONObject("completeInfo");
                if (data == null) {
                    return;
                }
                JSONArray completeRewards = data.optJSONArray("completeRewards");
                if (completeRewards == null) {
                    return;
                }
                parseRewardsByJSONArrayRewards(completeRewards, 2);
            }
        } catch (Throwable t) {
            Log.i(TAG, "parseRewardsByJSONObjectData err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private String queryGoingPathId() {
        String goingPathId = "";
        try {
            String date = Log.getFormatDate();
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryPath(date, ""));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                jo = jo.optJSONObject("data");
                if (jo == null) {
                    return "";
                }
                goingPathId = jo.optString("goingPathId");
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryGoingPathId err:");
            Log.printStackTrace(TAG, t);
        }
        return goingPathId;
    }

    private String queryJoinPathId() {
        String pathId = null;

        try {
            int index = PathThemeMapList.getValue();
            if (index < 0 || index >= WalkPathThemeMapList.values.length) {
                return pathId;
            }
            String themeId = WalkPathThemeMapList.values[index];
            JSONObject theme = queryWorldMap(themeId);
            if (theme == null) {
                return pathId;
            }
            JSONArray cityList = theme.optJSONArray("cityList");
            if (cityList == null) {
                return "";
            }
            for (int i = 0; i < cityList.length(); i++) {
                JSONObject cityNode = cityList.optJSONObject(i);
                if (cityNode == null) {
                    continue;
                }
                String cityId = cityNode.optString("cityId", "");
                if (cityId.equals("000000") || cityId.equals("232700") || cityId.equals("620900") || cityId.equals("653100") || cityId.equals("710100")) {
                    continue;
                }
                JSONObject city = queryCityPath(cityId);
                if (city == null) {
                    continue;
                }
                JSONArray cityPathList = city.optJSONArray("cityPathList");
                if (cityPathList == null) {
                    continue;
                }
                for (int j = 0; j < cityPathList.length(); j++) {
                    JSONObject cityPath = cityPathList.optJSONObject(j);
                    if (cityPath == null) {
                        continue;
                    }
                    pathId = cityPath.optString("pathId", "");
                    String pathCompleteStatus = cityPath.optString("pathCompleteStatus");
                    if (!PathCompleteStatus.COMPLETED.name().equals(pathCompleteStatus)) {
                        return pathId;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryJoinPathId err:");
            Log.printStackTrace(TAG, t);
        }
        return pathId;
    }

    public static Boolean checkJoinPathId(String joinPathId) {
        try {
            JSONObject jo = queryPath(joinPathId);
            String goingPathId = jo.optString("goingPathId");
            if (Objects.equals(goingPathId, joinPathId)) {
                return false;
            }
            jo = jo.optJSONObject("userPathStep");
            if (jo == null) {
                return false;
            }
            return !jo.optBoolean("dayLimit");
        } catch (Throwable t) {
            Log.i(TAG, "checkJoinPathId err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static Boolean joinPath(String pathId) {
        if (pathId == null) {
            // 守护体育梦
            pathId = "p000202408231708";
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.joinPath(pathId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                // queryPath 解析失败会返回 null；path 缺失时这里原本 NPE，日志里什么都看不到
                JSONObject pathData = queryPath(pathId);
                JSONObject pathObj = pathData == null ? null : pathData.optJSONObject("path");
                if (pathObj == null) {
                    Log.i(TAG, "行走路线 跳过[" + pathId + "]#缺少字段 path");
                    return false;
                }
                String pathName = pathObj.optString("name", "");
                Log.other("行走路线🚶🏻‍♂️加入[" + pathName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "joinPath err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    /*
     * 新版行走路线 -- end
     */
    private Boolean canDonateCharityCoinToday() {
        if (Status.hasFlagToday("sport::donateCharityCoin")) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryDonateRecord());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            JSONArray footballFieldLongModel = jo.optJSONArray("footballFieldLongModel");
            if (footballFieldLongModel == null) {
                return false;
            }
            if (footballFieldLongModel.length() == 0) {
                return true;
            }
            jo = footballFieldLongModel.optJSONObject(0);
            if (jo == null) {
                return false;
            }
            jo = jo.optJSONObject("personStatModel");
            if (jo == null) {
                return false;
            }
            long lastDonationTime = jo.optLong("lastDonationTime", 0L);
            if (TimeUtil.isLessThanNowOfDays(lastDonationTime)) {
                return true;
            }
            Status.flagToday("sport::donateCharityCoin");
        } catch (Throwable t) {
            Log.i(TAG, "canDonateCharityCoinToday err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private void queryProjectList() {
        if (!canDonateCharityCoinToday()) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryProjectList(0));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            int charityCoinCount = jo.optInt("charityCoinCount", 0);
            int donateCharityCoin = donateCharityCoinAmount.getValue();
            if (charityCoinCount < donateCharityCoin) {
                return;
            }
            // 原来 projectPage.data.basicModel 三层链式裸取，任一层缺失整轮公益项目都跳过
            JSONObject projectPage = jo.optJSONObject("projectPage");
            JSONArray ja = projectPage == null ? null : projectPage.optJSONArray("data");
            if (ja == null) {
                Log.i(TAG, "公益捐赠 跳过#缺少字段 projectPage.data");
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                JSONObject project = ja.optJSONObject(i);
                jo = project == null ? null : project.optJSONObject("basicModel");
                if (jo == null) {
                    continue;
                }
                if (jo.optInt("acwProjectStatus") == 0) {
                    // acwProjectStatus: 0 1
                    continue;
                }
                // footballFieldStatus: OPENING_DONATE DONATE_COMPLETED
                if ("DONATE_COMPLETED".equals(jo.optString("footballFieldStatus", ""))) {
                    break;
                }
                if (donate(donateCharityCoin, jo.optString("projectId", ""), jo.optString("title", ""))) {
                    charityCoinCount -= donateCharityCoin;
                    if (donateCharityCoinType.getValue() != DonateCharityCoinType.ALL) {
                        break;
                    }
                    if (charityCoinCount < donateCharityCoin) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryProjectList err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private Boolean donate(int donateCharityCoin, String projectId, String title) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.donate(donateCharityCoin, projectId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.other("公益捐赠❤️[捐赠运动币:" + title + "]捐赠" + donateCharityCoin + "运动币");

                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "donate err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private Boolean canDonateWalkExchangeToday() {
        if (Status.hasFlagToday("sport::donateWalk")) {
            return false;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.donateExchangeRecord());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            // 服务端在「从未兑换过」时不返回该字段，getJSONArray 会直接抛异常，
            // 被下面的 catch 吞掉 → 这里永远 return false → 捐赠功能静默失效
            JSONArray userExchangeRecords = jo.optJSONArray("userExchangeRecords");
            if (userExchangeRecords == null || userExchangeRecords.length() == 0) {
                return true;
            }
            jo = userExchangeRecords.optJSONObject(0);
            if (jo == null) {
                return true;
            }
            long gmtCreate = jo.optLong("gmtCreate", 0L);
            if (TimeUtil.isLessThanNowOfDays(gmtCreate)) {
                return true;
            }
            Status.flagToday("sport::donateWalk");
        } catch (Throwable t) {
            Log.i(TAG, "canDonateWalkExchangeToday err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private void queryWalkStep() {
        if (!canDonateWalkExchangeToday()) {
            return;
        }
        if (Status.hasFlagToday("sport::donateWalk")) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryWalkStep());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            //jo = jo.getJSONObject("dailyStepModel");
            //long stepLastTime = jo.getLong("stepLastTime");
            int hour = Integer.parseInt(Log.getFormatTime().split(":")[0]);

            int stepCount = jo.optInt("stepCount");
            if (stepCount < minExchangeCount.getValue() && hour < latestExchangeTime.getValue()) {
                return;
            }
            AntSportsRpcCall.walkDonateSignInfo(stepCount);
            jo = new JSONObject(AntSportsRpcCall.donateWalkHome(stepCount));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject walkDonateHomeModel = jo.optJSONObject("walkDonateHomeModel");
            if (walkDonateHomeModel == null) {
                return;
            }
            JSONObject walkUserInfoModel = walkDonateHomeModel.optJSONObject("walkUserInfoModel");
            if (walkUserInfoModel == null) {
                return;
            }
            if (!walkUserInfoModel.has("exchangeFlag")) {
                return;
            }

            String donateToken = walkDonateHomeModel.optString("donateToken", "");
            JSONObject walkCharityActivityModel = walkDonateHomeModel.optJSONObject("walkCharityActivityModel");
            if (walkCharityActivityModel == null) {
                return;
            }
            String activityId = walkCharityActivityModel.optString("activityId", "");

            jo = new JSONObject(AntSportsRpcCall.donateWalkExchange(activityId, stepCount, donateToken));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject donateExchangeResultModel = jo.optJSONObject("donateExchangeResultModel");
            if (donateExchangeResultModel == null) {
                return;
            }
            int userCount = donateExchangeResultModel.optInt("userCount", 0);
            // userAmount 缺失只影响「兑换了多少元」这条日志，不能因此中断打卡
            JSONObject userAmount = donateExchangeResultModel.optJSONObject("userAmount");
            double amount = userAmount == null ? 0d : userAmount.optDouble("amount", 0d);
            String donateTitle = donateExchangeResultModel.optString("donateTitle", "");
            Log.other("公益捐赠❤️[捐步做公益:" + donateTitle + "]捐赠" + userCount + "步,兑换" + amount + "元公益金");
            Status.flagToday("sport::donateWalk");

        } catch (Throwable t) {
            Log.i(TAG, "queryWalkStep err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /* 文体中心 */
    // SPORTS_DAILY_SIGN_GROUP SPORTS_DAILY_GROUP
    private void userTaskGroupQuery(String groupId) {
        try {
            String s = AntSportsRpcCall.userTaskGroupQuery(groupId);
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                // 服务端在无任务组/无任务时不下发 group、userTaskList，裸 get 会整轮抛异常；
                // 改为逐层 opt 判空，缺字段直接结束本次任务组处理
                JSONObject group = jo.optJSONObject("group");
                if (group == null) {
                    Log.i(TAG, "文体中心 跳过[" + groupId + "]#缺少字段 group");
                    return;
                }
                JSONArray userTaskList = group.optJSONArray("userTaskList");
                if (userTaskList == null) {
                    Log.i(TAG, "文体中心 跳过[" + groupId + "]#缺少字段 userTaskList");
                    return;
                }
                for (int i = 0; i < userTaskList.length(); i++) {
                    jo = userTaskList.optJSONObject(i);
                    if (jo == null) {
                        continue;
                    }
                    if (!"TODO".equals(jo.optString("status", ""))) {
                        continue;
                    }
                    JSONObject taskInfo = jo.optJSONObject("taskInfo");
                    if (taskInfo == null) {
                        continue;
                    }
                    String bizType = taskInfo.optString("bizType", "");
                    String taskId = taskInfo.optString("taskId", "");
                    // 缺 bizType/taskId 时提交空参数只会被服务端判失败，直接跳过该任务
                    if (bizType.isEmpty() || taskId.isEmpty()) {
                        Log.i(TAG, "文体中心 跳过[" + taskInfo.optString("taskName", "") + "]#缺少字段 bizType/taskId");
                        continue;
                    }
                    jo = new JSONObject(AntSportsRpcCall.userTaskComplete(bizType, taskId));
                    if (jo.optBoolean("success")) {
                        String taskName = taskInfo.optString("taskName", taskId);
                        Log.other("文体中心🧾完成任务[" + taskName + "]");
                    } else {
                        Log.record("文体每日任务" + " " + jo);
                    }
                }
            } else {
                Log.record("文体每日任务" + " " + s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "userTaskGroupQuery err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void participate() {
        try {
            String s = AntSportsRpcCall.queryAccount();
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                double balance = jo.optDouble("balance", 0d);
                if (balance < 100) {
                    return;
                }
                jo = new JSONObject(AntSportsRpcCall.queryRoundList());
                if (jo.optBoolean("success")) {
                    JSONArray dataList = jo.optJSONArray("dataList");
                    if (dataList == null) {
                        Log.i(TAG, "走路挑战 跳过#缺少字段 dataList");
                        return;
                    }
                    for (int i = 0; i < dataList.length(); i++) {
                        jo = dataList.optJSONObject(i);
                        if (jo == null) {
                            continue;
                        }
                        if (!"P".equals(jo.optString("status", ""))) {
                            continue;
                        }
                        if (jo.has("userRecord")) {
                            continue;
                        }
                        // 缺 instanceList 的单条记录只跳过它自己，不能让整轮挑战赛解析失败
                        JSONArray instanceList = jo.optJSONArray("instanceList");
                        if (instanceList == null) {
                            continue;
                        }
                        int pointOptions = 0;
                        String roundId = jo.optString("id", "");
                        String InstanceId = null;
                        String ResultId = null;
                        for (int j = instanceList.length() - 1; j >= 0; j--) {
                            jo = instanceList.optJSONObject(j);
                            if (jo == null) {
                                continue;
                            }
                            if (jo.optInt("pointOptions", 0) < pointOptions) {
                                continue;
                            }
                            pointOptions = jo.optInt("pointOptions", 0);
                            InstanceId = jo.optString("id", "");
                            ResultId = jo.optString("instanceResultId", "");
                        }
                        jo = new JSONObject(AntSportsRpcCall.participate(pointOptions, InstanceId, ResultId, roundId));
                        if (jo.optBoolean("success")) {
                            JSONObject data = jo.optJSONObject("data");
                            if (data == null) {
                                continue;
                            }
                            String roundDescription = data.optString("roundDescription", "");
                            int targetStepCount = jo.optInt("targetStepCount", 0);
                            Log.other("走路挑战🚶🏻‍♂️[" + roundDescription + "]#" + targetStepCount);
                        } else {
                            Log.record("走路挑战赛" + " " + jo);
                        }
                    }
                } else {
                    Log.record("queryRoundList" + " " + jo);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "participate err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void userTaskRightsReceive() {
        try {
            String s = AntSportsRpcCall.userTaskGroupQuery("SPORTS_DAILY_GROUP");
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                // 同文体每日任务：group/userTaskList 缺失时不要整轮抛异常
                JSONObject group = jo.optJSONObject("group");
                if (group == null) {
                    Log.i(TAG, "文体中心领奖 跳过#缺少字段 group");
                    return;
                }
                JSONArray userTaskList = group.optJSONArray("userTaskList");
                if (userTaskList == null) {
                    Log.i(TAG, "文体中心领奖 跳过#缺少字段 userTaskList");
                    return;
                }
                for (int i = 0; i < userTaskList.length(); i++) {
                    jo = userTaskList.optJSONObject(i);
                    if (jo == null) {
                        continue;
                    }
                    if (!"COMPLETED".equals(jo.optString("status", ""))) {
                        continue;
                    }
                    String userTaskId = jo.optString("userTaskId", "");
                    JSONObject taskInfo = jo.optJSONObject("taskInfo");
                    if (taskInfo == null) {
                        continue;
                    }
                    String taskId = taskInfo.optString("taskId", "");
                    if (userTaskId.isEmpty() || taskId.isEmpty()) {
                        Log.i(TAG, "文体中心领奖 跳过[" + taskInfo.optString("taskName", "") + "]#缺少字段 taskId/userTaskId");
                        continue;
                    }
                    jo = new JSONObject(AntSportsRpcCall.userTaskRightsReceive(taskId, userTaskId));
                    if (jo.optBoolean("success")) {
                        String taskName = taskInfo.optString("taskName", taskId);
                        // 奖励明细缺失不影响「奖励已领」，只是日志少一段，不能因此抛异常
                        JSONArray rightsRuleList = taskInfo.optJSONArray("rightsRuleList");
                        StringBuilder award = new StringBuilder();
                        if (rightsRuleList != null) {
                            for (int j = 0; j < rightsRuleList.length(); j++) {
                                JSONObject rule = rightsRuleList.optJSONObject(j);
                                if (rule == null) {
                                    continue;
                                }
                                award.append(rule.optString("rightsName", "")).append("*").append(rule.optInt("baseAwardCount", 0));
                            }
                        }
                        Log.other("领取奖励🎖️[" + taskName + "]#" + award);
                    } else {
                        Log.record("文体中心领取奖励");
                        Log.i(jo.toString());
                    }
                }
            } else {
                Log.record("文体中心领取奖励");
                Log.i(s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "userTaskRightsReceive err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void pathFeatureQuery() {
        try {
            String s = AntSportsRpcCall.pathFeatureQuery();
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                // 活动未开/无路线时不下发 path，裸 get 会让整个路线任务静默失败
                JSONObject path = jo.optJSONObject("path");
                if (path == null) {
                    Log.i(TAG, "行走路线 跳过#缺少字段 path");
                    return;
                }
                String pathId = path.optString("pathId", "");
                String title = path.optString("title", "");
                int minGoStepCount = path.optInt("minGoStepCount", 0);
                if (jo.has("userPath")) {
                    JSONObject userPath = jo.optJSONObject("userPath");
                    if (userPath == null) {
                        return;
                    }
                    String userPathRecordStatus = userPath.optString("userPathRecordStatus", "");
                    if ("COMPLETED".equals(userPathRecordStatus)) {
                        pathMapHomepage(pathId);
                        pathMapJoin(title, pathId);
                    } else if ("GOING".equals(userPathRecordStatus)) {
                        pathMapHomepage(pathId);
                        String countDate = Log.getFormatDate();
                        jo = new JSONObject(AntSportsRpcCall.stepQuery(countDate, pathId));
                        if (jo.optBoolean("success")) {
                            int canGoStepCount = jo.optInt("canGoStepCount", 0);
                            if (canGoStepCount >= minGoStepCount) {
                                String userPathRecordId = userPath.optString("userPathRecordId", "");
                                tiyubizGo(countDate, title, canGoStepCount, pathId, userPathRecordId);
                            }
                        }
                    }
                } else {
                    pathMapJoin(title, pathId);
                }
            } else {
                Log.i(TAG, jo.optString("resultDesc", ""));
            }
        } catch (Throwable t) {
            Log.i(TAG, "pathFeatureQuery err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void pathMapHomepage(String pathId) {
        try {
            String s = AntSportsRpcCall.pathMapHomepage(pathId);
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                JSONArray userPathGoRewardList = jo.optJSONArray("userPathGoRewardList");
                if (userPathGoRewardList == null) {
                    return;
                }
                for (int i = 0; i < userPathGoRewardList.length(); i++) {
                    jo = userPathGoRewardList.optJSONObject(i);
                    if (jo == null) {
                        continue;
                    }
                    if (!"UNRECEIVED".equals(jo.optString("status", ""))) {
                        continue;
                    }
                    String userPathRewardId = jo.optString("userPathRewardId", "");
                    jo = new JSONObject(AntSportsRpcCall.rewardReceive(pathId, userPathRewardId));
                    if (jo.optBoolean("success")) {
                        // 开箱成功但明细缺字段：只丢日志，不影响已领取状态
                        JSONObject rewardDetail = jo.optJSONObject("userPathRewardDetail");
                        JSONArray rightsRuleList = rewardDetail == null ? null : rewardDetail.optJSONArray("userPathRewardRightsList");
                        StringBuilder award = new StringBuilder();
                        if (rightsRuleList != null) {
                            for (int j = 0; j < rightsRuleList.length(); j++) {
                                JSONObject rule = rightsRuleList.optJSONObject(j);
                                JSONObject rightsContent = rule == null ? null : rule.optJSONObject("rightsContent");
                                if (rightsContent == null) {
                                    continue;
                                }
                                award.append(rightsContent.optString("name", "")).append("*").append(rightsContent.optInt("count", 0));
                            }
                        }
                        Log.other("文体宝箱🎁[" + award + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                    } else {
                        Log.record("文体中心开宝箱");
                        Log.i(jo.toString());
                    }
                }
            } else {
                Log.record("文体中心开宝箱");
                Log.i(s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "pathMapHomepage err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void pathMapJoin(String title, String pathId) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.pathMapJoin(pathId));
            if (jo.optBoolean("success")) {
                Log.other("加入线路🚶🏻‍♂️[" + title + "]");
                pathFeatureQuery();
            } else {
                Log.i(TAG, jo.toString());
            }
        } catch (Throwable t) {
            Log.i(TAG, "pathMapJoin err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void tiyubizGo(String countDate, String title, int goStepCount, String pathId, String userPathRecordId) {
        try {
            String s = AntSportsRpcCall.tiyubizGo(countDate, goStepCount, pathId, userPathRecordId);
            JSONObject jo = new JSONObject(s);
            if (jo.optBoolean("success")) {
                jo = jo.optJSONObject("userPath");
                if (jo == null) {
                    return;
                }
                Log.other("行走线路🚶🏻‍♂️[" + title + "]#前进了" + jo.optInt("userPathRecordForwardStepCount", 0) + "步");
                pathMapHomepage(pathId);
                boolean completed = "COMPLETED".equals(jo.optString("userPathRecordStatus", ""));
                if (completed) {
                    Log.other("完成线路🚶🏻‍♂️[" + title + "]");
                    pathFeatureQuery();
                }
            } else {
                Log.i(TAG, s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "tiyubizGo err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // 抢好友大战
    // 俱乐部首页，执行抢好友、训练动作、抢购等操作（具体逻辑依赖配置的 clubTrainItemType、clubTradeMemberType 等）
    private void queryClubHome() {
        try {
            // 收运动能量
            JSONObject joBubble = new JSONObject(AntSportsRpcCall.queryClubHome());
            if (!MessageUtil.checkResultCode(TAG, joBubble)) {
                return;
            }
            JSONObject mainRoom = joBubble.optJSONObject("mainRoom");
            if (mainRoom == null) {
                return;
            }
            if (mainRoom.has("bubbleList")) {
                JSONArray bubbleList = mainRoom.optJSONArray("bubbleList");
                if (bubbleList == null) {
                    return;
                }
                for (int k = 0; k < bubbleList.length(); k++) {
                    JSONObject bubble = bubbleList.optJSONObject(k);
                    if (bubble == null) {
                        continue;
                    }
                    String bubbleId = bubble.optString("bubbleId", "");
                    collectBubble(bubbleId, "[买卖]");
                    TimeUtil.sleep(200);
                }
            }

            JSONObject jo = new JSONObject(AntSportsRpcCall.queryClubHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray roomList = jo.optJSONArray("roomList");
            if (roomList == null) {
                return;
            }
            for (int i = 0; i < roomList.length(); i++) {
                // 检查可以购买好友的房号i
                JSONObject room = roomList.optJSONObject(i);
                if (room == null) {
                    continue;
                }
                String roomId = room.optString("roomId", "");

                // 收取训练好友能量
                if (room.has("bubbleList")) {
                    JSONArray roombubbleList = room.optJSONArray("bubbleList");
                    if (roombubbleList == null) {
                        continue;
                    }
                    for (int l = 0; l < roombubbleList.length(); l++) {
                        JSONObject bubble = roombubbleList.optJSONObject(l);
                        if (bubble == null) {
                            continue;
                        }
                        String bubbleId = bubble.optString("bubbleId", "");
                        // 收取第i号房间需要收取训练好友的第l个能量球
                        collectBubble(bubbleId, "[训练]");
                        TimeUtil.sleep(200);
                    }
                }

                // 原来直接取 memberList.length()，字段缺失会抛异常把整轮俱乐部任务中断
                JSONArray memberList = room.optJSONArray("memberList");
                if (memberList == null || memberList.length() != 0) {
                    continue;
                }

                // 购买好友
                if (clubTradeMemberType.getValue() != TradeMemberType.NONE) {
                    queryMemberPriceRanking(roomId);
                    TimeUtil.sleep(200);
                }
            }
            TimeUtil.sleep(200);

            // 训练好友
            JSONObject joTrain = new JSONObject(AntSportsRpcCall.queryClubHome());
            if (!MessageUtil.checkResultCode(TAG, joTrain)) {
                return;
            }
            JSONArray roomListTrain = joTrain.optJSONArray("roomList");
            if (roomListTrain == null) {
                return;
            }
            for (int j = 0; j < roomListTrain.length(); j++) {
                JSONObject roomTrain = roomListTrain.optJSONObject(j);
                if (roomTrain == null) {
                    continue;
                }
                // 同上：memberList 缺失时跳过该房间，而不是整轮抛异常
                JSONArray memberList = roomTrain.optJSONArray("memberList");
                JSONObject member = memberList == null || memberList.length() == 0 ? null : memberList.optJSONObject(0);
                if (member != null) {
                    trainMember(member);
                    TimeUtil.sleep(1000);
                }
            }

            //蹲点训练好友
            JSONObject autoTrain = new JSONObject(AntSportsRpcCall.queryClubHome());
            if (!MessageUtil.checkResultCode(TAG, autoTrain)) {
                return;
            }
            roomListTrain = autoTrain.optJSONArray("roomList");
            if (roomListTrain == null) {
                return;
            }
            for (int j = 0; j < roomListTrain.length(); j++) {
                JSONObject roomTrain = roomListTrain.optJSONObject(j);
                if (roomTrain == null) {
                    continue;
                }
                String roomId = roomTrain.optString("roomId", "");
                JSONArray memberList = roomTrain.optJSONArray("memberList");
                JSONObject member = memberList == null || memberList.length() == 0 ? null : memberList.optJSONObject(0);
                if (member != null) {
                    JSONObject trainInfo = member.optJSONObject("trainInfo");
                    if (trainInfo == null) {
                        continue;
                    }
                    if (trainInfo.has("gmtEnd")) {
                        Long gmtEnd = trainInfo.optLong("gmtEnd", 0L);
                        long updateTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
                        addChildTask(new ChildModelTask(roomId, "", () -> {
                            autoTrainMember(roomId, gmtEnd);
                        }, updateTime));
                    }
                    TimeUtil.sleep(200);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryClubHome err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // 抢好友大战-收集运动能量
    private void collectBubble(String bubbleId, String bubbleType) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.collectBubble(bubbleId));
            if (jo.optBoolean("success")) {
                JSONObject ja = jo.optJSONObject("data");
                if (ja == null) {
                    return;
                }
                String collectCoin = ja.optString("changeAmount", "");
                Log.other("好友大战🧊收取" + bubbleType + "获得[" + collectCoin + "运动能量]" + "#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
            }
        } catch (Throwable t) {
            Log.i(TAG, "collectBubble err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // 抢好友大战-训练好友
    private void trainMember(JSONObject member) {
        try {
            String memberId = member.optString("memberId", "");
            String originBossId = member.optString("originBossId", "");
            JSONObject trainInfo = member.optJSONObject("trainInfo");
            if (trainInfo == null) {
                return;
            }

            String userName = UserIdMap.getShowName(originBossId);
            if (!trainInfo.optBoolean("training", false)) {
                String itemType = TrainItemType.itemTypes[clubTrainItemType.getValue()];
                if (StringUtil.isEmpty(itemType)) {
                    return;
                }

                String name = TrainItemType.nickNames[clubTrainItemType.getValue()];
                JSONObject queryTrainItemjo = new JSONObject(AntSportsRpcCall.queryTrainItem());
                if (!MessageUtil.checkResultCode(TAG, queryTrainItemjo)) {
                    return;
                }

                // 可以翻倍训练
                if (queryTrainItemjo.has("bizId")) {
                    String bizId = queryTrainItemjo.optString("bizId", "");
                    String taskAction = "SHOW_AD";
                    queryTrainItemjo = queryTrainItemjo.optJSONObject("taskDetail");
                    if (queryTrainItemjo == null) {
                        return;
                    }
                    String taskId = queryTrainItemjo.optString("taskId", "");
                    JSONObject jo = new JSONObject(AntSportsRpcCall.DoubletrainMember(itemType, bizId, memberId, originBossId));
                    Log.other("好友大战💪训练[" + userName + "]" + name + "[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    TimeUtil.sleep(7000);
                    jo = new JSONObject(AntSportsRpcCall.duublecompleteTask(bizId, taskAction, taskId));
                    if (!MessageUtil.checkSuccess(TAG, jo)) {
                        return;
                    }
                    Log.other("好友大战💪翻倍训练[" + userName + "]" + name + "[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                } else {
                    JSONObject jo = new JSONObject(AntSportsRpcCall.trainMember(itemType, memberId, originBossId));
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    Log.other("好友大战💪训练[" + userName + "]" + name + "[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                }
            }

        } catch (Throwable t) {
            Log.i(TAG, "trainMember err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // 抢好友大战-蹲点训练
    private void autoTrainMember(String roomId, Long gmtEnd) {
        String taskId = "TRAIN|" + roomId;
        if (!hasChildTask(taskId)) {
            addChildTask(new ChildModelTask(taskId, "TRAIN", () -> {
                AntSportsRpcCall.queryClubRoom(roomId);
            }, gmtEnd));
            int roomIdInt = Integer.parseInt(roomId.substring(2, 8));
            Log.record("蹲点训练💪添加[" + roomIdInt + "号房]在[" + TimeUtil.getCommonDate(gmtEnd) + "]执行");
        }
    }

    // 抢好友大战-抢购好友
    private void queryMemberPriceRanking(String roomId) {
        int energyBalance;
        try {
            JSONObject jo1 = new JSONObject(AntSportsRpcCall.queryClubHome());
            if (!MessageUtil.checkResultCode(TAG, jo1)) {
                return;
            }
            JSONObject assetsInfo = jo1.optJSONObject("assetsInfo");
            if (assetsInfo == null) {
                return;
            }
            energyBalance = assetsInfo.optInt("energyBalance", 0);
            TimeUtil.sleep(200);
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryMemberPriceRankingEnergy(energyBalance));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            energyBalance = jo.optInt("energyBalance", 0);
            jo = jo.optJSONObject("rank");
            if (jo == null) {
                return;
            }
            JSONArray ja = jo.optJSONArray("data");
            if (ja == null) {
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.optJSONObject(i);
                if (jo == null) {
                    continue;
                }
                int price = jo.optInt("price", 0);
                if (price > energyBalance) {
                    continue;
                }
                String originBossId = jo.optString("originBossId", "");
                String currentBossId = jo.optString("currentBossId", "");

                // 判断如果老板是当前账号则查找下一个
                if (currentBossId.equals(UserIdMap.getCurrentUid())) {
                    continue;
                }

                // 判断是否为购买列表中的好友
                boolean isTradeMember = clubTradeMemberList.getValue().contains(originBossId);
                // 判断是选中购买还是未选中购买
                if (clubTradeMemberType.getValue() != TradeMemberType.TRADE) {
                    isTradeMember = !isTradeMember;
                }
                if (!isTradeMember) {
                    continue;
                }

                // 标识为可购买的好友，如果在当前账户的训练房间中则标识为false
                boolean canbuyMember = true;
                JSONObject joTrain = new JSONObject(AntSportsRpcCall.queryClubHome());
                if (!MessageUtil.checkResultCode(TAG, joTrain)) {
                    return;
                }
                JSONArray roomListTrain = joTrain.optJSONArray("roomList");
                if (roomListTrain == null) {
                    continue;
                }
                for (int j = 0; j < roomListTrain.length(); j++) {
                    JSONObject roomTrain = roomListTrain.optJSONObject(j);
                    if (roomTrain == null) {
                        continue;
                    }
                    // memberList 缺失时按「房间里没人」处理，不能让一个坏房间中断整轮买房判断
                    JSONArray memberList = roomTrain.optJSONArray("memberList");
                    JSONObject member = memberList == null || memberList.length() == 0 ? null : memberList.optJSONObject(0);
                    if (member != null) {
                        if (originBossId.equals(member.optString("originBossId", ""))) {
                            canbuyMember = false;
                        }
                    }
                }
                // 不管是否购买好友成功，都返回继续检测下一个房间
                if (canbuyMember) {
                    buyMember(roomId, queryClubMember(jo));
                    return;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryMemberPriceRanking err:");
            Log.printStackTrace(TAG, t);
        }
        return;
    }

    private JSONObject queryClubMember(JSONObject member) {
        try {
            String memberId = member.optString("memberId", "");
            String originBossId = member.optString("originBossId", "");
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryClubMember(memberId, originBossId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                // 成员详情缺 member/priceInfo 时保持原返回值（不塞 priceInfo），避免调用方拿到半截数据
                JSONObject memberInfo = jo.optJSONObject("member");
                JSONObject priceInfo = memberInfo == null ? null : memberInfo.optJSONObject("priceInfo");
                if (priceInfo == null) {
                    return member;
                }
                member.put("priceInfo", priceInfo);

                return member;
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryClubMember err:");
            Log.printStackTrace(TAG, t);
        }
        return null;
    }

    private Boolean buyMember(String roomId, JSONObject member) {
        if (member == null) {
            return false;
        }
        try {
            String currentBossId = member.optString("currentBossId", "");
            String currentBossShowName = UserIdMap.getShowName(currentBossId) != null ? UserIdMap.getShowName(currentBossId) : currentBossId;
            String memberId = member.optString("memberId", "");
            String originBossId = member.optString("originBossId", "");
            JSONObject priceInfo = member.optJSONObject("priceInfo");
            if (priceInfo == null) {
                return false;
            }
            JSONObject jo = new JSONObject(AntSportsRpcCall.buyMember(currentBossId, memberId, originBossId, priceInfo, roomId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                String userName = UserIdMap.getShowName(originBossId);
                int price = member.optInt("price", 0);
                Log.other("好友大战🉐抢购[" + userName + "]来自[" + currentBossShowName + "]花费[" + price + "健康能量]" + "#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                Toast.show("好友大战🉐抢购[" + userName + "]来自[" + currentBossShowName + "]花费[" + price + "健康能量]");
                return true;
            } else {
                return false;
            }
        } catch (Throwable t) {
            Log.i(TAG, "buyMember err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    private void coinExchangeItem(String itemId) {
        try {
            JSONObject jo = new JSONObject(AntSportsRpcCall.queryItemDetail(itemId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            jo = jo.optJSONObject("data");
            if (jo == null) {
                return;
            }
            if (!"OK".equals(jo.optString("exchangeBtnStatus"))) {
                return;
            }
            jo = jo.optJSONObject("itemBaseInfo");
            if (jo == null) {
                return;
            }
            String itemTitle = jo.optString("itemTitle", "");
            int valueCoinCount = jo.optInt("valueCoinCount", 0);
            jo = new JSONObject(AntSportsRpcCall.exchangeItem(itemId, valueCoinCount));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            jo = jo.optJSONObject("data");
            if (jo == null) {
                return;
            }
            if (jo.optBoolean("exgSuccess")) {
                Log.other("运动好礼🎐兑换[" + itemTitle + "]花费" + valueCoinCount + "运动币");
            }
        } catch (Throwable t) {
            Log.i(TAG, "trainMember err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 领取特殊奖励
     *
     * @param sceneType  场景类型
     * @param rewardName 奖励名称
     */
    public static void receiveSpecialPrize(String sceneType, String rewardName) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.receiveSpecialPrize(sceneType));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return;
                }
                int energy = data.optInt("modifyCount", 0);
                if (energy > 0) {
                    Log.other("悦动健康🚑️领取奖励[" + rewardName + "]#获得[" + energy + "g健康能量]");
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "receiveSpecialPrize err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 签到
     *
     * @return 是否签到成功
     */
    public static boolean signIn() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.takeSign());
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return false;
                }
                // 签到成功后这两个子对象只用于拼日志，缺字段不该让「已签到」被当成失败
                JSONObject continuousSignInfo = data.optJSONObject("continuousSignInfo");
                JSONObject continuousDoSignInVO = data.optJSONObject("continuousDoSignInVO");
                int continuousDay = continuousSignInfo == null ? 0 : continuousSignInfo.optInt("continuitySignedDayCount", 0);
                int reward = continuousDoSignInVO == null ? 0 : continuousDoSignInVO.optInt("rewardAmount", 0);
                Log.other("悦动健康🚑️连续签到[第" + continuousDay + "天]#获得[" + reward + "g健康能量]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                return true;
            }
        } catch (Exception e) {
            Log.i(TAG, "takeSign err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    /**
     * 领取任务奖励
     *
     * @param task 任务JSON对象
     * @return 是否领取成功
     */
    public static boolean receiveTaskReward(JSONObject task) {
        try {
            task.put("scene", "MED_TASK_HALL").put("source", "jkdprizesign");
            String arg = "[" + task.toString() + "]";
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.neverlandtaskReceive(arg));

            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                String taskName = task.optString("title", "");
                // 领取接口成功但不回 userItems 时按「没拿到奖励」处理，保持原返回值语义
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    Log.i(TAG, "悦动健康 跳过[" + taskName + "]#缺少字段 data");
                    return false;
                }
                JSONArray rewards = data.optJSONArray("userItems");
                if (rewards == null) {
                    Log.i(TAG, "悦动健康 跳过[" + taskName + "]#缺少字段 userItems");
                    return false;
                }
                ArrayList<String> rewardList = parseRewards(rewards);
                Log.other("悦动健康🚑️领取奖励[" + taskName + "]#获得" + rewardList);
                return true;
            }
        } catch (Exception e) {
            Log.i(TAG, "taskReceive err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    /**
     * 完成任务
     *
     * @param task 任务JSON对象
     * @return 是否完成成功
     */
    public static boolean completeTask(JSONObject task) {
        try {
            task.put("scene", "MED_TASK_HALL");
            String arg = "[" + task.toString() + "]";
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.neverlandtaskSend(arg));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                String taskName = task.optString("title", "");
                Log.other("悦动健康🚑️完成任务[" + taskName + "]");
                TimeUtil.sleep(1000);
                return true;
            }
        } catch (Exception e) {
            Log.i(TAG, "taskSend err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    /**
     * 能量泵前进
     *
     * @param branchId 分支ID
     * @param mapId    地图ID
     * @param mapName  地图名称
     * @return 是否继续前进
     */
    public static boolean walkGrid(String branchId, String mapId, String mapName) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.neverlandwalkGrid(branchId, mapId));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    Log.i(TAG, "悦动健康能量泵 跳过[" + mapName + "]#缺少字段 data");
                    return false;
                }
                // 原来直接在 mapAwards 上取 [0]：数组为空或缺失都会抛异常，
                // 而返回值 false 只会让外层结束循环，日志里看不出原因
                JSONArray mapAwards = data.optJSONArray("mapAwards");
                JSONObject firstAward = mapAwards == null ? null : mapAwards.optJSONObject(0);
                int step = firstAward == null ? 0 : firstAward.optInt("step", 0);
                int leftCount = data.optInt("leftCount", 0);
                Log.other("悦动健康🚑️能量泵[" + mapName + "]#前进[" + step + "步]");

                JSONArray rewards = data.optJSONArray("userItems");
                ArrayList<String> rewardList = rewards == null ? new ArrayList<>() : parseRewards(rewards);
                if (!rewardList.isEmpty()) {
                    Log.other("悦动健康🚑️能量泵[" + mapName + "]#获得" + rewardList);
                }

                JSONObject starData = data.optJSONObject("starData");
                int currentStar = starData == null ? 0 : starData.optInt("curr", 0);
                int totalStar = starData == null ? 0 : starData.optInt("count", 0);
                return leftCount >= 5 && currentStar < totalStar;
            }
        } catch (Exception e) {
            Log.i(TAG, "walkGrid err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    public static int build(String branchId, String mapId, String mapName, int multiNum) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.build(branchId, mapId, multiNum));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return 0;
                }
                JSONObject endStageInfo = data.optJSONObject("endStageInfo");
                if (endStageInfo == null) {
                    return 0;
                }
                int buildingEnergyFinal = endStageInfo.optInt("buildingEnergyFinal");
                String buildingId = endStageInfo.optString("buildingId");
                int endbuildingEnergyProcess = endStageInfo.optInt("buildingEnergyProcess");
                Log.other("悦动健康🚑️能量泵[" + mapName + "]建造[" + buildingId + "]进度(" + endbuildingEnergyProcess + "/" + buildingEnergyFinal + ")#消耗" + multiNum * 5 + "g能量");
                JSONArray rewards = data.optJSONArray("rewards");
                if (rewards == null) {
                    return 0;
                }
                ArrayList<String> rewardList = parseRewards(rewards);
                if (!rewardList.isEmpty()) {
                    Log.other("悦动健康🚑️能量泵[" + mapName + "]#获得" + rewardList);
                }
                return buildingEnergyFinal - endbuildingEnergyProcess;
            }
        } catch (Exception e) {
            Log.i(TAG, "build err:");
            Log.printStackTrace(TAG, e);
        }
        return 0;
    }

    /**
     * 领取浏览任务奖励
     *
     * @param task 任务JSON对象
     * @return 是否领取成功
     */
    public static boolean receiveBrowseReward(JSONObject task) {
        if (!task.has("encryptValue") || !task.has("energyNum")) {
            return false;
        }

        try {
            task.put("type", "LIGHT_FEEDS_TASK");
            String arg = "[" + task.toString() + "]";
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.neverlandenergyReceive(arg));

            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return false;
                }
                JSONArray prizes = data.optJSONArray("prizes");
                if (prizes == null) {
                    return false;
                }
                int totalEnergy = 0;
                for (int i = 0; i < prizes.length(); i++) {
                    JSONObject prize = prizes.optJSONObject(i);
                    if (prize == null) {
                        continue;
                    }
                    totalEnergy += prize.optInt("prizeCount", 0);
                }

                String taskName = task.optString("title", "浏览商品15s得健康能量");
                Log.other("悦动健康🚑️完成任务[" + taskName + "]#获得[" + totalEnergy + "g健康能量]");
                return true;
            }
        } catch (Exception e) {
            Log.i(TAG, "energyReceive err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    /**
     * 领取离线奖励
     */
    public static void receiveOfflineReward() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.offlineAward());
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                // 无离线奖励时服务端可能不回 data/userItems，缺字段按「没有奖励」处理即可
                JSONObject data = jsonResult.optJSONObject("data");
                JSONArray rewards = data == null ? null : data.optJSONArray("userItems");
                if (rewards != null) {
                    ArrayList<String> rewardList = parseRewards(rewards);
                    if (!rewardList.isEmpty()) {
                        Log.other("悦动健康🚑️领取奖励[离线奖励]#获得" + rewardList);
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "offlineAward err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 解析奖励列表
     *
     * @param rewards 奖励JSON数组
     * @return 格式化后的奖励列表
     */
    public static ArrayList<String> parseRewards(JSONArray rewards) {
        ArrayList<String> rewardList = new ArrayList<>();
        try {
            for (int i = 0; i < rewards.length(); i++) {
                JSONObject reward = rewards.optJSONObject(i);
                if (reward == null) {
                    continue;
                }
                int count = reward.optInt("modifyCount");
                if (count <= 0) {
                    continue;
                }

                String unit = "H1".equals(reward.optString("itemId")) ? "g" : "";
                String name = reward.optString("name", "");
                if (name.isEmpty()) continue;
                rewardList.add(count + unit + name);
            }
        } catch (Exception e) {
            Log.i(TAG, "parseRewards err:");
            Log.printStackTrace(TAG, e);
        }
        return rewardList;
    }

    /**
     * 领取气泡任务奖励
     *
     * @param recordId   记录ID
     * @param rewardName 奖励名称
     */
    public static void receiveBubbleReward(String recordId, String rewardName) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.neverlandpickBubbleTaskEnergy(recordId));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return;
                }
                String energy = data.optString("changeAmount", "");
                Log.other("悦动健康🚑️领取奖励[" + rewardName + "]#获得[" + energy + "g健康能量]");
            }
        } catch (Exception e) {
            Log.i(TAG, "pickBubbleTaskEnergy err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 查询基础信息并处理相关任务
     */
    public void queryBaseInfoAndProcess() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryBaseinfo());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }
            JSONObject data = jsonResult.optJSONObject("data");
            if (data == null) {
                return;
            }
            // 处理离线奖励：字段缺失按「没有离线奖励」处理
            JSONArray offlineAwards = data.optJSONArray("offlineAwards");
            if (offlineAwards != null && offlineAwards.length() > 0) {
                receiveOfflineReward();
            }

            // 处理普通岛能量泵任务
            if (!data.optBoolean("newGame") && WALK_GRID.getValue()) {
                String branchId = data.optString("branchId", "");
                String mapId = data.optString("mapId", "");
                String mapName = data.optString("mapName", "");
                int walkGridcount = 0;
                if (canWalkGrid(branchId, mapId) && queryUserEnergy() >= 5 && queryUserEnergy() >= WALK_GRID_LIMIT.getValue()) {
                    while (walkGrid(branchId, mapId, mapName)) {
                        TimeUtil.sleep(2000);
                        if (WALK_GRID_MAX.getValue() == 0) {
                            continue;
                        }
                        walkGridcount++;
                        if (walkGridcount >= WALK_GRID_MAX.getValue() || queryUserEnergy() < 5 || queryUserEnergy() <= WALK_GRID_LIMIT.getValue()) {
                            break;
                        }
                    }
                }
            }
            // 处理活动岛能量泵任务
            if (data.optBoolean("newGame") && WALK_GRID.getValue()) {
                String branchId = data.optString("branchId", "");
                String mapId = data.optString("mapId", "");
                String mapName = data.optString("mapName", "");
                int buildcount = 0;
                if (canBuild(mapId) && queryUserEnergy() >= 5 && queryUserEnergy() >= WALK_GRID_LIMIT.getValue()) {
                    int remainBuildingEnergyProcess = build(branchId, mapId, mapName, 1);
                    buildcount++;
                    if (buildcount >= WALK_GRID_MAX.getValue() && WALK_GRID_MAX.getValue() != 0) {
                        return;
                    }
                    while (remainBuildingEnergyProcess > 0 && canBuild(mapId)) {
                        TimeUtil.sleep(2000);
                        if (remainBuildingEnergyProcess >= 50 && ((WALK_GRID_MAX.getValue() - buildcount) >= 10 || WALK_GRID_MAX.getValue() == 0) && queryUserEnergy() >= 50) {
                            remainBuildingEnergyProcess = build(branchId, mapId, mapName, 10);
                            buildcount = buildcount + 10;
                        } else if (remainBuildingEnergyProcess >= 25 && ((WALK_GRID_MAX.getValue() - buildcount) >= 5 || WALK_GRID_MAX.getValue() == 0) && queryUserEnergy() >= 25) {
                            remainBuildingEnergyProcess = build(branchId, mapId, mapName, 5);
                            buildcount = buildcount + 5;
                        } else {
                            remainBuildingEnergyProcess = build(branchId, mapId, mapName, 1);
                            buildcount++;
                        }
                        if (WALK_GRID_MAX.getValue() == 0) {
                            continue;
                        }
                        if (buildcount >= WALK_GRID_MAX.getValue() || queryUserEnergy() < 5 || queryUserEnergy() <= WALK_GRID_LIMIT.getValue()) {
                            break;
                        }
                    }
                }
            }
            if (awardspecialActivityReceive.getValue()) {
                //领取活动岛奖励
                if (data.optBoolean("newGame")) {
                    String branchId = data.optString("branchId", "");
                    String mapId = data.optString("mapId", "");
                    String mapName = data.optString("mapName", "");
                    jsonResult = new JSONObject(AntSportsRpcCall.queryMapDetail(mapId));
                    if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                        JSONObject dataMapDetail = jsonResult.optJSONObject("data");
                        if (dataMapDetail == null) {
                            return;
                        }
                        JSONObject baseMapInfo = dataMapDetail.optJSONObject("baseMapInfo");
                        if (baseMapInfo == null) {
                            return;
                        }
                        if (baseMapInfo.optInt("currentPercent", 0) == 100 && baseMapInfo.optString("status").equals("FINISH_NOT_REWARD")) {
                            JSONArray rewards = baseMapInfo.optJSONArray("rewards");
                            if (rewards == null) {
                                return;
                            }
                            for (int i = 0; i < rewards.length(); i++) {
                                JSONObject reward = rewards.optJSONObject(i);
                                if (reward == null) {
                                    continue;
                                }
                                if (reward.optString("prizeStatus").equals("待领取")) {
                                    String itemId = reward.optString("itemId");
                                    JSONObject mapChooseRewardjo = new JSONObject(AntSportsRpcCall.mapChooseReward(branchId, mapId, itemId));
                                    if (MessageUtil.checkSuccess(TAG, mapChooseRewardjo)) {
                                        data = mapChooseRewardjo.optJSONObject("data");
                                        if (data == null) {
                                            continue;
                                        }
                                        JSONObject specialActivityReceiveResult = data.optJSONObject("specialActivityReceiveResult");
                                        if (specialActivityReceiveResult == null) {
                                            continue;
                                        }
                                        JSONArray prizes = specialActivityReceiveResult.optJSONArray("prizes");
                                        if (prizes == null) {
                                            continue;
                                        }
                                        JSONObject prize = prizes.optJSONObject(0);
                                        if (prize == null) {
                                            continue;
                                        }
                                        String subTitle = prize.optString("subTitle");
                                        String title = prize.optString("title");
                                        Log.other("悦动健康🚑️领取奖励[" + subTitle + "]#获得[" + title + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "queryBaseInfo err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 查询气泡任务并处理
     */
    public static void queryAndProcessBubbleTasks() {
        queryAndProcessBubbleTasks(0);
    }

    /**
     * @param attempt 已自递归次数：服务端回「操作成功」但状态不前进时，原实现会无限加深调用栈（M 同款改法）
     */
    private static void queryAndProcessBubbleTasks(int attempt) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryBubbleTask());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }
            JSONObject data = jsonResult.optJSONObject("data");
            if (data == null) {
                return;
            }
            JSONArray tasks = data.optJSONArray("bubbleTaskVOS");
            if (tasks == null) {
                return;
            }
            boolean needRetry = false;

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                if (!task.has("bubbleTaskStatus")) {
                    continue;
                }
                String title = task.optString("title", "");
                String bubbleTaskStatus = task.optString("bubbleTaskStatus", "");

                if (bubbleTaskStatus.equals("INIT")) {
                    if ("AD_BALL".equals(task.optString("taskId", ""))) {
                        task.put("lightTaskId", "adBubble");
                        if (receiveBrowseReward(task)) {
                            TimeUtil.sleep(1000);
                            needRetry = true;
                        }
                    } else if ("STRATEGY_BALL".equals(task.optString("taskId", ""))) {
                        receiveSpecialPrize(task.optString("taskId", "") + "_ACTIVITY", title);
                    } else if ("SIGN_BALL".equals(task.optString("taskId", ""))) {
                        signIn();
                    }
                    break;
                }
                if (bubbleTaskStatus.equals("TO_RECEIVE")) {
                    // 已完成任务，领取奖励
                    receiveBubbleReward(task.optString("medEnergyBallInfoRecordId", ""), title);
                    break;
                }
            }
            // 如果有任务触发了状态变更，重试一次（带次数上限）
            if (needRetry) {
                if (attempt >= MAX_SELF_RETRY) {
                    Log.i(TAG, "气泡任务重试达到上限，本轮停止");
                    return;
                }
                queryAndProcessBubbleTasks(attempt + 1);
            }
        } catch (Exception e) {
            Log.i(TAG, "queryBubbleTask err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 兑换权益
     */
    public void exchangeBenefits() {
        int currentEnergy = queryUserEnergy();
        int page = 1;
        boolean hasMore = true;

        try {
            // 翻页上限：这个循环体内没有 sleep，服务端恒回 hasMore=true 时会一直打接口
            while (hasMore && page <= MAX_BENEFIT_PAGE) {
                if (Status.hasFlagToday("sport::exchangeBenefits_ERROR")) {
                    return;
                }
                JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryItemList(page));
                String errorMessage = jsonResult.optString("errorMessage");
                if (errorMessage.equals("系统繁忙，请稍后再试。")) {
                    Status.flagToday("sport::exchangeBenefits_ERROR");
                    return;
                }
                if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                    break;
                }

                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    break;
                }
                hasMore = data.optBoolean("hasMore");
                if (!data.has("itemVOList")) {
                    break;
                }

                JSONArray items = data.optJSONArray("itemVOList");
                if (items == null) {
                    break;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    if (!"benefitItem".equals(item.optString("materialType", ""))) {
                        continue;
                    }

                    String benefitId = item.optString("benefitId", "");
                    String itemId = item.optString("itemId", "");
                    String itemName = item.optString("itemName", "");
                    int remainCount = item.optInt("remainCount", 0);
                    // salePoint 是字符串数字：缺失时 Integer.parseInt("") 会抛异常中断整个兑换循环
                    int cost = parseIntQuietly(item.optString("salePoint", ""), 0);

                    // 检查是否可兑换
                    if (remainCount >= 1 && neverLandBenefitList.contains(itemId) && currentEnergy >= cost) {
                        if (item.optString("status", "").equals("ITEM_SALE")) {
                            String exchangeResult = AntSportsRpcCall.createOrder(benefitId, itemId);
                            if (MessageUtil.checkSuccess(TAG, new JSONObject(exchangeResult))) {
                                Log.other("悦动健康🚑️兑换权益[" + itemName + "]#消耗[" + cost + "g健康能量]");
                                currentEnergy -= cost;
                            }
                        }
                    }
                }
                page++;
            }
        } catch (Exception e) {
            Log.i(TAG, "exchangeBenefits err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 检查是否可进行能量泵前进
     *
     * @param branchId 分支ID
     * @param mapId    地图ID
     * @return 是否可前进
     */
    public static boolean canWalkGrid(String branchId, String mapId) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryMapInfo(branchId, mapId));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return false;
                }
                JSONObject starData = data.optJSONObject("starData");
                if (starData == null) {
                    return false;
                }
                return data.optBoolean("canWalk", false) && starData.optInt("curr", 0) < starData.optInt("count", 0);
            }
        } catch (Exception e) {
            Log.i(TAG, "canWalkGrid err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    public static boolean canBuild(String mapId) {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryMapDetail(mapId));
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return false;
                }
                JSONObject baseMapInfo = data.optJSONObject("baseMapInfo");
                if (baseMapInfo == null) {
                    return false;
                }
                return baseMapInfo.optBoolean("newIsLandFlg", false) && baseMapInfo.optInt("currentPercent", 0) < 100;
            }
        } catch (Exception e) {
            Log.i(TAG, "canBuild err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    /**
     * 处理签到逻辑
     */
    public static void processSignIn() {
        if (Status.hasFlagToday("NeverLand::SIGN")) {
            return;
        }

        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.querySign());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }

            JSONObject data = jsonResult.optJSONObject("data");
            if (data == null) {
                return;
            }
            if (!data.has("days")) {
                return;
            }

            JSONArray days = data.optJSONArray("days");
            if (days == null) {
                return;
            }
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                if (day == null) {
                    continue;
                }
                if (day.optBoolean("current") && !day.optBoolean("signIn")) {
                    if (signIn()) {
                        Status.flagToday("NeverLand::SIGN");
                        return;
                    }
                }
            }

            // 检查连续签到状态
            if (data.has("continuousSignInfo")) {
                JSONObject continuousInfo = data.optJSONObject("continuousSignInfo");
                if (continuousInfo == null) {
                    return;
                }
                if (continuousInfo.optBoolean("signedToday") || signIn()) {
                    Status.flagToday("NeverLand::SIGN");
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "processSignIn err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 处理任务中心任务
     */
    public static void processTaskCenter() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryTaskCenter());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }

            JSONObject data = jsonResult.optJSONObject("data");
            if (data == null) {
                return;
            }
            JSONArray tasks = data.optJSONArray("taskCenterTaskVOS");
            if (tasks == null) {
                return;
            }
            boolean needRetry = false;

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String status = task.optString("taskStatus", "");

                if ("SIGNUP_COMPLETE".equals(status)) {
                    String taskType = task.optString("taskType", "");
                    if ("LIGHT_TASK".equals(taskType)) {
                        if (task.has("logExtMap")) {
                            JSONObject logExtMap = task.optJSONObject("logExtMap");
                            if (logExtMap == null) {
                                continue;
                            }
                            //if (TaskHelper.checkTaskCompleted(logExtMap.getString("taskType"), logExtMap.getString("bizId"))) {
                            //
                            //    TimeUtil.sleep(1000);
                            //    needRetry = true;
                            //}
                        }
                    } else if ("PROMOKERNEL_TASK".equals(taskType)) {
                        if (completeTask(task)) {
                            task.put("taskStatus", "TO_RECEIVE");
                            TimeUtil.sleep(2000);
                            needRetry = true;
                        }
                    }
                } else if ("TO_RECEIVE".equals(status)) {
                    if (receiveTaskReward(task)) {
                        TimeUtil.sleep(1000);
                        needRetry = true;
                    }
                }
            }

            if (needRetry) {
                processTaskCenter();
            }
        } catch (Exception e) {
            Log.i(TAG, "processTaskCenter err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 处理浏览任务
     */
    public static void processBrowseTasks() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryTaskInfo());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }

            JSONObject data = jsonResult.optJSONObject("data");
            if (data == null) {
                return;
            }
            if (!data.has("taskInfos")) {
                return;
            }

            JSONArray tasks = data.optJSONArray("taskInfos");
            if (tasks == null) {
                return;
            }
            boolean hasNewTask = false;

            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                String taskName = task.optString("title", "浏览商品15s得健康能量");
                long viewMillis = TimeUnit.SECONDS.toMillis(task.optInt("viewSec", 0));
                // 行为伪造：真人要「看够 viewSec 秒」，这里按服务端给的时长等待后领奖（服务端只认客户端等待）。
                // 交给 TaskAttemptPolicy 管：同一天同一任务只伪造一次，失败不再每轮白等 15 秒
                Outcome outcome = TaskAttemptPolicy.handle("sports::browse::" + taskName, taskName, null,
                        () -> {
                            TimeUtil.sleep(viewMillis);
                            return receiveBrowseReward(task) ? Outcome.DONE : Outcome.UNABLE;
                        },
                        Log::other, new TaskAttemptPolicy.Site("AntSportsTaskList", "运动任务", taskName, "",
                                (k) -> probeBrowseStatus(taskName)));
                if (outcome == Outcome.DONE) {
                    hasNewTask = true;
                }
            }

            if (hasNewTask) {
                processBrowseTasks();
            }
        } catch (Exception e) {
            Log.i(TAG, "processBrowseTasks err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 查询用户能量值
     *
     * @return 能量值
     */
    public static int queryUserEnergy() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryUserAccount());
            if (MessageUtil.checkSuccess(TAG, jsonResult)) {
                JSONObject data = jsonResult.optJSONObject("data");
                if (data == null) {
                    return 0;
                }
                return parseIntQuietly(data.optString("balance", ""), 0);
            }
        } catch (Exception e) {
            Log.i(TAG, "queryUserEnergy err:");
            Log.printStackTrace(TAG, e);
        }
        return 0;
    }

    public void neverlandrun() {
        try {
            Log.record("悦动健康🚑️开始执行#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
            // 处理签到
            if (QUERY_SIGN.getValue()) {
                processSignIn();
            }
            // 处理任务中心
            if (QUERY_TASK_CENTER.getValue()) {
                processTaskCenter();
            }
            // 处理浏览任务
            processBrowseTasks();
            // 处理气泡任务
            if (QUERY_BUBBLE_TASK.getValue()) {
                queryAndProcessBubbleTasks();
            }
            // 处理基础信息相关任务
            queryBaseInfoAndProcess();
            // 兑换权益
            if (QUERY_ITEM_LIST.getValue()) {
                exchangeBenefits();
            }
            if (MapListSwitch.getValue()) {
                queryMapListSwitch();
            }

            Log.record("悦动健康🚑️执行完成#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
        } catch (Exception e) {
            Log.i(TAG, "run err:");
            Log.printStackTrace(TAG, e);
        }
    }

    private void queryMapListSwitch() {
        try {
            //获取当前岛名字
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.queryBaseinfo());
            if (!MessageUtil.checkSuccess(TAG, jsonResult)) {
                return;
            }
            JSONObject thisdata = jsonResult.optJSONObject("data");
            if (thisdata == null) {
                return;
            }
            String thismapName = thisdata.optString("mapName");

            //获取岛地图
            JSONObject jsonLandMap = new JSONObject(AntSportsRpcCall.queryMapList());
            if (MessageUtil.checkSuccess("queryMapList", jsonLandMap)) {
                JSONObject data = jsonLandMap.optJSONObject("data");
                if (data == null) {
                    return;
                }

                JSONArray mapList = data.optJSONArray("mapList");
                if (mapList == null) {
                    return;
                }
                boolean needSwitch = false;

                for (int i = 0; i < mapList.length(); i++) {
                    JSONObject map = mapList.optJSONObject(i);
                    if (map == null) {
                        continue;
                    }
                    String mapName = map.optString("mapName", "");
                    String status = map.optString("status", "");

                    if (mapName.equals(thismapName) && status.contains("FINISH")) {
                        needSwitch = true;
                    }
                }
                if (needSwitch) {
                    for (int i = 0; i < mapList.length(); i++) {
                        JSONObject map = mapList.optJSONObject(i);
                        if (map == null) {
                            continue;
                        }
                        String mapName = map.optString("mapName", "");
                        String mapId = map.optString("mapId", "");
                        String status = map.optString("status", "");
                        String branchId = map.optString("branchId", "");
                        //boolean newIsLandFlg = map.optBoolean("newIsLandFlg");

                        if (!mapName.equals(thismapName)) {
                            //if (!status.contains("FINISH") && !newIsLandFlg) {
                            if (!status.contains("FINISH")) {
                                JSONObject jo = new JSONObject(AntSportsRpcCall.mapChooseFree(branchId, mapId));
                                if (MessageUtil.checkSuccess("mapChooseFree", jo)) {
                                    Log.other("悦动健康🚑️切换到[" + mapName + "](" + mapId + ")#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                    break;
                                }
                            }
                        }
                    }
                    queryBaseInfoAndProcess();
                }

            }
        } catch (Exception e) {
            Log.i(TAG, "queryMapListSwitch err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 检查权限
     *
     * @return 是否有权限
     */
    private boolean checkAuth() {
        try {
            JSONObject jsonResult = new JSONObject(AntSportsRpcCall.checkAuth());
            if (MessageUtil.checkSuccess("NeverLandAuth", jsonResult)) {
                // 缺 resultObj 视为无权限，而不是抛异常后走 catch 返回 false 的模糊路径
                JSONObject resultObj = jsonResult.optJSONObject("resultObj");
                return resultObj != null && resultObj.optBoolean("authStatus");
            }
        } catch (Exception e) {
            Log.i(TAG, "checkAuth err:");
            Log.printStackTrace(TAG, e);
        }
        return false;
    }

    // 任务状态枚举
    public enum neverlandTaskStatus {
        TODO, FINISHED, EXPIRED, DISABLED
    }

    // 能量策略枚举
    public interface EnergyStrategy {
        int NONE = 0;
        int CONSERVE = 1;
        int MAXIMIZE = 2;
        String[] nickNames = {"不操作", "保守策略", "最大化收益"};
    }

    // 任务选项接口
    public interface NeverLandOption {
    }

    public enum PathCompleteStatus {
        NOT_JOIN, JOIN, NOT_COMPLETED, COMPLETED, INTERRUPT;
    }

    public enum TaskStatus {
        WAIT_COMPLETE, WAIT_RECEIVE, HAS_RECEIVED;
    }


    public interface WalkPathTheme {
        int DA_MEI_ZHONG_GUO = 0;
        int GONG_YI_YI_XIAO_BU = 1;
        int DENG_DING_ZHI_MA_SHAN = 2;
        int WEI_C_DA_TIAO_ZHAN = 3;
        int LONG_NIAN_QI_FU = 4;
        int SHOU_HU_TI_YU_MENG = 5;

        String[] nickNames = {"大美中国", "公益一小步", "登顶芝麻山", "维C大挑战", "龙年祈福", "守护体育梦"};
        String[] walkPathThemeIds = {"M202308082226", "M202401042147", "V202405271625", "202404221422", "WF202312050200", "V202409061650"};
    }

    public interface DonateCharityCoinType {

        int ZERO = 0;
        int ONE = 1;
        int ALL = 2;

        String[] nickNames = {"不捐赠", "捐赠一个项目", "捐赠所有项目"};
    }

    public interface TradeMemberType {

        int NONE = 0;
        int TRADE = 1;
        int NOT_TRADE = 2;

        String[] nickNames = {"不抢购", "抢购已选好友", "抢购未选好友"};
    }

    public interface TrainItemType {

        int NONE = 0;
        int BALLET = 1;
        int SANDBAG = 2;
        int BARBELL = 3;
        int YANGKO = 4;
        int SKATE = 5;
        int MUD = 6;

        String[] nickNames = {"不训练", "跳芭蕾", "打沙包", "举杠铃", "扭秧歌", "玩滑板", "踩泥坑"};
        String[] itemTypes = {"", "ballet", "sandbag", "barbell", "yangko", "skate", "mud"};
    }
}
