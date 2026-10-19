package com.sesame.wu.model.task.antForest;

import static com.sesame.wu.model.normal.base.BaseModel.taskRpcRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sesame.wu.util.XHelpers;
import com.sesame.wu.data.ConfigV2;
import com.sesame.wu.data.ModelFields;
import com.sesame.wu.data.ModelGroup;
import com.sesame.wu.data.RuntimeInfo;
import com.sesame.wu.data.TokenConfig;
import com.sesame.wu.data.modelFieldExt.BooleanModelField;
import com.sesame.wu.data.modelFieldExt.ChoiceModelField;
import com.sesame.wu.data.modelFieldExt.EmptyModelField;
import com.sesame.wu.data.modelFieldExt.IntegerModelField;
import com.sesame.wu.data.modelFieldExt.ListModelField;
import com.sesame.wu.data.modelFieldExt.SelectAndCountModelField;
import com.sesame.wu.data.modelFieldExt.SelectModelField;
import com.sesame.wu.data.modelFieldExt.StringModelField;
import com.sesame.wu.data.modelFieldExt.TextModelField;
import com.sesame.wu.data.task.ModelTask;
import com.sesame.wu.data.task.TaskAttemptPolicy;
import com.sesame.wu.data.task.TaskAttemptPolicy.Outcome;
import com.sesame.wu.data.task.TaskAward;
import com.sesame.wu.entity.AlipayAntForestHuntTaskList;
import com.sesame.wu.entity.AlipayAntForestVitalityTaskList;
import com.sesame.wu.entity.AlipayUser;
import com.sesame.wu.entity.CollectEnergyEntity;
import com.sesame.wu.entity.CustomOption;
import com.sesame.wu.entity.FriendWatch;
import com.sesame.wu.entity.KVNode;
import com.sesame.wu.entity.RpcEntity;
import com.sesame.wu.entity.VitalityBenefit;
import com.sesame.wu.entity.AlipayForestHunt;
import com.sesame.wu.hook.ApplicationHook;
import com.sesame.wu.hook.Toast;
import com.sesame.wu.model.base.TaskAlternative;
import com.sesame.wu.model.base.TaskCommon;
import com.sesame.wu.model.extensions.ExtensionsHandle;
import com.sesame.wu.model.normal.base.BaseModel;
import com.sesame.wu.model.task.antFarm.AntFarm.TaskStatus;
import com.sesame.wu.model.task.antFarm.AntFarmRpcCall;
import com.sesame.wu.model.task.antGame.GameTask;
import com.sesame.wu.rpc.intervallimit.FixedOrRangeIntervalLimit;
import com.sesame.wu.rpc.intervallimit.RpcIntervalLimit;
import com.sesame.wu.ui.ObjReference;
import com.sesame.wu.util.AverageMath;
import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.JsonUtil;
import com.sesame.wu.util.ListUtil;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.MessageUtil;
import com.sesame.wu.util.NotificationUtil;
import com.sesame.wu.util.RandomUtil;
import com.sesame.wu.util.Statistics;
import com.sesame.wu.util.Status;
import com.sesame.wu.util.StringUtil;
import com.sesame.wu.util.TaskBlacklist;
import com.sesame.wu.util.TimeUtil;
import com.sesame.wu.util.idMap.AntForestHuntTaskListMap;
import com.sesame.wu.util.idMap.AntForestVitalityTaskListMap;
import com.sesame.wu.util.idMap.UserIdMap;
import com.sesame.wu.util.idMap.VitalityBenefitIdMap;
import lombok.Getter;

/**
 * 蚂蚁森林V2
 */
public class AntForestV2 extends ModelTask {

    private static final String TAG = AntForestV2.class.getSimpleName();

    /** 森林乐园限定任务单轮最多重拉次数：只防服务端恒回 TODO 时死循环。 */
    /**
     * 乐园限定任务的轮次上限下限/上限：按任务实际阶段数算定，见 {@link #optionalPlayRoundLimit}。
     * <p>下限 12 是给足阶段数，上限 60 是防任务列表异常时长时间空转。
     */
    private static final int OPTIONAL_PLAY_ROUND_MIN = 12;
    private static final int OPTIONAL_PLAY_ROUND_MAX = 60;

    /** 森林集市「线上逛街」能量单轮最多重试次数：只防服务端恒回可发却不产生能量时死循环。 */
    private static final int MAX_MARKET_ENERGY_ROUNDS = 3;

    /**
     * 行为类任务（浇水/能量雨）伪造后等列表确认的轮询间隔与轮数。
     * <p>行为生效有延迟，不能无脑认定成功——行为已发但列表未确认时如实返回 false 留待下一轮，
     * 否则会立刻落当日标记，把"还没被服务端确认"的任务锁死（见 {@link #probeVitalityDone}）。
     */
    private static final long VITALITY_PROBE_INTERVAL_MS = 3000L;
    private static final int VITALITY_PROBE_ROUNDS = 3;

    private static final AverageMath offsetTimeMath = new AverageMath(5);

    private static final Map<String, Long> usingProps = new ConcurrentHashMap<>();

    private static final Map<String, String> dressMap;

    private static final Set<String> AntForestTaskTypeSet;

    static {
        dressMap = new HashMap<>();
        // position To positionType
        dressMap.put("tree__main", "treeMain");
        dressMap.put("bg__sky_0", "bgSky0");
        dressMap.put("bg__sky_cloud", "bgSkyCloud");
        dressMap.put("bg__ground_a", "bgGroundA");
        dressMap.put("bg__ground_b", "bgGroundB");
        dressMap.put("bg__ground_c", "bgGroundC");
        // positionType To position
        dressMap.put("treeMain", "tree__main");
        dressMap.put("bgSky0", "bg__sky_0");
        dressMap.put("bgSkyCloud", "bg__sky_cloud");
        dressMap.put("bgGroundA", "bg__ground_a");
        dressMap.put("bgGroundB", "bg__ground_b");
        dressMap.put("bgGroundC", "bg__ground_c");

        AntForestTaskTypeSet = new HashSet<>();
        AntForestTaskTypeSet.add("VITALITYQIANDAOPUSH"); //
        AntForestTaskTypeSet.add("ONE_CLICK_WATERING_V1"); // 给随机好友一键浇水
        AntForestTaskTypeSet.add("GYG_YUEDU_2"); // 去森林图书馆逛15s
        AntForestTaskTypeSet.add("GYG_TBRS"); // 逛一逛淘宝人生
        AntForestTaskTypeSet.add("TAOBAO_tab2_2023"); // 去淘宝看科普视频
        AntForestTaskTypeSet.add("GYG_diantao"); // 逛一逛点淘得红包
        AntForestTaskTypeSet.add("GYG-taote"); // 逛一逛淘宝特价版
        AntForestTaskTypeSet.add("NONGCHANG_20230818"); // 逛一逛淘宝芭芭农场
        // AntForestTaskTypeSet.add("GYG_haoyangmao_20240103");//逛一逛淘宝薅羊毛
        // AntForestTaskTypeSet.add("YAOYIYAO_0815");//去淘宝摇一摇领奖励
        // AntForestTaskTypeSet.add("GYG-TAOCAICAI");//逛一逛淘宝买菜
    }

    private final AtomicInteger taskCount = new AtomicInteger(0);

    private String selfId;

    private Integer tryCountInt;

    private Integer retryIntervalInt;

    private Integer advanceTimeInt;

    private Integer checkIntervalInt;

    private FixedOrRangeIntervalLimit collectIntervalEntity;

    private FixedOrRangeIntervalLimit doubleCollectIntervalEntity;

    private final AverageMath delayTimeMath = new AverageMath(5);

    private final ObjReference<Long> collectEnergyLockLimit = new ObjReference<>(0L);

    private final Object usePropLockObj = new Object();

    private BooleanModelField collectEnergy;
    private BooleanModelField expiredEnergy;
    private BooleanModelField energyRain;
    private IntegerModelField advanceTime;
    private IntegerModelField tryCount;
    private IntegerModelField retryInterval;
    private SelectModelField dontCollectList;

    private BooleanModelField drawGameCenterAward;
    private ChoiceModelField CollectSelfEnergyType;

    private IntegerModelField CollectSelfEnergyThreshold;
    private IntegerModelField collectRobExpandEnergy;
    private BooleanModelField collectWateringBubble;
    private BooleanModelField batchRobEnergy;
    private BooleanModelField balanceNetworkDelay;
    //PK能量
    private BooleanModelField pkEnergy;
    private ChoiceModelField whackModeName;
    private IntegerModelField whackModeGames;
    private IntegerModelField whackModeCount;
    private IntegerModelField earliestwhackMoleTime;

    // 定义运行模式名称数组（需提前声明，与原 Kotlin 中的 whackMoleModeNames 对应）

    private BooleanModelField collectProp;
    private StringModelField queryInterval;
    private StringModelField collectInterval;
    private StringModelField doubleCollectInterval;
    private ChoiceModelField doubleClickType;
    private ListModelField.ListJoinCommaToStringModelField doubleCardTime;
    @Getter
    private IntegerModelField doubleCountLimit;
    private IntegerModelField CollectBombEnergyLimit;
    private BooleanModelField useEnergyRainLimit;
    private BooleanModelField doubleCardConstant;
    private ChoiceModelField helpFriendCollectType;
    private SelectModelField helpFriendCollectList;

    private IntegerModelField helpFriendCollectListLimit;
    private IntegerModelField returnWater33;
    private IntegerModelField returnWater18;
    private IntegerModelField returnWater10;
    private BooleanModelField receiveForestTaskAward;

    private BooleanModelField AutoAntForestVitalityTaskList;
    private SelectModelField AntForestVitalityTaskList;
    private ChoiceModelField waterFriendType;
    private BooleanModelField waterFriendEnergySendChat;

    private BooleanModelField waterFriendEnergyFirst;
    private SelectAndCountModelField waterFriendList;

    private SelectAndCountModelField wateredFriendList;

    private BooleanModelField doubleWaterFriendEnergy;
    /** 本轮浇水已执行标志，防止水FriendEnergyFirst和最终调用重复执行 */
    private boolean wateringExecutedThisRound = false;
    private SelectModelField giveEnergyRainList;
    private BooleanModelField vitalityExchangeBenefit;
    private SelectAndCountModelField vitality_ExchangeBenefitList;
    private BooleanModelField collectGiftBox;
    private BooleanModelField medicalHealth;
    private BooleanModelField greenLife;

    /** 绿植浏览（GUANG_TASK）：15g 能量 + 5 积分 + 200 水滴，独立于森林集市 */
    private BooleanModelField marketBrowseTask;

    private BooleanModelField greenRent;
    private SelectModelField whoYouWantToGiveTo;
    private BooleanModelField ecoLife;
    private BooleanModelField youthPrivilege;
    private SelectModelField ecoLifeOptions;
    private BooleanModelField dress;
    private TextModelField dressDetailList;

    private static int totalCollected = 0;
    private static int totalHelpCollected = 0;
    private static boolean hasErrorWait = false;

    @Getter
    private Set<String> dontCollectMap = new HashSet<>();

    @Override
    public String getName() {
        return "森林";
    }

    @Override
    public ModelGroup getGroup() {
        return ModelGroup.FOREST;
    }

    private BooleanModelField loveteamWater;
    private IntegerModelField loveteamWaterNum;

    private BooleanModelField partnerteamWater;
    private IntegerModelField partnerteamWaterNum;
    private BooleanModelField ForestHunt;
    private BooleanModelField AutoAntForestHuntTaskList;
    private SelectModelField AntForestHuntTaskList;
    private BooleanModelField ForestHuntDraw;
    private BooleanModelField ForestHuntHelp;
    private SelectModelField ForestHuntHelpList;

    private SelectModelField continuousUseCardOptions;

    private IntegerModelField continuousUseShieldHour;
    private BooleanModelField NORMALForestHuntHelp;
    private BooleanModelField ACTIVITYForestHuntHelp;

    private BooleanModelField energyPvp;

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(collectEnergy = new BooleanModelField("collectEnergy", "收集能量", false));
        modelFields.addField(dontCollectList = new SelectModelField("dontCollectList", "不收取能量列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(batchRobEnergy = new BooleanModelField("batchRobEnergy", "一键收取", false));
        modelFields.addField(CollectSelfEnergyType = new ChoiceModelField("CollectSelfEnergyType", "收单个能量球 | " + "方式", CollectSelfType.ALL, CollectSelfType.nickNames));
        modelFields.addField(CollectSelfEnergyThreshold = new IntegerModelField("CollectSelfEnergyThreshold", "收单个能量球阈值", 0, 0, 10000));
        modelFields.addField(pkEnergy = new BooleanModelField("pkEnergy", "Pk榜收取 | 开关", false));
        modelFields.addField(energyPvp = new BooleanModelField("energyPvp", "1V1能量挑战 | 开关", false));
        modelFields.addField(collectWateringBubble = new BooleanModelField("collectWateringBubble", "收取金球", false));
        modelFields.addField(wateredFriendList = new SelectAndCountModelField("wateredFriendList", "统计 | 应被好友浇水", new LinkedHashMap<>(), AlipayUser::getList, "请填写被浇水次数(用于核对金球)"));
        modelFields.addField(collectRobExpandEnergy = new IntegerModelField("collectRobExpandEnergy", "倍卡额外能量(大于该值收取)", 100, 0, 1000000));
        modelFields.addField(expiredEnergy = new BooleanModelField("expiredEnergy", "收取过期能量", false));
        modelFields.addField(queryInterval = new StringModelField("queryInterval", "查询间隔(毫秒或毫秒范围)", "500-1000"));
        modelFields.addField(collectInterval = new StringModelField("collectInterval", "收取间隔" + "(毫秒或毫秒范围)", "1000" + "-1500"));
        modelFields.addField(doubleCollectInterval = new StringModelField("doubleCollectInterval", "双击间隔(毫秒或毫秒范围)", "50-150"));
        modelFields.addField(balanceNetworkDelay = new BooleanModelField("balanceNetworkDelay", "平衡网络延迟", true));
        modelFields.addField(advanceTime = new IntegerModelField("advanceTime", "提前时间(毫秒)", 0, Integer.MIN_VALUE, 500));
        modelFields.addField(tryCount = new IntegerModelField("tryCount", "尝试收取(次数)", 1, 0, 10));
        modelFields.addField(retryInterval = new IntegerModelField("retryInterval", "重试间隔(毫秒)", 1000, 0, 10000));
        modelFields.addField(drawGameCenterAward = new BooleanModelField("drawGameCenterAward", "森林乐园 | 游戏宝箱", true));
        modelFields.addField(CollectBombEnergyLimit = new IntegerModelField("CollectBombEnergyLimit", "单个炸弹能量大于该值收取", 0, 0, 100000));
        modelFields.addField(continuousUseCardOptions = new SelectModelField("continuousUseCardOptions", "连续兑换使用道具卡片 | 选项", new LinkedHashSet<>(), CustomOption::getContinuousUseCardOptions, "光盘行动需要先手动完成一次"));
        modelFields.addField(continuousUseShieldHour = new IntegerModelField("continuousUseShieldHour", "剩余小时数接续使用保护罩", 24, 1, 168));
        //modelFields.addField(doubleClickType = new ChoiceModelField("doubleClickType", "双击卡 | " + "自动使用", UsePropType.CLOSE, UsePropType.nickNames));
        //modelFields.addField(doubleCountLimit = new IntegerModelField("doubleCountLimit", "双击卡 | " + "使用次数", 6));
        //modelFields.addField(doubleCardTime = new ListModelField.ListJoinCommaToStringModelField("doubleCardTime", "双击卡 | 使用时间(范围)", ListUtil.newArrayList("0700" + "-0730")));
        //modelFields.addField(doubleCardConstant = new BooleanModelField("DoubleCardConstant", "双击卡 | 限时双击永动机", false));
        modelFields.addField(returnWater10 = new IntegerModelField("returnWater10", "返水 | 10克需收能量" + "(关闭:0)", 0));
        modelFields.addField(returnWater18 = new IntegerModelField("returnWater18", "返水 | 18克需收能量" + "(关闭:0)", 0));
        modelFields.addField(returnWater33 = new IntegerModelField("returnWater33", "返水 | 33克需收能量" + "(关闭:0)", 0));
        modelFields.addField(waterFriendType = new ChoiceModelField("waterFriendType", "浇水 | 动作", WaterFriendType.WATER_00, WaterFriendType.nickNames));
        modelFields.addField(waterFriendList = new SelectAndCountModelField("waterFriendList", "浇水 | 好友列表", new LinkedHashMap<>(), AlipayUser::getList, "请填写浇水次数(每日)"));
        modelFields.addField(waterFriendEnergySendChat = new BooleanModelField("waterFriendEnergySendChat", "浇水 | 发送已浇水提醒", false));
        modelFields.addField(waterFriendEnergyFirst = new BooleanModelField("waterFriendEnergyFirst", "浇水 | 每次首先执行", false));
        modelFields.addField(doubleWaterFriendEnergy = new BooleanModelField("doubleWaterFriendEnergy", "浇水 | 强制检查重复一次", false));
        modelFields.addField(helpFriendCollectType = new ChoiceModelField("helpFriendCollectType", "复活能量 | 动作", HelpFriendCollectType.NONE, HelpFriendCollectType.nickNames));
        modelFields.addField(helpFriendCollectList = new SelectModelField("helpFriendCollectList", "复活能量 | 好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(helpFriendCollectListLimit = new IntegerModelField("helpFriendCollectListLimit", "复活好友能量下限(大于该值复活)", 0, 0, 100000));
        modelFields.addField(vitalityExchangeBenefit = new BooleanModelField("vitalityExchangeBenefit", "活力值 | 兑换权益", false));
        modelFields.addField(vitality_ExchangeBenefitList = new SelectAndCountModelField("vitality_ExchangeBenefitList", "活力值 | 权益列表", new LinkedHashMap<>(), VitalityBenefit::getList, "请填写兑换次数(每日)"));
        modelFields.addField(whackModeName = new ChoiceModelField("whackModeName", "6秒拼手速 | 运行模式", whackModeNames.CLOSE, whackModeNames.nickNames));
        modelFields.addField(whackModeGames = new IntegerModelField("whackModeGames", "6秒拼手速 | 激进模式局数", 5));
        modelFields.addField(whackModeCount = new IntegerModelField("whackModeCount", "6秒拼手速 | 兼容模式击打数", 15));
        modelFields.addField(earliestwhackMoleTime = new IntegerModelField("earliestwhackMoleTime", "6秒拼手速 | 最早执行(24小时制)", 8, 0, 23));
        modelFields.addField(collectProp = new BooleanModelField("collectProp", "收集道具", false));
        modelFields.addField(whoYouWantToGiveTo = new SelectModelField("whoYouWantToGiveTo", "赠送道具好友列表", new LinkedHashSet<>(), AlipayUser::getList, "会赠送所有可送道具都给已选择的好友"));
        modelFields.addField(energyRain = new BooleanModelField("energyRain", "收集能量雨", false));
        modelFields.addField(giveEnergyRainList = new SelectModelField("giveEnergyRainList", "赠送能量雨好友列表", new LinkedHashSet<>(), AlipayUser::getList));
        modelFields.addField(useEnergyRainLimit = new BooleanModelField("useEnergyRainLimit", "兑换使用限时能量雨卡", false));
        modelFields.addField(receiveForestTaskAward = new BooleanModelField("receiveForestTaskAward", "森林任务", false));
        modelFields.addField(AutoAntForestVitalityTaskList = new BooleanModelField("AutoAntForestVitalityTaskList", "活力值 | 自动黑白名单", true));
        modelFields.addField(AntForestVitalityTaskList = new SelectModelField("AntForestVitalityTaskList", "活力值 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestVitalityTaskList::getList));
        modelFields.addField(collectGiftBox = new BooleanModelField("collectGiftBox", "领取礼盒", false));
        modelFields.addField(medicalHealth = new BooleanModelField("medicalHealth", "医疗健康", false));
        modelFields.addField(greenLife = new BooleanModelField("greenLife", "森林集市", false));
        modelFields.addField(marketBrowseTask = new BooleanModelField("marketBrowseTask", "绿植浏览", false));
        modelFields.addField(greenRent = new BooleanModelField("greenRent", "绿色租赁", false));
        modelFields.addField(youthPrivilege = new BooleanModelField("youthPrivilege", "青春特权 | 森林道具", false));
        modelFields.addField(ecoLife = new BooleanModelField("ecoLife", "绿色行动 | 开启", false));
        modelFields.addField(ecoLifeOptions = new SelectModelField("ecoLifeOptions", "绿色行动 | 选项", new LinkedHashSet<>(), CustomOption::getEcoLifeOptions, "光盘行动需要先手动完成一次"));
        modelFields.addField(partnerteamWater = new BooleanModelField("partnerteamWater", "组队合种浇水", false));
        modelFields.addField(partnerteamWaterNum = new IntegerModelField("partnerteamWaterNum", "组队合种浇水" + "(g)", 10, 10, 5000));
        modelFields.addField(loveteamWater = new BooleanModelField("loveteamWater", "真爱合种浇水", false));
        modelFields.addField(loveteamWaterNum = new IntegerModelField("loveteamWaterNum", "真爱合种浇水" + "(g)", 20, 20, 10000));
        modelFields.addField(ForestHunt = new BooleanModelField("ForestHunt", "森林寻宝", false));
        modelFields.addField(AutoAntForestHuntTaskList = new BooleanModelField("AutoAntForestHuntTaskList", "抽抽乐任务 | 自动黑白名单", true));
        modelFields.addField(AntForestHuntTaskList = new SelectModelField("AntForestHuntTaskList", "抽抽乐任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntForestHuntTaskList::getList));
        modelFields.addField(ForestHuntDraw = new BooleanModelField("ForestHuntDraw", "森林寻宝抽奖", false));
        modelFields.addField(ForestHuntHelp = new BooleanModelField("ForestHuntHelp", "森林寻宝助力", false));
        modelFields.addField(NORMALForestHuntHelp = new BooleanModelField("NORMALForestHuntHelp", "普通场景强制助力" + "(助力任务不在列表中时使用，如果日志显示失效请关闭)", false));
        modelFields.addField(ACTIVITYForestHuntHelp = new BooleanModelField("ACTIVITYForestHuntHelp", "活动场景强制助力" + "(同上)", false));
        modelFields.addField(ForestHuntHelpList = new SelectModelField("ForestHuntHelpList", "点击配置寻宝助力列表" + "(填写shareId中开头的22-24位字符在\"4O7FEYDgn\"前的)", new LinkedHashSet<>(), AlipayForestHunt::getList));
        modelFields.addField(dress = new BooleanModelField("dress", "装扮保护 | 开启", false));
        modelFields.addField(dressDetailList = new TextModelField("dressDetailList", "装扮保护 | " + "装扮信息", ""));
        modelFields.addField(new EmptyModelField("dressDetailListClear", "装扮保护 | 装扮信息清除", () -> dressDetailList.reset()));
        return modelFields;
    }

    @Override
    public Boolean check() {
        if (RuntimeInfo.getInstance().getLong(RuntimeInfo.RuntimeInfoKey.ForestPauseTime) > System.currentTimeMillis()) {
            Log.record("异常等待中，暂不执行检测！");
            return false;
        }
        return true;
    }

    @Override
    public Boolean isSync() {
        return true;
    }

    @Override
    public void boot(ClassLoader classLoader) {
        super.boot(classLoader);
        // 两个接口各自持有独立的限流实例：IntervalLimit 的锁与时间戳都挂在实例上，
        // 共用同一个实例会让首页与好友页互相抢节流窗口
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.queryHomePage",
                new FixedOrRangeIntervalLimit(queryInterval.getValue(), 10, 10000));
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.queryFriendHomePage",
                new FixedOrRangeIntervalLimit(queryInterval.getValue(), 10, 10000));
        RpcIntervalLimit.addIntervalLimit("alipay.antmember.forest.h5.collectEnergy", 0);
        RpcIntervalLimit.addIntervalLimit("alipay.antmember.forest.h5.queryEnergyRanking", 100);
        RpcIntervalLimit.addIntervalLimit("alipay.antforest.forest.h5.fillUserRobFlag", 500);
        tryCountInt = tryCount.getValue();
        retryIntervalInt = retryInterval.getValue();
        advanceTimeInt = advanceTime.getValue();
        checkIntervalInt = BaseModel.getCheckInterval().getValue();
        dontCollectMap = dontCollectList.getValue();
        collectIntervalEntity = new FixedOrRangeIntervalLimit(collectInterval.getValue(), 50, 10000);
        doubleCollectIntervalEntity = new FixedOrRangeIntervalLimit(doubleCollectInterval.getValue(), 10, 5000);
        delayTimeMath.clear();
        AntForestRpcCall.init();
    }

    @Override
    public void run() {
        try {
            Log.record("执行开始-蚂蚁森林");
            NotificationUtil.setStatusTextExec();
            taskCount.set(0);
            selfId = UserIdMap.getCurrentUid();
            hasErrorWait = false;
            wateringExecutedThisRound = false;

            //GameTask.Orchard_ncscc.report("农场上车车", 1);
            if (waterFriendEnergyFirst.getValue()) {
                waterFriendEnergy();
                wateringExecutedThisRound = true;
            }


            if (useEnergyRainLimit.getValue()) {
                useEnergyRainCard();
            }

            if (energyRain.getValue()) {
                energyRain();
            }

            if (ecoLife.getValue()) {
                ecoLife();
            }

            if (youthPrivilege.getValue()) {
                // 青春特权道具（双击卡/能量罩/加速器）改走 listTaskopengreen：
                // 原实现用的 queryTaskList 对本场景返回空 forestTasksNew，三项道具从未触发领取
                queryVitalityYouthTaskList();
            }
            //连续兑换使用道具卡片
            continuousUseCardOptions();

            if (receiveForestTaskAward.getValue()) {
                vantiepSign();
            }

            JSONObject selfHomeObject = collectSelfEnergy();
            try {
                JSONObject friendsObject = new JSONObject(AntForestRpcCall.queryEnergyRanking());
                if (MessageUtil.checkResultCode(TAG, friendsObject)) {
                    collectFriendsEnergy(friendsObject, "ordinary");
                    int pos = 20;
                    List<String> idList = new ArrayList<>();
                    JSONArray totalDatas = friendsObject.getJSONArray("totalDatas");
                    while (pos < totalDatas.length()) {
                        JSONObject friend = totalDatas.getJSONObject(pos);
                        idList.add(friend.getString("userId"));
                        pos++;
                        if (pos % 20 == 0) {
                            collectFriendsEnergy(idList, "ordinary");
                            idList.clear();
                        }
                    }
                    if (!idList.isEmpty()) {
                        collectFriendsEnergy(idList, "ordinary");
                    }
                }
                selfHomeObject = collectSelfEnergy();
            } catch (Throwable t) {
                Log.i(TAG, "queryEnergyRanking err:");
                Log.printStackTrace(TAG, t);
            }

            // PK榜收取与「自己能量」「好友榜能量」同属收能量操作，必须放在只收能量守卫之外。
            // 原先它在守卫块内，设置「只收能量时间」时会被一起跳过，与 collectSelfEnergy()
            // 和好友榜收取（均已在块外）行为不一致。
            if (pkEnergy.getValue()) {
                collectPKEnergy();
            }

            if (!TaskCommon.IS_ENERGY_TIME && selfHomeObject != null) {
                String whackMoleStatus = selfHomeObject.optString("whackMoleStatus");
                if (Objects.equals("CAN_PLAY", whackMoleStatus) || Objects.equals("CAN_INITIATIVE_PLAY", whackMoleStatus) || Objects.equals("NEED_MORE_FRIENDS", whackMoleStatus)) {
                    checkAndHandleWhackMole();
                }
                boolean hasMore = false;
                do {
                    if (hasMore) {
                        hasMore = false;
                        selfHomeObject = querySelfHome();
                    }
                    if (collectWateringBubble.getValue()) {
                        JSONArray wateringBubbles = selfHomeObject.has("wateringBubbles") ? selfHomeObject.getJSONArray("wateringBubbles") : new JSONArray();
                        if (wateringBubbles.length() > 0) {
                            for (int i = 0; i < wateringBubbles.length(); i++) {
                                // 每颗金球单独计数：原先声明在循环外，bubbles 为空时会复用上一次的值重复计入
                                int collected = 0;
                                JSONObject wateringBubble = wateringBubbles.getJSONObject(i);
                                String bizType = wateringBubble.getString("bizType");
                                String friendShowName = UserIdMap.getShowName(wateringBubble.getString("userId"));
                                switch (bizType) {
                                    case "jiaoshui": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectEnergy(bizType, selfId, wateringBubble.getLong("id")));
                                        if (MessageUtil.checkResultCode("收取[我]的浇水金球", joEnergy)) {
                                            JSONArray bubbles = joEnergy.getJSONArray("bubbles");
                                            for (int j = 0; j < bubbles.length(); j++) {
                                                collected += bubbles.getJSONObject(j).getInt("collectedEnergy");
                                            }

                                            if (collected > 0) {
                                                //记录被浇水次数
                                                Status.wateredFriendToday(wateringBubble.getString("userId"));
                                                Statistics.addData(Statistics.DataType.WATEREDCOUNT, 1);
                                                String msg = "收取金球🍯[" + friendShowName + "]的浇水[" + collected + "g]";
                                                Log.forest(msg + "#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                                Toast.show(msg);
                                                totalCollected += collected;
                                                Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                            } else {
                                                Log.record("收取[我]的浇水金球失败");
                                            }
                                        }
                                        break;
                                    }
                                    case "fuhuo": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectRebornEnergy());
                                        if (MessageUtil.checkResultCode("收取[我]的复活金球", joEnergy)) {
                                            collected = joEnergy.getInt("energy");
                                            String msg = "收取金球🍯复活[" + collected + "g]";
                                            Log.forest(msg + "#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                            Toast.show(msg);
                                            totalCollected += collected;
                                            Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                        }
                                        break;
                                    }
                                    case "baohuhuizeng": {
                                        JSONObject joEnergy = new JSONObject(AntForestRpcCall.collectEnergy(bizType, selfId, wateringBubble.getLong("id")));
                                        if (MessageUtil.checkResultCodeString("收取[" + friendShowName + "]的复活回赠金球", joEnergy)) {
                                            JSONArray bubbles = joEnergy.getJSONArray("bubbles");
                                            for (int j = 0; j < bubbles.length(); j++) {
                                                collected += bubbles.getJSONObject(j).getInt("collectedEnergy");
                                            }
                                            if (collected > 0) {
                                                String msg = "收取金球🍯[" + friendShowName + "]复活回赠[" + collected + "g]";
                                                Log.forest(msg + "#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                                Toast.show(msg);
                                                totalCollected += collected;
                                                Statistics.addData(Statistics.DataType.COLLECTED, collected);
                                            } else {
                                                Log.record("收取[" + friendShowName + "]的复活回赠金球失败");
                                            }
                                        }
                                        break;
                                    }
                                }
                                TimeUtil.sleep(1000L);
                            }
                            if (wateringBubbles.length() >= 20) {
                                hasMore = true;
                            }
                        }
                    }
                    if (collectProp.getValue()) {
                        JSONArray givenProps = selfHomeObject.has("givenProps") ? selfHomeObject.getJSONArray("givenProps") : new JSONArray();
                        if (givenProps.length() > 0) {
                            for (int i = 0; i < givenProps.length(); i++) {
                                JSONObject jo = givenProps.getJSONObject(i);
                                String giveConfigId = jo.getString("giveConfigId");
                                String giveId = jo.getString("giveId");
                                String propName = jo.getJSONObject("propConfig").getString("propName");
                                jo = new JSONObject(AntForestRpcCall.collectProp(giveConfigId, giveId));
                                if (MessageUtil.checkSuccess(TAG, jo)) {
                                    Log.forest("领取道具🎭[" + propName + "]");
                                }
                                TimeUtil.sleep(1000L);
                            }
                            if (givenProps.length() >= 20) {
                                hasMore = true;
                            }
                        }
                    }
                } while (hasMore);
                //强制重复浇水一次
                if (doubleWaterFriendEnergy.getValue()) {
                    if (!Status.hasFlagToday("Forest::doubleWaterFriendEnergy")) {
                        doubleWaterFriendEnergy();
                    }
                }

                if (!wateringExecutedThisRound) {
                    waterFriendEnergy();
                }

                // 组队合种浇水
                //if (partnerteamWater.getValue()) {
                //    if (partnerteamWaterNum.getValue() > 0 && partnerteamWaterNum.getValue() <= 5000) {
                //        partnerteamWater(partnerteamWaterNum.getValue());
                //    }
                //}

                //初始任务列表
                if (!Status.hasFlagToday("BlackList::initAntForest")) {
                    initAntForestTaskListMap(AutoAntForestVitalityTaskList.getValue(), AutoAntForestHuntTaskList.getValue(), receiveForestTaskAward.getValue(), ForestHunt.getValue());
                    Status.flagToday("BlackList::initAntForest");
                }

                // 组队合种浇水
                if (partnerteamWater.getValue()) {
                    teamCooperateWater();
                }
                // 真爱合种浇水
                if (loveteamWater.getValue()) {
                    if (loveteamWaterNum.getValue() >= 20 && loveteamWaterNum.getValue() <= 10000) {
                        loveteam(loveteamWaterNum.getValue());
                    }
                }

                // 森林寻宝
                if (ForestHunt.getValue()) {
                    ForestChouChouLe forestChouChouLe = new ForestChouChouLe();
                    forestChouChouLe.chouChouLe(ForestHuntDraw.getValue(), ForestHuntHelp.getValue(), ForestHuntHelpList.getValue(), NORMALForestHuntHelp.getValue(), ACTIVITYForestHuntHelp.getValue(), AntForestHuntTaskList.getValue());
                }

                if (expiredEnergy.getValue()) {
                    popupTask();
                }

                if (receiveForestTaskAward.getValue()) {
                    queryCommonSign();
                    queryTaskList();
                }

                giveProp();
                if (vitalityExchangeBenefit.getValue()) {
                    vitalityExchangeBenefit();
                }
                /* 森林集市 */
                if (greenLife.getValue()) {
                    greenLife();
                }

                // 绿植浏览（独立任务，与森林集市无关）
                if (marketBrowseTask.getValue()) {
                    GreenPlantBrowse.run();
                }

                // 绿色租赁
                if (greenRent.getValue()) {
                    if (!Status.hasFlagToday("Forest::greenRent")) {
                        greenRent();
                        Status.flagToday("Forest::greenRent");
                    }
                }

                if (medicalHealth.getValue()) {
                    // 医疗健康 绿色医疗 16g*6能量
                    queryForestEnergy("FEEDS");
                    // 医疗健康 电子小票 4g*10能量
                    queryForestEnergy("BILL");
                }
                if (dress.getValue()) {
                    dress();
                }

                checkAndHandleWhackMole();

                //森林乐园
                if (drawGameCenterAward.getValue()) {
                    doforestgame();
                    queryOptionalPlay();
                }

                //1V1能量挑战
                if (energyPvp.getValue()) {
                    if (updateUserConfigEnergyPvp()) {
                        queryPvpHomeInfo();
                        receivePvpRewards();
                    }

                }
                ForestEnergyInfo();

                // 倍卡额外能量放到最后收：此时本轮的能量（含异步收取的好友能量）才累积完，
                // 放在收取之前只能收到上一轮攒下的部分
                collectRobExpandEnergy();

            }
        } catch (Throwable t) {
            Log.i(TAG, "AntForestV2.run err:");
            Log.printStackTrace(TAG, t);
        } finally {
            try {
                synchronized (AntForestV2.this) {
                    int count = taskCount.get();
                    if (count > 0) {
                        AntForestV2.this.wait(TimeUnit.MINUTES.toMillis(30));
                        count = taskCount.get();
                    }
                    if (count > 0) {
                        Log.record("执行超时-蚂蚁森林");
                    } else if (count == 0) {
                        Log.record("执行结束-蚂蚁森林");
                    } else {
                        Log.record("执行完成-蚂蚁森林");
                    }
                }
            } catch (InterruptedException ie) {
                Log.i(TAG, "执行中断-蚂蚁森林");
            }
            Statistics.save();
            FriendWatch.save();
            NotificationUtil.updateLastExecText("收:" + totalCollected + " 帮:" + totalHelpCollected);
        }
    }

    private void ForestEnergyInfo() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray bubbles = jo.getJSONArray("bubbles");
            int bubblesNumber = bubbles.length();

            if (!jo.has("userBaseInfo")) {
                return;
            }
            JSONObject userBaseInfo = jo.getJSONObject("userBaseInfo");
            int currentEnergy = userBaseInfo.optInt("currentEnergy", 0);
            int totalCertCount = userBaseInfo.optInt("totalCertCount", 0);
            if (!jo.has("userVitalityInfo")) {
                return;
            }
            JSONObject userVitalityInfo = jo.getJSONObject("userVitalityInfo");
            int totalVitalityAmount = userVitalityInfo.optInt("totalVitalityAmount", 0);

            jo = new JSONObject(AntForestRpcCall.queryDynamicsIndex());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            if (!jo.has("todayEnergySummary")) {
                return;
            }
            JSONObject todayEnergySummary = jo.getJSONObject("todayEnergySummary");
            int obtainTotal = todayEnergySummary.optInt("obtainTotal", 0);
            int robbedTotal = todayEnergySummary.optInt("robbedTotal", 0);

            //获取能量日榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "day"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            JSONObject myself = jo.getJSONObject("myself");
            int dayenergySummation = myself.optInt("energySummation", 0);
            int dayrank = myself.optInt("rank", 0);

            //获取能量周榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "week"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            myself = jo.getJSONObject("myself");
            int weekenergySummation = myself.optInt("energySummation", 0);
            int weekrank = myself.optInt("rank", 0);

            //获取能量总榜top
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("energyRank", "total"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("myself")) {
                return;
            }
            myself = jo.getJSONObject("myself");
            int totalenergySummation = myself.optInt("energySummation", 0);
            int totalrank = myself.optInt("rank", 0);

            //获取偷我日榜top
            String dayenergySummationtop3 = "偷我日榜top3:";
            String userId;
            int energySummation;
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("robRank", "day"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("friendRanking")) {
                return;
            }
            JSONArray friendRankings = jo.getJSONArray("friendRanking");
            //friendRankings.length()
            for (int i = 0; i < (Math.max(friendRankings.length(), 3)); i++) {
                JSONObject friendRanking = friendRankings.getJSONObject(i);
                energySummation = friendRanking.optInt("energySummation", 0);
                if (energySummation == 0) {
                    break;
                }
                userId = friendRanking.optString("userId", null);
                dayenergySummationtop3 = dayenergySummationtop3 + "[" + UserIdMap.getShowName(userId) + "]" + energySummation + "g;";
            }

            //获取偷我周榜top
            String weekenergySummationtop3 = "偷我周榜top3:";
            jo = new JSONObject(AntForestRpcCall.queryTopEnergyRanking("robRank", "week"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("friendRanking")) {
                return;
            }
            friendRankings = jo.getJSONArray("friendRanking");
            //friendRankings.length()
            for (int i = 0; i < (Math.max(friendRankings.length(), 3)); i++) {
                JSONObject friendRanking = friendRankings.getJSONObject(i);
                energySummation = friendRanking.optInt("energySummation", 0);
                if (energySummation == 0) {
                    break;
                }
                userId = friendRanking.optString("userId", null);
                weekenergySummationtop3 = weekenergySummationtop3 + "[" + UserIdMap.getShowName(userId) + "]" + energySummation + "g;";
            }
            String ForestInfo = "森林榜单🌳[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "](" + UserIdMap.getCurrentUid() + ")收取" + obtainTotal + "g;被收" + robbedTotal + "g;能量球" + bubblesNumber + "个;活力值" + totalVitalityAmount + ";当前能量" + currentEnergy + "g;证书" + totalCertCount + ";😡" + dayenergySummationtop3 + weekenergySummationtop3 + "😁日榜第" + dayrank + "名:" + dayenergySummation + "g;周榜第" + weekrank + "名:" + weekenergySummation + "g;总榜第" + totalrank + "名:" + totalenergySummation + "g;";
            //Toast.show(ForestInfo);
            //Log.forest("");
            Log.record(ForestInfo);
            //Log.forest("");

        } catch (Throwable th) {
            Log.i(TAG, "ForestEnergyInfo err:");
            Log.printStackTrace(TAG, th);
        }

    }

    private void collectPKEnergy() {
        try {
            JSONObject pkObject = new JSONObject(AntForestRpcCall.queryTopEnergyChallengeRanking());
            if (!MessageUtil.checkResultCode(TAG + "获取PK排行榜失败:", pkObject)) {
                Log.error("获取PK排行榜失败: " + pkObject.optString("resultDesc"));
            } else {
                if (!pkObject.getString("rankMemberStatus").equals("JOIN")) {
                    Log.record("未加入PK排行榜");
                    return;
                }
                collectFriendsEnergy(pkObject, "PK");
                //继续处理靠后的PK好友
                JSONArray totalData = pkObject.optJSONArray("totalData");
                if (totalData == null || totalData.length() == 0) {
                    Log.record("PK好友排行榜为空，跳过");
                    return;
                }
                List<String> pkIdList = new ArrayList<>();
                for (int pos = 20; pos < totalData.length(); pos++) {
                    JSONObject pkFriend = totalData.getJSONObject(pos);
                    String userId = pkFriend.getString("userId");
                    if (Objects.equals(userId, selfId)) {
                        continue; //如果是自己则跳过
                    }
                    pkIdList.add(userId);
                    if (pkIdList.size() == 20) {
                        collectFriendsEnergy(pkIdList, "PK");
                        pkIdList.clear();
                    }
                }
                if (!pkIdList.isEmpty()) {
                    collectFriendsEnergy(pkIdList, "PK");
                }
                Log.record("收取PK能量完成！");
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
        }
    }

    private void notifyMain() {
        if (taskCount.decrementAndGet() < 1) {
            synchronized (AntForestV2.this) {
                AntForestV2.this.notifyAll();
            }
        }
    }

    private JSONObject querySelfHome() {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryHomePage());
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            int offsetTime = offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            Log.i("服务器时间：" + serverTime + "，本地与服务器时间差：" + offsetTime);
            //兼容组队模式
            if (isTeam(userHomeObject)) {
                JSONObject teamHomeResult = userHomeObject.optJSONObject("teamHomeResult");
                JSONObject mainMember = teamHomeResult != null ? teamHomeResult.optJSONObject("mainMember") : null;
                //取出组队模式下的selfHomeObject
                if (mainMember != null) {
                    Iterator<String> keyIterator = mainMember.keys();
                    while (keyIterator.hasNext()) {
                        String key = keyIterator.next();
                        Object value = mainMember.get(key);
                        //将道具卡详情存为一般森林主页格式，以便统一解析
                        if (key.equals("usingUserProps")) {
                            key = "usingUserPropsNew";
                        }
                        // 核心方法：put()
                        // 效果：存在该 key 则覆盖原值，不存在则新增键值对
                        userHomeObject.put(key, value);
                    }
                }
                //userHomeObject = teamHomeResult != null ? teamHomeResult.optJSONObject("mainMember") : null;
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    private JSONObject queryFriendHome(String userId) {
        JSONObject userHomeObject = null;
        try {
            long start = System.currentTimeMillis();
            userHomeObject = new JSONObject(AntForestRpcCall.queryFriendHomePage(userId));
            long end = System.currentTimeMillis();
            long serverTime = userHomeObject.getLong("now");
            int offsetTime = offsetTimeMath.nextInteger((int) ((start + end) / 2 - serverTime));
            Log.i("服务器时间：" + serverTime + "，本地与服务器时间差：" + offsetTime);
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return userHomeObject;
    }

    private JSONObject collectSelfEnergy() {
        try {
            JSONObject selfHomeObject = querySelfHome();
            if (selfHomeObject != null) {
                if (whackModeName.getValue() == whackModeNames.CLOSE) {
                    JSONObject propertiesObject = selfHomeObject.optJSONObject("properties");
                    if (propertiesObject != null) {
                        if (Objects.equals("Y", propertiesObject.optString("whackMole"))) {
                            if (com.sesame.wu.model.task.antForest.WhackMole.closeWhackMole()) {
                                Log.record("6秒拼手速关闭成功");
                            } else {
                                Log.record("6秒拼手速关闭失败");
                            }
                        }
                    }
                }
                String nextAction = selfHomeObject.optString("nextAction");
                if ("WhackMole".equalsIgnoreCase(nextAction)) {
                    Log.record("检测到6秒拼手速强制弹窗，先执行拼手速");
                    checkAndHandleWhackMole();
                }
                return collectUserEnergy(UserIdMap.getCurrentUid(), selfHomeObject, "ordinary");
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return null;
    }

    private JSONObject collectFriendEnergy(String userId, String getType) {
        if (hasErrorWait) {
            return null;
        }
        try {
            JSONObject userHomeObject = queryFriendHome(userId);
            if (userHomeObject != null) {
                return collectUserEnergy(userId, userHomeObject, getType);
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return null;
    }

    private JSONObject collectUserEnergy(String userId, JSONObject userHomeObject, String getType) {
        try {
            if (!MessageUtil.checkResultCode(TAG, userHomeObject)) {
                return userHomeObject;
            }

            long serverTime = userHomeObject.getLong("now");
            boolean isSelf = Objects.equals(userId, selfId);
            String userName;
            boolean isCollectEnergy;
            //默认收炸弹能量
            boolean isBombCollectenergy = true;
            if (getType.equals("PK")) {
                JSONObject userBaseInfo = userHomeObject.getJSONObject("userBaseInfo");
                userName = userBaseInfo.optString("displayName") + "(PK森友)";
                isCollectEnergy = true;
            } else {
                userName = UserIdMap.getMaskName(userId);
                isCollectEnergy = collectEnergy.getValue() && !dontCollectMap.contains(userId);
            }

            if (isSelf) {
                Log.record("进入[" + userName + "]的蚂蚁森林");
                updateUsingPropsEndTime(userHomeObject);
            } else {
                if (isCollectEnergy) {
                    JSONArray jaProps = userHomeObject.getJSONArray("usingUserPropsNew");
                    for (int i = 0; i < jaProps.length(); i++) {
                        JSONObject joProp = jaProps.getJSONObject(i);
                        if (Objects.equals("shield", joProp.getString("propGroup"))) {
                            if (joProp.getLong("endTime") > serverTime) {
                                Log.record("[" + userName + "]能量罩保护到[" + TimeUtil.getCommonDateS(joProp.getLong("endTime")) + "]");
                                isCollectEnergy = false;
                                JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                                for (int ii = 0; ii < jaBubbles.length(); ii++) {
                                    JSONObject canbubble = jaBubbles.getJSONObject(ii);
                                    long bubbleId = canbubble.getLong("id");
                                    switch (CollectStatus.valueOf(canbubble.getString("collectStatus"))) {
                                        case AVAILABLE:
                                            break;
                                        case WAITING:
                                            long produceTime = canbubble.getLong("produceTime");
                                            //如果保护罩不能覆盖能量成熟时间
                                            if (produceTime < joProp.getLong("endTime")) {
                                                break;
                                            }
                                            if (checkIntervalInt + checkIntervalInt / 2 > produceTime - serverTime) {
                                                if (hasChildTask(AntForestV2.getBubbleTimerTid(userId, bubbleId))) {
                                                    break;
                                                }
                                                addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                                Log.record("[" + userName + "]能量保护罩时间[" + TimeUtil.getCommonDate(joProp.getLong("endTime")) + "]#未覆盖能量球成熟时间[" + TimeUtil.getCommonDate(produceTime) + "]");
                                                Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                            } else {
                                                Log.i("用户[" + userName + "]能量成熟时间: " + TimeUtil.getCommonDate(produceTime));
                                            }
                                            break;
                                    }
                                }
                                break;
                            }
                        }
                        if (Objects.equals("energyBombCard", joProp.getString("propGroup"))) {
                            if (joProp.getLong("endTime") > serverTime) {
                                Log.record("[" + userName + "]使用了炸弹卡");
                                if (userHomeObject.has("bubbles")) {
                                    JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                                    for (int ii = 0; ii < jaBubbles.length(); ii++) {
                                        JSONObject Bombubble = jaBubbles.getJSONObject(ii);
                                        int remainEnergy = Bombubble.optInt("remainEnergy");
                                        //存在小于预设值
                                        if (remainEnergy < CollectBombEnergyLimit.getValue()) {
                                            isBombCollectenergy = false;
                                        } else {
                                            Log.record("[" + userName + "]炸弹能量[" + remainEnergy + "g]>设定值[" + CollectBombEnergyLimit.getValue() + "g]");
                                            isBombCollectenergy = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!isBombCollectenergy) {
                isCollectEnergy = false;
            }

            if (isCollectEnergy) {
                JSONArray jaBubbles = userHomeObject.getJSONArray("bubbles");
                List<Long> bubbleIdList = new ArrayList<>();
                for (int i = 0; i < jaBubbles.length(); i++) {
                    JSONObject bubble = jaBubbles.getJSONObject(i);
                    int remainEnergy = bubble.optInt("remainEnergy");
                    long bubbleId = bubble.getLong("id");
                    switch (CollectStatus.valueOf(bubble.getString("collectStatus"))) {
                        case AVAILABLE:
                            //用阈值判断单个能量球需收取情况
                            if (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) {
                                bubbleIdList.add(bubbleId);
                            } else if ((CollectSelfEnergyType.getValue() == CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= CollectSelfEnergyThreshold.getValue())) {
                                bubbleIdList.add(bubbleId);
                            } else if (((CollectSelfEnergyType.getValue() == CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= CollectSelfEnergyThreshold.getValue()))) {
                                bubbleIdList.add(bubbleId);
                            }
                            break;
                        case WAITING:
                            long produceTime = bubble.getLong("produceTime");
                            if (checkIntervalInt + checkIntervalInt / 2 > produceTime - serverTime) {
                                if (hasChildTask(AntForestV2.getBubbleTimerTid(userId, bubbleId))) {
                                    break;
                                }
                                if (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if ((CollectSelfEnergyType.getValue() == CollectSelfType.OVER_THRESHOLD) && (remainEnergy >= CollectSelfEnergyThreshold.getValue())) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                } else if (((CollectSelfEnergyType.getValue() == CollectSelfType.BELOW_THRESHOLD) && (remainEnergy <= CollectSelfEnergyThreshold.getValue()))) {
                                    addChildTask(new BubbleTimerTask(userId, bubbleId, produceTime, userName));
                                    Log.record("添加蹲点收取🪂[" + userName + "]在[" + TimeUtil.getCommonDate(produceTime) + "]执行");
                                }
                            } else {
                                Log.i("用户[" + userName + "]能量成熟时间: " + TimeUtil.getCommonDate(produceTime));
                            }
                            break;
                    }
                }
                //兼容组队模式
                JSONObject selfHomeObject = new JSONObject(AntForestRpcCall.queryHomePage());
                //不是自己或者是自己不在组队模式全收的情况
                //if (batchRobEnergy.getValue() && (!isSelf || (CollectSelfEnergyType.getValue() == CollectSelfType.ALL && !isTeam(selfHomeObject)))) {
                //不在组队模式全收的情况
                if (batchRobEnergy.getValue() && (CollectSelfEnergyType.getValue() == CollectSelfType.ALL) && !isTeam(selfHomeObject)) {
                    Iterator<Long> iterator = bubbleIdList.iterator();
                    List<Long> batchBubbleIdList = new ArrayList<>();
                    while (iterator.hasNext()) {
                        batchBubbleIdList.add(iterator.next());
                        if (batchBubbleIdList.size() >= 6) {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, batchBubbleIdList)), userName);
                            batchBubbleIdList = new ArrayList<>();
                        }
                    }
                    int size = batchBubbleIdList.size();
                    if (size > 0) {
                        if (size == 1) {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, batchBubbleIdList.get(0))), userName);
                        } else {
                            collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, batchBubbleIdList)), userName);
                        }
                    }
                } else {
                    for (Long bubbleId : bubbleIdList) {
                        collectEnergy(new CollectEnergyEntity(userId, userHomeObject, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, bubbleId)), userName);
                    }
                }
            }

            return userHomeObject;
        } catch (Throwable t) {
            Log.i(TAG, "collectUserEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        return null;
    }

    private void collectFriendsEnergy(List<String> idList, String getType) {
        try {
            if (hasErrorWait) {
                return;
            }
            collectFriendsEnergy(new JSONObject(AntForestRpcCall.fillUserRobFlag(new JSONArray(idList).toString())), getType);
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectFriendsEnergy(JSONObject friendsObject, String getType) {
        if (hasErrorWait) {
            return;
        }
        try {
            JSONArray jaFriendRanking = friendsObject.optJSONArray("friendRanking");
            if (jaFriendRanking == null) {
                return;
            }
            for (int i = 0, len = jaFriendRanking.length(); i < len; i++) {
                try {
                    JSONObject friendObject = jaFriendRanking.getJSONObject(i);
                    String userId = friendObject.getString("userId");
                    if (Objects.equals(userId, selfId)) {
                        continue;
                    }
                    JSONObject userHomeObject = null;
                    if (getType.equals("PK")) {
                        boolean collectEnergy = true;
                        if (!friendObject.optBoolean("canCollectEnergy")) {
                            long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                            if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > checkIntervalInt)) {
                                collectEnergy = false;
                            }
                        }
                        if (collectEnergy) {
                            userHomeObject = collectFriendEnergy(userId, getType);
                        }
                    } else {
                        if (collectEnergy.getValue() && !dontCollectMap.contains(userId)) {
                            boolean collectEnergy = true;
                            if (!friendObject.optBoolean("canCollectEnergy")) {
                                long canCollectLaterTime = friendObject.getLong("canCollectLaterTime");
                                if (canCollectLaterTime <= 0 || (canCollectLaterTime - System.currentTimeMillis() > checkIntervalInt)) {
                                    collectEnergy = false;
                                }
                            }
                            if (collectEnergy) {
                                userHomeObject = collectFriendEnergy(userId, getType);
                            } /* else {
                  Log.i("不收取[" + UserIdMap.getNameById(userId) + "], userId=" + userId);
              }*/
                        }

                        if (helpFriendCollectType.getValue() != HelpFriendCollectType.NONE && friendObject.optBoolean("canProtectBubble") && !Status.hasFlagToday("forest::protectBubble")) {
                            boolean isHelpCollect = helpFriendCollectList.getValue().contains(userId);
                            if (helpFriendCollectType.getValue() != HelpFriendCollectType.HELP) {
                                isHelpCollect = !isHelpCollect;
                            }
                            if (isHelpCollect) {
                                if (userHomeObject == null) {
                                    userHomeObject = queryFriendHome(userId);
                                }
                                if (userHomeObject != null) {
                                    protectFriendEnergy(userHomeObject);
                                }
                            }
                        }
                        if (collectGiftBox.getValue() && friendObject.getBoolean("canCollectGiftBox")) {
                            if (userHomeObject == null) {
                                userHomeObject = queryFriendHome(userId);
                            }
                            if (userHomeObject != null) {
                                collectGiftBox(userHomeObject);
                            }
                        }
                    }
                } catch (Exception t) {
                    Log.i(TAG, "collectFriendEnergy err:");
                    Log.printStackTrace(TAG, t);
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectGiftBox(JSONObject userHomeObject) {
        try {
            JSONObject giftBoxInfo = userHomeObject.optJSONObject("giftBoxInfo");
            JSONObject userEnergy = userHomeObject.optJSONObject("userEnergy");
            String userId = userEnergy == null ? UserIdMap.getCurrentUid() : userEnergy.optString("userId");
            if (giftBoxInfo != null) {
                JSONArray giftBoxList = giftBoxInfo.optJSONArray("giftBoxList");
                if (giftBoxList != null && giftBoxList.length() > 0) {
                    for (int ii = 0; ii < giftBoxList.length(); ii++) {
                        try {
                            JSONObject giftBox = giftBoxList.getJSONObject(ii);
                            String giftBoxId = giftBox.getString("giftBoxId");
                            String title = giftBox.getString("title");
                            JSONObject giftBoxResult = new JSONObject(AntForestRpcCall.collectFriendGiftBox(giftBoxId, userId));
                            if (!MessageUtil.checkResultCode(TAG, giftBoxResult)) {
                                continue;
                            }
                            int energy = giftBoxResult.optInt("energy", 0);
                            Log.forest("礼盒能量🎁[" + UserIdMap.getMaskName(userId) + "-" + title + "]#" + energy + "g");
                            Statistics.addData(Statistics.DataType.COLLECTED, energy);
                        } catch (Throwable t) {
                            Log.printStackTrace(t);
                            break;
                        } finally {
                            TimeUtil.sleep(500);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void protectFriendEnergy(JSONObject userHomeObject) {
        try {
            JSONArray wateringBubbles = userHomeObject.optJSONArray("wateringBubbles");
            JSONObject userEnergy = userHomeObject.optJSONObject("userEnergy");
            String userId = userEnergy == null ? UserIdMap.getCurrentUid() : userEnergy.optString("userId");
            if (wateringBubbles != null && wateringBubbles.length() > 0) {
                for (int j = 0; j < wateringBubbles.length(); j++) {
                    try {
                        JSONObject wateringBubble = wateringBubbles.getJSONObject(j);
                        if (!Objects.equals("fuhuo", wateringBubble.getString("bizType"))) {
                            continue;
                        }
                        if (wateringBubble.getJSONObject("extInfo").optInt("restTimes", 0) == 0) {
                            Status.flagToday("forest::protectBubble");
                        }
                        if (!wateringBubble.getBoolean("canProtect")) {
                            continue;
                        }
                        int fullEnergy = wateringBubble.optInt("fullEnergy", 0);
                        if (fullEnergy < helpFriendCollectListLimit.getValue()) {
                            continue;
                        }
                        JSONObject joProtect = new JSONObject(AntForestRpcCall.protectBubble(userId));
                        if (!MessageUtil.checkResultCode(TAG, joProtect)) {
                            continue;
                        }
                        int vitalityAmount = joProtect.optInt("vitalityAmount", 0);

                        String str = "复活能量🚑[" + UserIdMap.getMaskName(userId) + "-" + fullEnergy + "g]" + (vitalityAmount > 0 ? "#活力值+" + vitalityAmount : "");
                        Log.forest(str);
                        totalHelpCollected += fullEnergy;
                        Statistics.addData(Statistics.DataType.HELPED, fullEnergy);

                        break;
                    } catch (Throwable t) {
                        Log.printStackTrace(t);
                        break;
                    } finally {
                        TimeUtil.sleep(500);
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    private void collectEnergy(CollectEnergyEntity collectEnergyEntity, String username) {
        collectEnergy(collectEnergyEntity, false, username);
    }

    private void collectEnergy(CollectEnergyEntity collectEnergyEntity, Boolean joinThread, String username) {
        if (hasErrorWait) {
            return;
        }
        Runnable runnable = () -> {
            try {
                String userId = collectEnergyEntity.getUserId();
                //usePropBeforeCollectEnergy(userId);
                RpcEntity rpcEntity = collectEnergyEntity.getRpcEntity();
                boolean needDouble = collectEnergyEntity.getNeedDouble();
                boolean needRetry = collectEnergyEntity.getNeedRetry();
                int tryCount = collectEnergyEntity.addTryCount();
                int collected = 0;
                long startTime;
                synchronized (collectEnergyLockLimit) {
                    long sleep;
                    if (needDouble) {
                        collectEnergyEntity.unsetNeedDouble();
                        sleep = doubleCollectIntervalEntity.getInterval() - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    } else if (needRetry) {
                        collectEnergyEntity.unsetNeedRetry();
                        sleep = retryIntervalInt - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    } else {
                        sleep = collectIntervalEntity.getInterval() - System.currentTimeMillis() + collectEnergyLockLimit.get();
                    }
                    if (sleep > 0) {
                        TimeUtil.sleep(sleep);
                    }
                    startTime = System.currentTimeMillis();
                    collectEnergyLockLimit.setForce(startTime);
                }
                ApplicationHook.requestObject(rpcEntity, 0, 0);
                long spendTime = System.currentTimeMillis() - startTime;
                if (balanceNetworkDelay.getValue()) {
                    delayTimeMath.nextInteger((int) (spendTime / 3));
                }
                if (rpcEntity.getHasError()) {
                    String errorCode = (String) XHelpers.callMethod(rpcEntity.getResponseObject(), "getString", "error");
                    if (Objects.equals("1004", errorCode)) {
                        if (BaseModel.getWaitWhenException().getValue() > 0) {
                            long waitTime = System.currentTimeMillis() + BaseModel.getWaitWhenException().getValue();
                            RuntimeInfo.getInstance().put(RuntimeInfo.RuntimeInfoKey.ForestPauseTime, waitTime);
                            NotificationUtil.updateStatusText("异常");
                            Log.record("触发异常,等待至" + TimeUtil.getCommonDate(waitTime));
                            hasErrorWait = true;
                            return;
                        }
                        TimeUtil.sleep(600 + RandomUtil.delay());
                    }
                    if (tryCount < tryCountInt) {
                        collectEnergyEntity.setNeedRetry();
                        collectEnergy(collectEnergyEntity, username);
                    }
                    return;
                }
                JSONObject jo = new JSONObject(rpcEntity.getResponseString());
                String resultCode = jo.getString("resultCode");
                if (!"SUCCESS".equalsIgnoreCase(resultCode)) {
                    if ("PARAM_ILLEGAL2".equals(resultCode)) {
                        Log.record("[" + username + "]" + "能量已被收取,取消重试 错误:" + jo.getString("resultDesc"));
                        return;
                    }
                    Log.record("[" + username + "]" + jo.getString("resultDesc"));
                    if (tryCount < tryCountInt) {
                        collectEnergyEntity.setNeedRetry();
                        collectEnergy(collectEnergyEntity, username);
                    }
                    return;
                }

                JSONArray jaBubbles = jo.getJSONArray("bubbles");

                int jaBubbleLength = jaBubbles.length();
                if (jaBubbleLength > 1) {
                    List<Long> newBubbleIdList = new ArrayList<>();
                    for (int i = 0; i < jaBubbleLength; i++) {
                        JSONObject bubble = jaBubbles.getJSONObject(i);
                        if (bubble.getBoolean("canBeRobbedAgain")) {
                            newBubbleIdList.add(bubble.getLong("id"));
                        }
                        collected += bubble.getInt("collectedEnergy");
                    }
                    if (collected > 0) {
                        FriendWatch.friendWatch(userId, collected);
                        String str;
                        if (jo.has("bombCardEffect")) {
                            JSONObject bombCardEffect = jo.getJSONObject("bombCardEffect");
                            int explodeEnergy = bombCardEffect.optInt("explodeEnergy", 0);
                            str = "一键收取🪂[" + username + "]#" + collected + "g被炸" + explodeEnergy + "g";
                        } else {
                            str = "一键收取🪂[" + username + "]#" + collected + "g";
                        }
                        if (needDouble) {
                            Log.forest(str + "耗时[" + spendTime + "]ms[双击]");
                            Toast.show(str + "[双击]");
                        } else {
                            Log.forest(str + "耗时[" + spendTime + "]ms");
                            Toast.show(str);
                        }
                        totalCollected += collected;
                        Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    } else {
                        Log.record("一键收取[" + username + "]的能量失败" + " " + "，UserID：" + userId + "，BubbleId：" + newBubbleIdList);
                    }
                    if (!newBubbleIdList.isEmpty()) {
                        collectEnergyEntity.setRpcEntity(AntForestRpcCall.getCollectBatchEnergyRpcEntity(userId, newBubbleIdList));
                        collectEnergyEntity.setNeedDouble();
                        collectEnergyEntity.resetTryCount();
                        collectEnergy(collectEnergyEntity, username);
                    }
                } else if (jaBubbleLength == 1) {
                    JSONObject bubble = jaBubbles.getJSONObject(0);
                    collected += bubble.getInt("collectedEnergy");
                    FriendWatch.friendWatch(userId, collected);
                    if (collected > 0) {
                        String str;
                        if (jo.has("bombCardEffect")) {
                            JSONObject bombCardEffect = jo.getJSONObject("bombCardEffect");
                            int explodeEnergy = bombCardEffect.optInt("explodeEnergy", 0);
                            str = "收取能量🪂[" + username + "]#" + collected + "g被炸" + explodeEnergy + "g";
                        } else {
                            str = "收取能量🪂[" + username + "]#" + collected + "g";
                        }

                        if (needDouble) {
                            Log.forest(str + "耗时[" + spendTime + "]ms[双击]");
                            Toast.show(str + "[双击]");
                        } else {
                            Log.forest(str + "耗时[" + spendTime + "]ms");
                            Toast.show(str);
                        }
                        totalCollected += collected;
                        Statistics.addData(Statistics.DataType.COLLECTED, collected);
                    } else {
                        Log.record("收取[" + username + "]的能量失败");
                        Log.i("，UserID：" + userId + "，BubbleId：" + bubble.getLong("id"));
                    }
                    if (bubble.getBoolean("canBeRobbedAgain")) {
                        collectEnergyEntity.setNeedDouble();
                        collectEnergyEntity.resetTryCount();
                        collectEnergy(collectEnergyEntity, username);
                        return;
                    }
                    JSONObject userHome = collectEnergyEntity.getUserHome();
                    if (userHome == null) {
                        return;
                    }
                    String bizNo = userHome.optString("bizNo");
                    if (bizNo.isEmpty()) {
                        return;
                    }
                    int returnCount = 0;
                    if (returnWater33.getValue() > 0 && collected >= returnWater33.getValue()) {
                        returnCount = 33;
                    } else if (returnWater18.getValue() > 0 && collected >= returnWater18.getValue()) {
                        returnCount = 18;
                    } else if (returnWater10.getValue() > 0 && collected >= returnWater10.getValue()) {
                        returnCount = 10;
                    }
                    if (returnCount > 0) {
                        returnFriendWater(userId, bizNo, 1, returnCount);
                    }
                }
            } catch (Exception e) {
                Log.i("collectEnergy err:");
                Log.printStackTrace(e);
            } finally {
                Statistics.save();
                NotificationUtil.updateLastExecText("收:" + totalCollected + " 帮:" + totalHelpCollected);
                notifyMain();
            }
        };
        taskCount.incrementAndGet();
        if (joinThread) {
            runnable.run();
        } else {
            addChildTask(new ChildModelTask("CE|" + collectEnergyEntity.getUserId() + "|" + runnable.hashCode(), "CE", runnable));
        }
    }

    private void updateUsingPropsEndTime() throws JSONException {
        JSONObject joHomePage = new JSONObject(AntForestRpcCall.queryHomePage());
        TimeUtil.sleep(100);
        updateUsingPropsEndTime(joHomePage);
    }

    private void updateUsingPropsEndTime(JSONObject joHomePage) {
        try {
            // 组队版首页把在用道具放在 usingUserProps（个人版在 loginUserUsingPropNew），三个字段都取。
            // 用 optJSONArray 而非 getJSONArray：字段缺失时不应抛异常中断整个首页处理
            JSONArray ja = joHomePage.optJSONArray("loginUserUsingPropNew");
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserPropsNew");
            }
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserProps");
            }
            if (ja == null) {
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                usingProps.put(jo.getString("propGroup"), jo.getLong("endTime"));
            }
            forestExtensions();
        } catch (Throwable th) {
            Log.i(TAG, "updateUsingPropsEndTime err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 收取倍卡额外能量（本轮收尾调用）。倍卡额度是本轮收取行为累积出来的，
     * 先收只能收到上一轮攒下的部分，本轮领到的要等到下一轮；且好友能量是异步收取，
     * 必须等子任务跑完再查，否则查到的仍是未累积的旧值。
     */
    private void collectRobExpandEnergy() {
        try {
            JSONObject joHomePage = new JSONObject(AntForestRpcCall.queryHomePage());
            if (MessageUtil.checkResultCode(TAG, joHomePage)) {
                collectRobExpandEnergy(joHomePage);
            }
        } catch (Throwable th) {
            Log.i(TAG, "collectRobExpandEnergy err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /** 从首页响应里取出在用的倍卡并收取 */
    private void collectRobExpandEnergy(JSONObject joHomePage) {
        try {
            // 组队版首页把在用道具放在 usingUserProps（个人版在 loginUserUsingPropNew），三个字段都取
            JSONArray ja = joHomePage.optJSONArray("loginUserUsingPropNew");
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserPropsNew");
            }
            if (ja == null || ja.length() == 0) {
                ja = joHomePage.optJSONArray("usingUserProps");
            }
            if (ja == null) {
                return;
            }
            for (int i = 0; i < ja.length(); i++) {
                JSONObject jo = ja.getJSONObject(i);
                if (PropGroup.robExpandCard.name().equals(jo.getString("propGroup"))) {
                    collectRobExpandEnergy(jo.optString("extInfo"), jo.getString("propId"), jo.getString("propType"));
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "collectRobExpandEnergy err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void collectRobExpandEnergy(String extInfo, String propId, String propType) {
        if (extInfo.isEmpty()) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(extInfo);
            double leftEnergy = Double.parseDouble(jo.optString("leftEnergy", "0"));
            if (leftEnergy > collectRobExpandEnergy.getValue() || (Objects.equals(jo.optString("overLimitToday", "false"), "true") && leftEnergy > 0)) {
                collectRobExpandEnergy(propId, propType);
            }
        } catch (Throwable th) {
            Log.i(TAG, "collectRobExpandEnergy err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void collectRobExpandEnergy(String propId, String propType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.collectRobExpandEnergy(propId, propType));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                int collectEnergy = jo.optInt("collectEnergy");
                Log.forest("额外能量🎄收取[" + collectEnergy + "g]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                totalCollected += collectEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectEnergy);
            }
        } catch (Throwable th) {
            Log.i(TAG, "collectRobExpandEnergy err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void queryForestEnergy(String scene) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryForestEnergy(scene));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data").getJSONObject("response");
            JSONArray ja = jo.getJSONArray("energyGeneratedList");
            if (ja.length() > 0) {
                harvestForestEnergy(scene, ja);
            }
            int remainBubble = jo.optInt("remainBubble");
            for (int i = 0; i < remainBubble; i++) {
                ja = produceForestEnergy(scene);
                if (ja.length() == 0 || !harvestForestEnergy(scene, ja)) {
                    return;
                }
                TimeUtil.sleep(1000);
            }
        } catch (Throwable th) {
            Log.i(TAG, "queryForestEnergy err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private JSONArray produceForestEnergy(String scene) {
        JSONArray energyGeneratedList = new JSONArray();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.produceForestEnergy(scene));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                jo = jo.getJSONObject("data").getJSONObject("response");
                energyGeneratedList = jo.getJSONArray("energyGeneratedList");
                if (energyGeneratedList.length() > 0) {
                    String title = scene.equals("FEEDS") ? "绿色医疗" : "电子小票";
                    int cumulativeEnergy = jo.getInt("cumulativeEnergy");
                    Log.forest("医疗健康🚑完成[" + title + "]#产生[" + cumulativeEnergy + "g能量]");
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "produceForestEnergy err:");
            Log.printStackTrace(TAG, th);
        }
        return energyGeneratedList;
    }

    private Boolean harvestForestEnergy(String scene, JSONArray bubbles) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.harvestForestEnergy(scene, bubbles));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            jo = jo.getJSONObject("data").getJSONObject("response");
            int collectedEnergy = jo.getInt("collectedEnergy");
            if (collectedEnergy > 0) {
                String title = scene.equals("FEEDS") ? "绿色医疗" : "电子小票";
                Log.forest("医疗健康🚑收取[" + title + "]#获得[" + collectedEnergy + "g能量]");
                totalCollected += collectedEnergy;
                Statistics.addData(Statistics.DataType.COLLECTED, collectedEnergy);
                return true;
            }
        } catch (Throwable th) {
            Log.i(TAG, "harvestForestEnergy err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    /**
     * 检查并处理6秒拼手速逻辑（每天主动执行一次）
     */
    private void whackMole() {
        try {
            if (whackModeName.getValue() == whackModeNames.CLOSE) {
                // 检查今天是否已执行过打地鼠
                if (Status.hasFlagToday("forest::whackMole::executed")) {
                    Log.record("⏭️ 今天已完成过6秒拼手速，跳过执行");
                } else {
                    // 主动执行打地鼠（今日首次）
                    Log.record("🎮 开始执行6秒拼手速（今日首次）");
                    checkAndHandleWhackMole();
                    Status.flagToday("forest::whackMole::executed");
                    Log.record("✅ 6秒拼手速已完成，今天不再执行");
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "whackMole err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void checkAndHandleWhackMole() {
        try {
            // 获取当前选择的索引 (0, 1, 或 2)
            int modeIndex = (whackModeName != null) ? whackModeName.getValue() : 0;

            // 如果索引为 0 (关闭)，直接返回
            if (modeIndex == 0) {
                return;
            }

            // 检查执行时间
            int hour = Integer.parseInt(Log.getFormatTime().split(":")[0]);
            if (hour >= earliestwhackMoleTime.getValue()) {
                String whackMoleFlag = "forest::whackMole::executed";
                if (Status.hasFlagToday(whackMoleFlag)) {
                    return;
                }

                // 根据索引匹配模式
                switch (modeIndex) {
                    case 1: // 兼容模式
                        Log.record("触发任务🎮拼手速:兼容模式");
                        WhackMole.setTotalGames(1);
                        int defaultMoleCount = (whackModeCount != null) ? whackModeCount.getValue() : 15;
                        WhackMole.setMoleCount(defaultMoleCount);
                        WhackMole.start(WhackMole.Mode.COMPATIBLE);
                        break;

                    case 2: // 激进模式
                        Log.record("触发任务🎮拼手速:激进模式");
                        int configGames = (whackModeGames != null) ? whackModeGames.getValue() : 5;
                        WhackMole.setTotalGames(configGames);
                        WhackMole.start(WhackMole.Mode.AGGRESSIVE);
                        break;
                }
            }
        } catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    public void initAntForestTaskListMap(boolean AutoAntForestVitalityTaskList, boolean AutoAntForestHuntTaskList, boolean receiveForestTaskAward, boolean ForestHunt) {
        try {

            //初始化AntForestVitalityTaskListMap
            AntForestVitalityTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 预置黑名单集中登记在 TaskBlacklist（游戏/浏览类 2 条已释放）
            Set<String> blackList = TaskBlacklist.presetBlackList("AntForestV2", "AntForestVitalityTaskList");

            // 可继续添加更多黑名单任务

            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            whiteList.add("逛农场得落叶肥料");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntForestVitalityTaskListMap.add(task, task);
            }

            if (receiveForestTaskAward) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray forestTasksNew = jo.optJSONArray("forestTasksNew");
                    if (forestTasksNew != null && forestTasksNew.length() != 0) {
                        for (int i = 0; i < forestTasksNew.length(); i++) {
                            JSONObject forestTask = forestTasksNew.getJSONObject(i);
                            JSONArray taskInfoList = forestTask.optJSONArray("taskInfoList");
                            if (taskInfoList != null && taskInfoList.length() != 0) {
                                for (int j = 0; j < taskInfoList.length(); j++) {
                                    JSONObject taskInfo = taskInfoList.getJSONObject(j);
                                    JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                                    JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                                    String taskType = taskBaseInfo.getString("taskType");
                                    String taskTitle = bizInfo.optString("taskTitle", taskType);
                                    AntForestVitalityTaskListMap.add(taskTitle, taskTitle);
                                }
                            }
                        }
                    }
                }

                jo = new JSONObject(AntForestRpcCall.listTaskopengreen());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    // 添加安全的空值判断
                    if (jo.has("taskInfoList")) {
                        JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                        if (taskInfoList != null && taskInfoList.length() != 0) {
                            for (int j = 0; j < taskInfoList.length(); j++) {
                                JSONObject taskInfo = taskInfoList.getJSONObject(j);
                                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                                String taskType = taskBaseInfo.getString("taskType");
                                String taskTitle = bizInfo.optString("taskTitle", taskType);
                                AntForestVitalityTaskListMap.add(taskTitle, taskTitle);
                            }
                        }
                    }
                }


                //保存任务到配置文件
                AntForestVitalityTaskListMap.save();
                Log.record("同步任务🉑森林活力值任务列表");

                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntForestVitalityTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntForestV2 = config.getModelFieldsMap().get("AntForestV2");
                    SelectModelField AntForestVitalityTaskList = (SelectModelField) AntForestV2.get("AntForestVitalityTaskList");
                    if (AntForestVitalityTaskList == null) {
                        return;
                    }

                    // 2. 批量添加黑名单任务（确保存在）
                    Set<String> currentValues = AntForestVitalityTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {
                        for (String task : blackList) {
                            if (!currentValues.contains(task)) {
                                AntForestVitalityTaskList.add(task, 0);
                            }
                        }

                        MessageUtil.registerTaskWhiteList("AntForestVitalityTaskList", whiteList);
                    }
                    // 4. 保存配置
                    if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                        Log.record("黑白名单🈲森林活力值任务自动设置: " + AntForestVitalityTaskList.getValue());
                    } else {
                        Log.record("森林活力值任务黑白名单设置失败");
                    }
                }
            }

            //初始化AntForestHuntTaskListMap
            AntForestHuntTaskListMap.load();
            // 1. 定义黑名单（需要添加的任务）和白名单（需要移除的任务）
            // 预置黑名单集中登记在 TaskBlacklist（原 2 条游戏/开宝箱类已全部释放）
            blackList = TaskBlacklist.presetBlackList("AntForestV2", "AntForestHuntTaskList");

            whiteList = new HashSet<>();// 从黑名单中移除该任务
            whiteList.add("消耗活力值得机会");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntForestHuntTaskListMap.add(task, task);
            }

            if (ForestHunt) {
                JSONObject resData = new JSONObject(AntForestRpcCall.enterDrawActivityopengreen("", "ANTFOREST_NORMAL_DRAW", "task_entry"));
                if (MessageUtil.checkSuccess(TAG, resData)) {
                    JSONArray drawSceneGroups = resData.getJSONArray("drawSceneGroups");
                    for (int i = 0; i < drawSceneGroups.length(); i++) {
                        JSONObject drawScene = drawSceneGroups.getJSONObject(i);
                        JSONObject drawActivity = drawScene.getJSONObject("drawActivity");
                        String sceneCode = drawActivity.getString("sceneCode");
                        JSONObject listTaskopengreen = new JSONObject(AntForestRpcCall.listTaskopengreen(sceneCode + "_TASK", "task_entry"));
                        if (MessageUtil.checkSuccess(TAG, listTaskopengreen)) {
                            JSONArray taskList = listTaskopengreen.getJSONArray("taskInfoList");
                            for (int j = 0; j < taskList.length(); j++) {
                                JSONObject taskInfo = taskList.getJSONObject(j);
                                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                                String taskName = bizInfo.getString("title");
                                AntForestHuntTaskListMap.add(taskName, taskName);
                            }
                        }
                    }
                }
                AntForestHuntTaskListMap.save();
                Log.record("同步任务🉑森林抽抽乐任务列表");
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntForestHuntTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields AntForestV2 = config.getModelFieldsMap().get("AntForestV2");
                    SelectModelField AntForestHuntTaskList = (SelectModelField) AntForestV2.get("AntForestHuntTaskList");
                    if (AntForestHuntTaskList == null) {
                        return;
                    }

                    // 2. 批量添加黑名单任务（确保存在）
                    Set<String> currentValues = AntForestHuntTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {
                        for (String task : blackList) {
                            if (!currentValues.contains(task)) {
                                AntForestHuntTaskList.add(task, 0);
                            }
                        }

                        MessageUtil.registerTaskWhiteList("AntForestHuntTaskList", whiteList);
                    }
                    // 4. 保存配置
                    if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                        Log.record("黑白名单🈲森林抽抽乐任务自动设置: " + AntForestHuntTaskList.getValue());
                    } else {
                        Log.record("森林抽抽乐任务黑白名单设置失败");
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "initAntForestTaskListMap err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /* 森林集市 */
    private static void greenLife() {
        sendEnergyByAction("GREEN_LIFE");
        //sendEnergyByAction("ANTFOREST");
        retrieveCurrentActivity();
    }

    // 绿色租赁
    private static void greenRent() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.checkUserSecondSceneChance());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            // 本轮不发能量时再请求生成会被服务端拒（SYSTEM_ERROR）：先看机会字段，直接跳过
            JSONObject chance = jo.optJSONObject("resultObject");
            if (chance != null && !chance.optBoolean("canSendEnergy", true)) {
                Log.i(TAG, "绿色租赁⏭️本轮无可发能量，跳过");
                return;
            }
            TimeUtil.sleep(200);
            jo = new JSONObject(AntForestRpcCall.generateEnergy());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }

            JSONObject resultObject = jo.getJSONObject("resultObject");
            JSONObject energyGenerated = resultObject.getJSONObject("energyGenerated");
            int zulinshangpinliulan = energyGenerated.getInt("zulinshangpinliulan");
            Log.forest("绿色租赁🛍️完成[线上逛街]#产生[" + zulinshangpinliulan + "g能量]");
            Toast.show("绿色租赁🛍️完成[线上逛街]#产生[" + zulinshangpinliulan + "g能量]");
            if (zulinshangpinliulan > 0) {
                totalCollected += zulinshangpinliulan;
                Statistics.addData(Statistics.DataType.COLLECTED, zulinshangpinliulan);
            }
        } catch (Throwable t) {
            Log.i(TAG, "greenRent err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private static void retrieveCurrentActivity() {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.retrieveCurrentActivity());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }

            jo = jo.getJSONObject("data");
            if (!jo.has("currentActivity")) {
                return;
            }
            JSONObject currentActivity = jo.getJSONObject("currentActivity");
            int numberOfDaysCompleted = currentActivity.getInt("numberOfDaysCompleted") + 1;
            JSONObject currentTask = jo.getJSONObject("currentTask");
            if (currentTask.getBoolean("checkInCompleted")) {
                return;
            }
            String taskTemplateId = currentTask.getString("taskTemplateId");
            // 走统一策略：完成后按列表核对（checkInCompleted），响应不可信；
            // 定位键用 taskTemplateId（同一活动内稳定且唯一），与 Site 的 bizKey 一致
            TaskAttemptPolicy.handle("forest::greenlife::" + taskTemplateId, "森林集市打卡", null,
                    () -> attemptGreenLifeCheckIn(taskTemplateId, numberOfDaysCompleted), Log::forest,
                    new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林集市打卡",
                            taskTemplateId, null, (k) -> probeGreenLifeCheckIn(taskTemplateId))
                            .withScene("ANTFOREST"));
        } catch (Throwable t) {
            Log.i(TAG, "retrieveCurrentActivity err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 森林集市打卡上报：{@code finishCurrentTask}。
     * <p>本模块的 RPC 不带任务场景码，而伪申报兜底（{@code TaskAlternative.hit}）要求场景码非空，
     * 故这里没有 {@link Outcome#UNSUPPORTED} 分支——判据恒不成立，不做无意义的兜底。
     */
    private static Outcome attemptGreenLifeCheckIn(String taskTemplateId, int numberOfDaysCompleted) {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.finishCurrentTask(taskTemplateId));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                JSONObject data = jo.optJSONObject("data");
                JSONArray ja = data == null ? null : data.optJSONArray("prizes");
                StringBuilder award = new StringBuilder();
                for (int i = 0; ja != null && i < ja.length(); i++) {
                    JSONObject prize = ja.optJSONObject(i);
                    if (prize == null) {
                        continue;
                    }
                    if (award.length() > 0) {
                        award.append(";");
                    }
                    award.append(prize.optString("name"));
                }
                Log.forest("森林集市🛍️打卡[坚持" + numberOfDaysCompleted + "天]"
                        + (award.length() > 0 ? "#获得[" + award + "]" : ""));
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.i(TAG, "attemptGreenLifeCheckIn err:");
            Log.printStackTrace(TAG, t);
        }
        return Outcome.RETRY;
    }

    /** 森林集市打卡探针：重拉活动信息，读 {@code currentTask.checkInCompleted}。 */
    private static TaskAttemptPolicy.ProbeResult probeGreenLifeCheckIn(String taskTemplateId) {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.retrieveCurrentActivity());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return TaskAttemptPolicy.ProbeResult.UNKNOWN;
            }
            JSONObject data = jo.optJSONObject("data");
            JSONObject currentTask = data == null ? null : data.optJSONObject("currentTask");
            if (currentTask == null) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            if (!taskTemplateId.equals(currentTask.optString("taskTemplateId"))) {
                return TaskAttemptPolicy.ProbeResult.GONE;
            }
            return currentTask.optBoolean("checkInCompleted", false)
                    ? TaskAttemptPolicy.ProbeResult.RECEIVED : TaskAttemptPolicy.ProbeResult.TODO;
        } catch (Throwable t) {
            Log.i(TAG, "probeGreenLifeCheckIn err:");
            Log.printStackTrace(TAG, t);
            return TaskAttemptPolicy.ProbeResult.UNKNOWN;
        }
    }

    private static void sendEnergyByAction(String sourceType) {
        try {
            JSONObject jo = new JSONObject(GreenLifeRpcCall.consultForSendEnergyByAction(sourceType));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.optBoolean("canSendEnergy", false)) {
                return;
            }
            // 集市逛街能量：服务端一轮只发一份，需循环问到「拿到能量」或「额度已达上限」为止。
            // 原先只问一次——首次 send 返回 0 能量（服务端异步未落状态）时当轮就白跑了。
            // 加轮次上限，防服务端恒回可发但不产生能量时死循环
            for (int round = 0; round < MAX_MARKET_ENERGY_ROUNDS; round++) {
                jo = new JSONObject(GreenLifeRpcCall.sendEnergyByAction(sourceType));
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    TimeUtil.sleep(1000);
                    continue;
                }
                data = jo.getJSONObject("data");
                int receivedEnergyAmount = data.optInt("receivedEnergyAmount", 0);
                if (receivedEnergyAmount > 0) {
                    Log.forest("森林集市🛍️完成[线上逛街]#产生[" + receivedEnergyAmount + "g能量]");
                    Toast.show("森林集市🛍️完成[线上逛街]#产生[" + receivedEnergyAmount + "g能量]");
                    totalCollected += receivedEnergyAmount;
                    Statistics.addData(Statistics.DataType.COLLECTED, receivedEnergyAmount);
                    return;
                }
                TimeUtil.sleep(1000);
            }
        } catch (Throwable t) {
            Log.i(TAG, "sendEnergyByAction err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void popupTask() {
        try {
            JSONObject resData = new JSONObject(AntForestRpcCall.popupTask());
            if (!MessageUtil.checkResultCode(TAG, resData)) {
                return;
            }
            JSONArray forestSignVOList = resData.optJSONArray("forestSignVOList");
            if (forestSignVOList != null) {
                for (int i = 0; i < forestSignVOList.length(); i++) {
                    JSONObject forestSignVO = forestSignVOList.getJSONObject(i);
                    String signId = forestSignVO.getString("signId");
                    String currentSignKey = forestSignVO.getString("currentSignKey");
                    JSONArray signRecords = forestSignVO.getJSONArray("signRecords");
                    for (int j = 0; j < signRecords.length(); j++) {
                        JSONObject signRecord = signRecords.getJSONObject(j);
                        String signKey = signRecord.getString("signKey");
                        if (signKey.equals(currentSignKey)) {
                            if (!signRecord.getBoolean("signed")) {
                                JSONObject resData2 = new JSONObject(AntForestRpcCall.antiepSign(signId, "ANTFOREST_ENERGY_SIGN", UserIdMap.getCurrentUid()));
                                if (MessageUtil.checkSuccess(TAG, resData2)) {
                                    int expiredEnergy = signRecord.getInt("awardCount");
                                    Log.forest("过期能量💊[" + expiredEnergy + "g]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, expiredEnergy);
                                }
                            }
                            break;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "popupTask err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void waterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = WaterFriendType.waterEnergy[waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        Map<String, Integer> friendMap = waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (selfId.equals(uid)) {
                continue;
            }
            Integer waterCount = friendEntry.getValue();
            if (waterCount == null || waterCount <= 0) {
                continue;
            }
            if (waterCount > 3) {
                waterCount = 3;
            }
            if (Status.canWaterFriendToday(uid, waterCount)) {
                try {
                    JSONObject jo = new JSONObject(AntForestRpcCall.queryFriendHomePage(uid));
                    TimeUtil.sleep(100);
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        String bizNo = jo.getString("bizNo");
                        KVNode<Integer, Boolean> waterCountKVNode = returnFriendWater(uid, bizNo, waterCount, waterEnergy);
                        waterCount = waterCountKVNode.getKey();
                        if (waterCount > 0) {
                            Status.waterFriendToday(uid, waterCount, taskUid);
                        }
                        if (!waterCountKVNode.getValue()) {
                            break;
                        }
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "waterFriendEnergy err:");
                    Log.printStackTrace(TAG, t);
                }
            }
        }
    }

    private KVNode<Integer, Boolean> returnFriendWater(String userId, String bizNo, int count, int waterEnergy) {
        if (bizNo == null || bizNo.isEmpty()) {
            return new KVNode<>(0, true);
        }
        int wateredTimes = 0;
        boolean isContinue = true;
        try {
            String s;
            JSONObject jo;
            int energyId = getEnergyId(waterEnergy);
            label:
            for (int waterCount = 1; waterCount <= count; waterCount++) {
                s = AntForestRpcCall.transferEnergy(userId, bizNo, energyId, waterFriendEnergySendChat.getValue() ? "Y" : "N");
                TimeUtil.sleep(1500);
                jo = new JSONObject(s);

                String resultCode = jo.getString("resultCode");
                switch (resultCode) {
                    case "SUCCESS":
                        //记录浇水次数
                        Status.wateringFriendToday(userId);
                        Statistics.addData(Statistics.DataType.WATERINGCOUNT, 1);
                        int currentEnergy = jo.getJSONObject("userBaseInfo").getInt("currentEnergy");
                        Log.forest("好友浇水🚿给[" + UserIdMap.getShowName(userId) + "]浇" + waterEnergy + "g#剩余能量[" + currentEnergy + "g]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                        Toast.show("好友浇水🚿给[" + UserIdMap.getShowName(userId) + "]浇" + waterEnergy + "g");
                        wateredTimes++;
                        Statistics.addData(Statistics.DataType.WATERED, waterEnergy);
                        break;
                    case "WATERING_TIMES_LIMIT":
                        Log.record("好友浇水🚿今日给[" + UserIdMap.getMaskName(userId) + "]浇水已达上限");
                        wateredTimes = 3;
                        break label;
                    case "WATERING_USER_LIMIT":
                        Log.record("好友浇水🚿给[" + UserIdMap.getMaskName(userId) + "]浇水，" + jo.getString("resultDesc"));
                        wateredTimes = 3;
                        break label;
                    default:
                        Log.record("好友浇水🚿" + jo.getString("resultDesc"));
                        Log.i(jo.toString());
                        break;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "returnFriendWater err:");
            Log.printStackTrace(TAG, t);
        }
        return new KVNode<>(wateredTimes, isContinue);
    }

    private int getEnergyId(int waterEnergy) {
        if (waterEnergy <= 0) {
            return 0;
        }
        if (waterEnergy >= 66) {
            return 42;
        }
        if (waterEnergy >= 33) {
            return 41;
        }
        if (waterEnergy >= 18) {
            return 40;
        }
        return 39;
    }

    private void doubleWaterFriendEnergy() {
        String taskUid = UserIdMap.getCurrentUid();
        int waterEnergy = WaterFriendType.waterEnergy[waterFriendType.getValue()];
        if (waterEnergy == 0) {
            return;
        }
        boolean reSet = true;
        Map<String, Integer> friendMap = waterFriendList.getValue();
        for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
            String uid = friendEntry.getKey();
            if (selfId.equals(uid)) {
                continue;
            }
            Integer waterCount = friendEntry.getValue();
            if (waterCount == null || waterCount <= 0) {
                continue;
            }
            if (Status.canWaterFriendToday(uid, 3)) {
                reSet = false;
            }
        }
        if (reSet) {
            for (Map.Entry<String, Integer> friendEntry : friendMap.entrySet()) {
                String uid = friendEntry.getKey();
                if (selfId.equals(uid)) {
                    continue;
                }
                Integer waterCount = friendEntry.getValue();
                if (waterCount == null || waterCount <= 0) {
                    continue;
                }
                //重置浇水次数
                Status.waterFriendToday(uid, 0, taskUid);
            }
            Log.record("好友浇水🚿今日给好友浇水状态已重置！");
            Status.flagToday("Forest::doubleWaterFriendEnergy");
        }
    }

    private void forestExtensions() {
        try {
            ExtensionsHandle.handleAlphaRequest("antForest", "extensions", usingProps);
        } catch (Throwable t) {
            Log.i(TAG, "forestExtensions err:");
            Log.printStackTrace(TAG, t);
        }
    }

    // skuId, sku
    Map<String, JSONObject> skuInfo = new HashMap<>();

    private void vitalityExchangeBenefit() {
        try {
            getAllSkuInfo();
            Map<String, Integer> exchangeList = vitality_ExchangeBenefitList.getValue();
            for (Map.Entry<String, Integer> entry : exchangeList.entrySet()) {
                String skuId = entry.getKey();
                Integer count = entry.getValue();
                if (count == null || count < 0) {
                    continue;
                }
                // 失效 skuId（权益下架/换号/服务端改 ID）当日已判过就跳过，避免每轮刷屏并拖累后续任务
                if (Status.hasFlagToday("forest::exchangeSkuMissing::" + skuId)) {
                    continue;
                }
                // 每日次数之外再加硬循环上限：即便出现意外也不会空转
                int loopCount = 0;
                while (loopCount++ < count
                        && Status.canVitalityExchangeBenefitToday(skuId, count)
                        && exchangeBenefit(skuId)) {
                    TimeUtil.sleep(3000);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "vitalityExchangeBenefit err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void vantiepSign() {
        if (Status.hasFlagToday("forest::vantiepSign")) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray forestSignVOList = jo.getJSONArray("forestSignVOList");
            JSONObject forestSignVO = forestSignVOList.getJSONObject(0);
            String currentSignKey = forestSignVO.getString("currentSignKey"); // 当前签到的 key
            String signId = forestSignVO.getString("signId"); // 签到ID
            String sceneCode = forestSignVO.getString("sceneCode"); // 场景代码
            JSONArray signRecords = forestSignVO.getJSONArray("signRecords"); // 签到记录
            for (int i = 0; i < signRecords.length(); i++) { // 遍历签到记录
                JSONObject signRecord = signRecords.getJSONObject(i);
                String signKey = signRecord.getString("signKey");
                int awardCount = signRecord.getInt("awardCount");
                if (signKey.equals(currentSignKey) && signRecord.getBoolean("signed")) {
                    // 服务端回读：本期 currentSignKey 已签 → 落当日标记
                    Status.flagToday("forest::vantiepSign");
                    break;
                }
                if (signKey.equals(currentSignKey) && !signRecord.getBoolean("signed")) {
                    JSONObject joSign = new JSONObject(AntForestRpcCall.antiepSign(signId, UserIdMap.getCurrentUid(), sceneCode));
                    TimeUtil.sleep(300); // 等待300毫秒
                    if (MessageUtil.checkSuccess(TAG + "森林签到失败:", joSign)) {
                        int continuousCount = joSign.getInt("continuousCount");
                        Log.forest("森林签到📆拯救第" + continuousCount + "天#复活[" + awardCount + "g能量]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                        Statistics.addData(Statistics.DataType.COLLECTED, awardCount);
                        // return awardCount;
                    }
                    // 回读确认：重新拉列表，本期 currentSignKey 已签才落当日标记
                    JSONObject verify = new JSONObject(AntForestRpcCall.queryTaskList());
                    if (MessageUtil.checkResultCode(TAG, verify)) {
                        JSONArray verifyRecords = verify.getJSONArray("forestSignVOList")
                                .getJSONObject(0).getJSONArray("signRecords");
                        for (int j = 0; j < verifyRecords.length(); j++) {
                            JSONObject verifyRecord = verifyRecords.getJSONObject(j);
                            if (currentSignKey.equals(verifyRecord.optString("signKey"))
                                    && verifyRecord.optBoolean("signed")) {
                                Status.flagToday("forest::vantiepSign");
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "vitalitySign err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void queryCommonSign() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryCommonSign("ANTFOREST_GIFT7TH_SIGN_202506"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("forestSignVO")) {
                if (!Status.hasFlagToday("forest::CommonSign")) {
                    Status.flagToday("forest::CommonSign");
                    Log.forest("森林签到📆尚未检测到[森林7日签到数据]若出现数据立马为大人领取");
                }
                return;
            }
            JSONObject forestSignVO = jo.getJSONObject("forestSignVO");
            String currentSignKey = forestSignVO.getString("currentSignKey"); // 当前签到的 key
            String signId = forestSignVO.getString("signId"); // 签到ID
            String sceneCode = forestSignVO.getString("sceneCode"); // 场景代码
            JSONArray signRecords = forestSignVO.getJSONArray("signRecords"); // 签到记录
            for (int i = 0; i < signRecords.length(); i++) { // 遍历签到记录
                JSONObject signRecord = signRecords.getJSONObject(i);
                String signKey = signRecord.getString("signKey");
                int awardCount = signRecord.getInt("awardCount");
                String awardType = signRecord.getString("awardType");
                JSONObject extInfo = signRecord.getJSONObject("extInfo");
                String awardName = extInfo.getString("awardName");
                if (signKey.equals(currentSignKey) && !signRecord.getBoolean("signed")) {
                    JSONObject joSign = new JSONObject(AntForestRpcCall.antiepSign(signId, UserIdMap.getCurrentUid(), sceneCode));
                    TimeUtil.sleep(300); // 等待300毫秒
                    if (MessageUtil.checkSuccess(TAG + "森林7日签到:", joSign)) {
                        int continuousCount = joSign.getInt("continuousCount");
                        Log.forest("森林签到📆第" + continuousCount + "天#7日签到[" + awardName + "*" + awardCount + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                        if (awardType.equals("ENERGY")) {
                            Statistics.addData(Statistics.DataType.COLLECTED, awardCount);
                        }
                        // return awardCount;
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "vitalitySign err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private void vitalitySign() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.vitalitySign());
            TimeUtil.sleep(300);
            if (MessageUtil.checkResultCode(TAG, jo)) {
                int continuousCount = jo.getInt("continuousCount");
                int signAwardCount = jo.getInt("signAwardCount");
                Log.forest("森林任务📆签到[" + continuousCount + "天]奖励[" + signAwardCount + "活力值]");
            }
        } catch (Throwable t) {
            Log.i(TAG, "vitalitySign err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 青春特权森林道具路由。
     * <p>firstTaskType 是查询用的分组标识，与要领取的 awardTaskType 不同；
     * source 需与 firstTaskType 配对传入，不可随意替换。
     */
    private static final class YouthForestRoute {
        final String firstTaskType;
        final String source;
        final String awardTaskType;
        final String displayName;

        YouthForestRoute(String firstTaskType, String source, String awardTaskType, String displayName) {
            this.firstTaskType = firstTaskType;
            this.source = source;
            this.awardTaskType = awardTaskType;
            this.displayName = displayName;
        }
    }

    private static final YouthForestRoute[] YOUTH_FOREST_ROUTES = {
            new YouthForestRoute("DNHZ_SL_college", "DNHZ_SL_college", "DAXUESHENG_SJK", "双击卡"),
            new YouthForestRoute("DXS_BHZ", "202212TJBRW", "NENGLIANGZHAO_20230807", "能量罩"),
            new YouthForestRoute("DXS_JSQ", "202212TJBRW", "JIASUQI_20230808", "加速器"),
    };

    private void queryVitalityYouthTaskList() {
        for (YouthForestRoute route : YOUTH_FOREST_ROUTES) {
            queryYouthForestTask(route);
        }
    }

    private void queryTaskList() {
        queryVitalityYouthTaskList();
        try {
            boolean doubleCheck = true;
            // 复核最多 5 轮：上报/领奖后要重取列表（新判定完成的任务要能接着处理），
            // 但服务端若对已处理任务仍回需要继续，这里就会无上限空转刷请求，故与下方循环同样设上限兜底。
            int listPass = 0;
            while (doubleCheck && listPass++ < 5) {
                JSONObject jo = new JSONObject(AntForestRpcCall.queryTaskList());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    doubleCheck = false;
                    continue;
                }
                // 添加安全的空值判断
                if (!jo.has("forestTasksNew")) {
                    doubleCheck = false;
                    continue;
                }
                JSONArray forestTasksNew = jo.optJSONArray("forestTasksNew");
                if (forestTasksNew == null || forestTasksNew.length() == 0) {
                    doubleCheck = false;
                    continue;
                }
                for (int i = 0; i < forestTasksNew.length(); i++) {
                    JSONObject forestTask = forestTasksNew.optJSONObject(i);
                    if (forestTask == null) {
                        continue;
                    }
                    JSONArray taskInfoList = forestTask.optJSONArray("taskInfoList");
                    if (taskInfoList == null) {
                        continue;
                    }
                    doubleCheck = doForsetTaskList(taskInfoList);
                }
            }
            doubleCheck = true;
            // 复核最多 5 轮：领奖后要重取列表（新判定完成的子任务要能接着领），
            // 但服务端若对已领奖任务仍回 success，这里就会空转刷请求，故设上限兜底。
            int pass = 0;
            while (doubleCheck && pass++ < 5) {
                JSONObject jo = new JSONObject(AntForestRpcCall.listTaskopengreen());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                // 添加安全的空值判断
                if (!jo.has("taskInfoList")) {
                    return;
                }
                JSONArray taskInfoList = jo.optJSONArray("taskInfoList");
                if (taskInfoList == null || taskInfoList.length() == 0) {
                    return;
                }
                doubleCheck = doForsetTaskList(taskInfoList);
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryTaskList err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private Boolean doForsetTaskList(JSONArray taskInfoList) {
        boolean doubleCheck = false;
        try {
            if (taskInfoList == null || taskInfoList.length() == 0) {
                return doubleCheck;
            }
            for (int j = 0; j < taskInfoList.length(); j++) {
                JSONObject taskInfo = taskInfoList.getJSONObject(j);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                String taskType = taskBaseInfo.getString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", taskType);
                String sceneCode = taskBaseInfo.getString("sceneCode");
                String taskStatus = taskBaseInfo.getString("taskStatus");
                // 容器型父任务（taskNode=PARENT，如选教卡 ENERGY_XUANJIAO、TEST_LEAF_TASK、DAKA_GROUP）：
                // 行为都在 childTaskTypeList 里，父任务自身只是一个任务组、没有完成接口。它的 taskStatus
                // 常为 RECEIVED（任务组自身的奖已领），不能因此跳过子任务——否则子任务里「已完成但未领奖」
                // 的奖励永远领不到。也不能按 taskType 白名单挑着做：未列举的容器会被静默跳过。
                JSONArray childTaskTypeList = taskInfo.optJSONArray("childTaskTypeList");
                if ("PARENT".equals(taskBaseInfo.optString("taskNode"))
                        && childTaskTypeList != null && childTaskTypeList.length() > 0) {
                    // 先领「已完成但未领奖」的子任务，再按子任务自带的 autoCompleteTask 标记尝试待办项
                    boolean changed = awardFinishedChildren(childTaskTypeList);
                    changed = doChildTask(childTaskTypeList, taskTitle) || changed;
                    // 容器父任务自身的奖励同样要领：子任务可能都已领取，父任务却仍是 FINISHED 待领，
                    // 直接 continue 会把它永远漏掉。纯领奖、无副作用
                    if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                        changed = receiveTaskAward(sceneCode, taskType, taskTitle) || changed;
                    }
                    // 只有真动了才再核一遍：容器型父任务每轮都在列表里，无条件置 true 会让上层 while 空转
                    if (changed) {
                        doubleCheck = true;
                    }
                    continue;
                }
                if (TaskStatus.FINISHED.name().equals(taskStatus)) {
                    if (receiveTaskAward(sceneCode, taskType, taskTitle)) {
                        doubleCheck = true;
                    }
                } else if (TaskStatus.TODO.name().equals(taskStatus)) {
                    //黑名单任务跳过
                    if (AntForestVitalityTaskList.getValue().contains(blackTaskKey(taskTitle))) {
                        continue;
                    }
                    // 周期/持续型任务（如"连续 N 天…"）：由真实行为推进、周期内常驻列表且无 RPC 可完成，
                    // 跳过——不尝试、也不拉黑（见 TaskAttemptPolicy.isCyclicTask）
                    if (TaskAttemptPolicy.isCyclicTask(taskBaseInfo)) {
                        continue;
                    }
                    // 站内行为类任务（浇水/能量雨）：完成条件是**服务端的行为计数**，
                    // 只能真做那个行为；上报与伪申报都推不动它，会白试一次后按"兜底未生效"拉黑
                    TaskAttemptPolicy.Forge forge = forestVitalityForge(sceneCode, taskType, taskTitle);
                    // 做不了的当天只试一次、临时故障留待下一轮（见 TaskAttemptPolicy）
                    Outcome outcome = TaskAttemptPolicy.handle("forest::" + sceneCode + "/" + taskType,
                            taskTitle, null,
                            () -> attemptFinishTask(sceneCode, taskType, taskTitle), Log::forest,
                            new TaskAttemptPolicy.Site("AntForestVitalityTaskList", "森林任务",
                                    taskType, sceneCode, TaskAlternative.DEFAULT_VERSION, forge,
                                    (k) -> probeForestVitalityStatus(sceneCode, taskType))
                                    // 本家值：任务场景 + ANTFOREST（用庄园的 ANTFARM/H5 会被判「场景不合法」）
                                    .withScene(sceneCode).withSource("ANTFOREST"));
                    doubleCheck = outcome == Outcome.DONE || outcome == Outcome.TRIGGERED;
                }
            }
            //可能是触发限时挑战奖励的
            AntForestRpcCall.batchQueryAndTouchopengreen();
        } catch (Throwable t) {
            Log.i(TAG, "doForsetTaskList err:");
            Log.printStackTrace(TAG, t);
        }
        return doubleCheck;
    }


    private void queryYouthForestTask(YouthForestRoute route) {
        if (Status.hasFlagToday("vitalityTask::" + route.firstTaskType)) {
            return;
        }
        try {
            String status = queryYouthForestTaskStatus(route);
            if (status.isEmpty()) {
                Log.forest("青春特权道具[跳过]" + route.displayName + " 未匹配到 taskType=" + route.awardTaskType);
                return;
            }
            boolean isReceived = TaskStatus.RECEIVED.name().equals(status);
            if (!isReceived && TaskStatus.FINISHED.name().equals(status)) {
                String award = AntForestRpcCall.receiveYouthPrivilegeTaskAward(route.source, route.awardTaskType);
                Log.forest("青春特权道具[" + route.displayName + "]领奖请求 " + award);
                TimeUtil.sleep(1000);
                status = queryYouthForestTaskStatus(route);
                isReceived = TaskStatus.RECEIVED.name().equals(status);
            }
            if (isReceived) {
                Status.flagToday("vitalityTask::" + route.firstTaskType);
                Log.forest("青春特权道具[" + route.displayName + "]已领取");
            } else {
                Log.forest("青春特权道具[" + route.displayName + "]未领取 状态=" + status);
            }
        } catch (Throwable t) {
            Log.i(TAG, "queryYouthForestTask err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 查询单个青春特权道具任务状态，返回 taskStatus；查不到返回空串。
     */
    private String queryYouthForestTaskStatus(YouthForestRoute route) throws JSONException {
        JSONObject jo = new JSONObject(AntForestRpcCall.queryYouthPrivilegeTaskList(route.firstTaskType, route.source));
        JSONObject payload = jo.optJSONObject("resData");
        if (payload == null) {
            payload = jo;
        }
        for (JSONObject taskInfo : collectOpenGreenTaskInfos(payload)) {
            JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
            if (taskBaseInfo == null || !Objects.equals(route.awardTaskType, taskBaseInfo.optString("taskType"))) {
                continue;
            }
            return taskBaseInfo.optString("taskStatus");
        }
        return "";
    }

    /**
     * 收集 listTaskopengreen 响应中所有层级的 taskInfo，兼容多种嵌套结构。
     */
    private static List<JSONObject> collectOpenGreenTaskInfos(JSONObject payload) {
        List<JSONObject> taskInfos = new ArrayList<>();
        appendTaskInfoList(taskInfos, payload.optJSONArray("taskInfoList"));
        appendTaskGroups(taskInfos, payload.optJSONArray("taskGroupList"));
        appendTaskGroups(taskInfos, payload.optJSONArray("forestTasksNew"));
        JSONObject result = payload.optJSONObject("result");
        if (result != null) {
            appendTaskInfoList(taskInfos, result.optJSONArray("taskInfoList"));
            appendTaskGroups(taskInfos, result.optJSONArray("taskGroupList"));
        }
        return taskInfos;
    }

    private static void appendTaskInfoList(List<JSONObject> taskInfos, JSONArray taskInfoList) {
        if (taskInfoList == null) {
            return;
        }
        for (int i = 0; i < taskInfoList.length(); i++) {
            JSONObject taskInfo = taskInfoList.optJSONObject(i);
            if (taskInfo != null) {
                taskInfos.add(taskInfo);
            }
        }
    }

    private static void appendTaskGroups(List<JSONObject> taskInfos, JSONArray groups) {
        if (groups == null) {
            return;
        }
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group != null) {
                appendTaskInfoList(taskInfos, group.optJSONArray("taskInfoList"));
            }
        }
    }

    private Boolean receiveTaskAward(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.receiveTaskAward(sceneCode, taskType));
            TimeUtil.sleep(500);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                int incAwardCount = jo.optInt("incAwardCount");
                String returnData = jo.optString("returnData").trim();
                // 权益型任务（选教卡子任务、TEST_LEAF_* 等）的响应形如
                // {"incAwardCount":1,"provideRightsSuccess":true,"returnData":"{}"}：只有数量、没有单位。
                // 按"不含 Energy 就当活力值"贴标签会把无量纲的数字记成活力值，故只记权益发放。
                if (returnData.isEmpty() || "{}".equals(returnData)) {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]#权益已发放[数量" + incAwardCount
                            + "]#响应未给单位");
                } else if (returnData.toUpperCase().contains("ENERGY")) {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]奖励#获得[" + incAwardCount + "g能量]");
                    Statistics.addData(Statistics.DataType.COLLECTED, incAwardCount);
                } else {
                    Log.forest("森林任务🎖️领取[" + taskTitle + "]奖励#获得[" + incAwardCount + "活力值]");
                }
                return true;
            }
            // 领奖收口：领奖响应不可信（102/限流可能已发放），先按任务列表复核"已领到"。
            // 该调用点不拉黑（拉黑由完成上报路径按规则负责），故 blackList 传 null
            TimeUtil.sleep(800);
            if (TaskAward.confirmReceivedOrBlackList("森林任务🎖️领取",
                    k -> probeForestVitalityStatus(sceneCode, taskType), taskTitle, taskTitle, null,
                    msg -> Log.forest(msg))) {
                return true;
            }
        } catch (Throwable t) {
            Log.i(TAG, "receiveTaskAward err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    /**
     * 森林任务完成上报：成败以任务列表为准（见 {@link TaskAttemptPolicy}），响应不可信；
     * 400000040「不支持rpc调用」由策略代为伪申报（返回 {@link Outcome#UNSUPPORTED}），不在此处直接发请求。
     */
    private Outcome attemptFinishTask(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.finishTask(sceneCode, taskType));
            TimeUtil.sleep(500);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("森林任务🧾️完成[" + taskTitle + "]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 400000040「不支持rpc调用」不代表任务做不了：由 TaskAttemptPolicy 代为伪申报（doFarmTask）。
            // 原先裸调 finishTask 会把它直接吞成 false，导致兜底永不触发；此处也不应因此拉黑
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            //检查并标记黑名单任务
            MessageUtil.checkResultCodeAndMarkTaskBlackList("AntForestVitalityTaskList", blackTaskKey(taskTitle), jo);
            Log.record("完成任务[" + taskTitle + "]失败");
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.i(TAG, "attemptFinishTask err:");
            Log.printStackTrace(TAG, t);
        }
        return Outcome.RETRY;
    }

    /**
     * 森林任务完成上报（<b>不写黑名单</b>）：容器型父任务下的行为子任务用。
     * <p>这类子任务（无纸化阅读、电子小票等）属真实低碳行为，需用户真实操作后服务端才判定完成；
     * 失败只代表本次未完成，不代表永远做不了。若走 {@link #attemptFinishTask}，
     * 会被 {@code checkResultCodeAndMarkTaskBlackList} 写进「活力值 | 黑名单列表」，
     * 污染用户配置、并与同名顶层任务互相干扰，故此处不做该记账。
     */
    private Outcome attemptFinishTaskNoBlacklist(String sceneCode, String taskType, String taskTitle) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.finishTask(sceneCode, taskType));
            TimeUtil.sleep(500);
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("森林任务🧾️完成[" + taskTitle + "]");
                return Outcome.DONE;
            }
            if (MessageUtil.isRetryable(jo) || MessageUtil.isServerBusy(jo)) {
                return Outcome.RETRY;
            }
            // 400000040「不支持rpc调用」不代表任务做不了：由 TaskAttemptPolicy 代为伪申报
            if (TaskAlternative.hit(jo, sceneCode)) {
                return Outcome.UNSUPPORTED;
            }
            Log.record("完成任务[" + taskTitle + "]失败");
            return Outcome.UNABLE;
        } catch (Throwable t) {
            Log.i(TAG, "attemptFinishTaskNoBlacklist err:");
            Log.printStackTrace(TAG, t);
        }
        return Outcome.RETRY;
    }

    /**
     * 森林活力值任务的「站内行为」分派：能对上模块已有行为的才返回 forge，其余返回 null。
     * <p>这类任务（浇水/能量雨）的完成条件是**服务端的行为计数**，只能真做那个行为；
     * 上报与伪申报都推不动它，会白试一次后按"兜底未生效"拉黑。
     * <p>判据取任务类型/名称而非 {@code taskCategorization}：后者会把站内浇水这类行为也标成
     * {@code Exchange}，按它判会把这些任务当成交易类放过（既不伪造、又很快被拉黑）。
     * <p>真交易/履约类（缴费/出行/回收）由 {@code TaskAlternative.isTransactionTask} 在策略里先行拦下，
     * 到不了这里，故此处只需认得出"能做的事"。
     * <p><b>与 M 的差异</b>：M 还认「进页面看一眼」类（{@code EFFECTIVE_TASK} / {@code taskJumpUrl}，
     * 行为接口为 {@code takeLookEnd}）；本项目的 {@code AntForestRpcCall} 没有该接口，故不伪造这一类，
     * 交由策略的伪申报兜底处理（与接入前的行为一致）。
     */
    private TaskAttemptPolicy.Forge forestVitalityForge(String sceneCode, String taskType, String taskTitle) {
        // 真需要浇水的才走浇水，且要有可浇的对象：配置为"不浇水"或没选好友时浇不动，
        // 此时不伪造（否则等于谎报做过），留给下一轮
        if (isWaterVitalityTask(taskType, taskTitle) && canWaterFriend()) {
            return () -> {
                waterFriendEnergy();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        if (isEnergyRainVitalityTask(taskType, taskTitle)) {
            return () -> {
                startEnergyRain();
                return probeVitalityDone(sceneCode, taskType);
            };
        }
        return null;
    }

    /** 是否浇得动水：动作非"不浇水"且选了好友 */
    private boolean canWaterFriend() {
        int type = waterFriendType.getValue();
        if (type < 0 || type >= WaterFriendType.waterEnergy.length
                || WaterFriendType.waterEnergy[type] == 0) {
            return false;
        }
        Map<String, Integer> friendMap = waterFriendList.getValue();
        return friendMap != null && !friendMap.isEmpty();
    }

    /** 浇水类：{@code ONE_CLICK_WATERING_V1}、{@code ZHRW_HUIYUANjiaoshui_202608} 等 */
    private static boolean isWaterVitalityTask(String taskType, String taskTitle) {
        String type = taskType == null ? "" : taskType.toLowerCase();
        return type.contains("jiaoshui") || type.contains("watering")
                || (taskTitle != null && (taskTitle.contains("浇水") || taskTitle.contains("一键浇水")));
    }

    /** 能量雨类 */
    private static boolean isEnergyRainVitalityTask(String taskType, String taskTitle) {
        String type = taskType == null ? "" : taskType.toLowerCase();
        return type.contains("energyrain")
                || (taskTitle != null && taskTitle.contains("能量雨"));
    }

    /**
     * 行为发出后等列表确认：行为类任务的完成要以任务列表为准，行为生效有延迟。
     * <p>不能无脑返回 true——那样会让策略立刻落当日标记，任务在**行为还没被服务端确认**时就被锁死，
     * 下一轮还会撞上"兜底未生效"的判据被拉黑。
     * <p>返回 false 只表示"本次没等到确认"，不代表行为没发出；日志据此如实措辞。
     */
    private boolean probeVitalityDone(String sceneCode, String taskType) {
        for (int round = 0; round < VITALITY_PROBE_ROUNDS; round++) {
            TimeUtil.sleep(VITALITY_PROBE_INTERVAL_MS);
            if (TaskAttemptPolicy.isDone(probeForestVitalityStatus(sceneCode, taskType))) {
                return true;
            }
        }
        Log.forest("森林任务⏳[" + taskType + "]#行为已发但列表未确认，留待下一轮");
        return false;
    }

    /** 列表状态探针：重拉绿色行动任务列表，按 sceneCode+taskType 匹配该任务当前状态。 */
    private TaskAttemptPolicy.ProbeResult probeForestVitalityStatus(String sceneCode, String taskType) {
        // 状态在 taskBaseInfo 子对象内；statusOf 返回 null 表示该项不匹配
        return TaskAttemptPolicy.probeByList(TAG, AntForestRpcCall::listTaskopengreen, "taskInfoList",
                jo -> MessageUtil.checkResultCode(TAG, jo),
                taskInfo -> {
                    JSONObject taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo");
                    if (taskBaseInfo == null
                            || !sceneCode.equals(taskBaseInfo.optString("sceneCode"))
                            || !taskType.equals(taskBaseInfo.optString("taskType"))) {
                        return null;
                    }
                    return taskBaseInfo.optString("taskStatus");
                });
    }

    /**
     * 容器型父任务下「已完成但未领奖」的子任务领奖。
     * <p>这类容器的行为子任务被判定完成后不会自动发放，必须逐个 {@code receiveTaskAward}；
     * 原来只处理 TODO 子任务，已完成的那部分奖励就一直挂在列表里领不到（TEST_LEAF_TASK 曾长期如此）。
     * <p>失败不记日志也不拉黑：与顶层 FINISHED 任务的领奖路径保持一致，避免把轮询变成噪声。
     *
     * @return 是否有子任务真领到奖励（供上层决定要不要再核一遍列表）
     */
    private boolean awardFinishedChildren(JSONArray childTaskTypeList) {
        boolean awarded = false;
        try {
            for (int i = 0; i < childTaskTypeList.length(); i++) {
                JSONObject taskInfo = childTaskTypeList.getJSONObject(i);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                if (!TaskStatus.FINISHED.name().equals(taskBaseInfo.optString("taskStatus"))) {
                    continue;
                }
                JSONObject bizInfo = new JSONObject(taskBaseInfo.optString("bizInfo", "{}"));
                String taskType = taskBaseInfo.optString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", taskType);
                String sceneCode = taskBaseInfo.optString("sceneCode");
                if (receiveTaskAward(sceneCode, taskType, taskTitle)) {
                    awarded = true;
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "awardFinishedChildren err:");
            Log.printStackTrace(TAG, th);
        }
        return awarded;
    }

    /** @return 本轮是否真上报了子任务（供上层判断要不要再核一遍列表） */
    private boolean doChildTask(JSONArray childTaskTypeList, String title) {
        boolean changed = false;
        try {
            for (int i = 0; i < childTaskTypeList.length(); i++) {
                JSONObject taskInfo = childTaskTypeList.getJSONObject(i);
                JSONObject taskBaseInfo = taskInfo.getJSONObject("taskBaseInfo");
                JSONObject bizInfo = new JSONObject(taskBaseInfo.getString("bizInfo"));
                String taskType = taskBaseInfo.getString("taskType");
                String taskTitle = bizInfo.optString("taskTitle", title);
                String sceneCode = taskBaseInfo.getString("sceneCode");
                String taskStatus = taskBaseInfo.getString("taskStatus");
                if (TaskStatus.TODO.name().equals(taskStatus)) {
                    if (bizInfo.optBoolean("autoCompleteTask")) {
                        // 走统一策略：400000040 时由 TaskAttemptPolicy 代为 doFarmTask 伪申报，
                        // 裸调 finishTask 会把 UNSUPPORTED 吞成 false，兜底永不触发；同时按当日去重，
                        // 避免每轮重拉都把同一批子任务重复申报一遍。
                        // 容器子任务失败不写黑名单（见 attemptFinishTaskNoBlacklist 的说明）：
                        // Site 的 listField 传 null 即"该调用点自行按规则记账"，策略只做伪申报兜底与当日去重
                        Outcome outcome = TaskAttemptPolicy.handle(
                                "forest::child::" + sceneCode + "/" + taskType, taskTitle, null,
                                () -> attemptFinishTaskNoBlacklist(sceneCode, taskType, taskTitle), Log::forest,
                                new TaskAttemptPolicy.Site(null, "森林任务", taskType, sceneCode,
                                        (k) -> probeForestVitalityStatus(sceneCode, taskType))
                                        .withScene("ANTFOREST"));
                        // 只有真推进了才让上层再核一遍列表：被当日标记挡下（已试过）、临时故障与做不了
                        // 都不该让外层重拉空转（与顶层任务分支一致）
                        if (outcome == Outcome.DONE || outcome == Outcome.TRIGGERED) {
                            changed = true;
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "doChildTask err:");
            Log.printStackTrace(TAG, th);
        }
        return changed;
    }

    private void startEnergyRain() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.startEnergyRain());
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            String token = jo.getString("token");
            JSONArray bubbleEnergyList = jo.getJSONObject("difficultyInfo").getJSONArray("bubbleEnergyList");
            int sum = 0;
            for (int i = 0; i < bubbleEnergyList.length(); i++) {
                sum += bubbleEnergyList.getInt(i);
            }
            TimeUtil.sleep(5000L);
            if (sum == 50) {
                Status.flagToday("EnergyRain::PlayGame");
            }
            jo = new JSONObject(AntForestRpcCall.energyRainSettlement(sum, token));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Toast.show("获得了[" + sum + "g]能量[能量雨]");
                Log.forest("收能量雨🌧️[" + sum + "g]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                totalCollected += sum;
                Statistics.addData(Statistics.DataType.COLLECTED, sum);
            }
            TimeUtil.sleep(500);
        } catch (Throwable th) {
            Log.i(TAG, "startEnergyRain err:");
            Log.printStackTrace(TAG, th);
        }
    }

    // LIMIT_TIME_ENERGY_DOUBLE_CLICK,CR20230516000363
    // LIMIT_TIME_ENERGY_RAIN_CHANCE,SK20250117005985,VITALITY_ENERGYRAIN_3DAYS，限时3天内使用能量雨次卡
    private void useEnergyRainCard() {
        try {
            // 商店兑换 限时能量雨卡
            exchangeBenefit("SK20250117005985");
            TimeUtil.sleep(2000);
            JSONObject jo;
            do {
                TimeUtil.sleep(1000);
                // 背包查找 能量雨卡
                jo = null;
                List<JSONObject> list = getPropGroup(getForestPropVOList(), PropGroup.energyRain.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null) {
                    break;
                }
                // 使用能量雨卡
            } while (consumeProp(jo));
        } catch (Throwable th) {
            Log.i(TAG, "useEnergyRainCard err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void energyRain() {
        try {
            JSONObject joEnergyRainHome = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
            TimeUtil.sleep(500);
            if (MessageUtil.checkResultCode(TAG, joEnergyRainHome)) {
                if (joEnergyRainHome.getBoolean("canPlayToday")) {
                    startEnergyRain();
                }
                if (joEnergyRainHome.getBoolean("canGrantStatus")) {
                    Log.record("有送能量雨的机会");
                    JSONObject joEnergyRainCanGrantList = new JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList());
                    TimeUtil.sleep(500);
                    JSONArray grantInfos = joEnergyRainCanGrantList.getJSONArray("grantInfos");
                    Set<String> set = giveEnergyRainList.getValue();
                    String userId;
                    boolean granted = false;
                    for (int j = 0; j < grantInfos.length(); j++) {
                        JSONObject grantInfo = grantInfos.getJSONObject(j);
                        if (grantInfo.getBoolean("canGrantedStatus")) {
                            userId = grantInfo.getString("userId");
                            if (set.contains(userId)) {
                                JSONObject joEnergyRainChance = new JSONObject(AntForestRpcCall.grantEnergyRainChance(userId));
                                TimeUtil.sleep(500);
                                Log.record("尝试送能量雨给【" + UserIdMap.getMaskName(userId) + "】");
                                granted = true;
                                // 20230724能量雨调整为列表中没有可赠送的好友则不赠送
                                if (MessageUtil.checkResultCode(TAG, joEnergyRainChance)) {
                                    Log.forest("送能量雨🌧️[" + UserIdMap.getMaskName(userId) + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                    startEnergyRain();
                                }
                                break;
                            }
                        }
                    }
                    if (!granted) {
                        Log.record("没有可以送的用户");
                    }
                }
                boolean canPlayGame = joEnergyRainHome.getBoolean("canPlayGame");

                if (canPlayGame) {
                    // 检查今日是否需要执行
                    if (!Status.hasFlagToday("EnergyRain::PlayGame")) {
                        Log.record("是否可以能量雨游戏: " + canPlayGame);
                        // 检查并处理游戏任务
                        checkAndDoEndGameTask();
                        TimeUtil.sleep(4000);
                    }
                }
            }
            joEnergyRainHome = new JSONObject(AntForestRpcCall.queryEnergyRainHome());
            TimeUtil.sleep(500);
            if (MessageUtil.checkResultCode(TAG, joEnergyRainHome) && joEnergyRainHome.getBoolean("canPlayToday")) {
                startEnergyRain();
            }
        } catch (Throwable th) {
            Log.i(TAG, "energyRain err:");
            Log.printStackTrace(TAG, th);
        }
    }

    public static void checkAndDoEndGameTask() {
        try {
            // 1. 查询游戏任务列表
            String response = AntForestRpcCall.queryEnergyRainEndGameList();
            JSONObject jo = new JSONObject(response);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("energyRainEndGameGroupTask")) {
                Status.flagToday("EnergyRain::PlayGame");
                return;
            }

            // 2. 初始化新任务（需要接入森林救援队）
            if (jo.optBoolean("needInitTask", false)) {
                Log.record("检测到新任务，准备接入[森林救援队]...");
                String initResStr = AntForestRpcCall.initTask("GAME_DONE_SLJYD");
                JSONObject initRes = new JSONObject(initResStr);
                if (!MessageUtil.checkResultCode(TAG, initRes)) {
                    return;
                }
            }

            // 3. 仅当森林救援队(GAME_DONE_SLJYD)未完结时才上报，避免每轮重复发起外部请求
            JSONObject groupTask = jo.optJSONObject("energyRainEndGameGroupTask");
            JSONArray taskInfoList = groupTask != null ? groupTask.optJSONArray("taskInfoList") : null;

            String sljydStatus = null;
            if (taskInfoList != null) {
                for (int i = 0; i < taskInfoList.length(); i++) {
                    JSONObject task = taskInfoList.optJSONObject(i);
                    JSONObject baseInfo = task != null ? task.optJSONObject("taskBaseInfo") : null;
                    if (baseInfo == null) continue;
                    if ("GAME_DONE_SLJYD".equals(baseInfo.optString("taskType"))) {
                        sljydStatus = baseInfo.optString("taskStatus");
                        break;
                    }
                }
            }

            // 需要初始化、或任务处于待办/未触发时上报；已完结/无任务则仅标记今日已处理
            boolean needInit = jo.optBoolean("needInitTask", false);
            boolean shouldReport = needInit
                    || "TODO".equals(sljydStatus)
                    || "NOT_TRIGGER".equals(sljydStatus);

            if (shouldReport) {
                GameTask.Forest_sljyd.report("森林", 1, Log::forest);
                Status.flagToday("EnergyRain::PlayGame");
                return;
            }
            Log.record("森林救援队🐱无需上报(状态:" + sljydStatus + ")，今日跳过");
            Status.flagToday("EnergyRain::PlayGame");

        } catch (Throwable th) {
            Log.printStackTrace("执行能量雨后续任务出错:", th);
        }
    }

    public void doforestgame() {
        try {
            String response = AntForestRpcCall.queryGameList();
            JSONObject jo = new JSONObject(response);

            // 验证请求是否成功
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                Log.error("queryGameList 失败: " + jo.optString("desc"));
                return;
            }

            JSONObject drawRights = jo.optJSONObject("gameCenterDrawRights");
            if (drawRights != null) {
                int perTime = drawRights.optInt("quotaPerTime", 100);

                // 换算实际宝箱次数
                int canUseCount = drawRights.optInt("quotaCanUse") / perTime;
                int limitCount = drawRights.optInt("quotaLimit") / perTime;
                int usedCount = drawRights.optInt("usedQuota") / perTime;

                // 1. 处理待开启奖励 (批量开启)
                if (canUseCount > 0) {
                    Log.record("森林乐园正在一次性开启 " + canUseCount + " 个宝箱...");
                    JSONObject drawJo = new JSONObject(AntForestRpcCall.drawGameCenterAward(canUseCount));
                    if (!MessageUtil.checkResultCode(drawJo)) {
                        return;
                    }
                    JSONArray awardList = drawJo.optJSONArray("gameCenterDrawAwardList");
                    int totalEnergy = 0;
                    List<String> otherAwards = new ArrayList<>();

                    if (awardList != null) {
                        for (int i = 0; i < awardList.length(); i++) {
                            JSONObject award = awardList.getJSONObject(i);
                            String type = award.optString("awardType");
                            String name = award.optString("awardName");
                            int count = award.optInt("awardCount");
                            Log.forest("森林乐园🎁开宝箱得[" + name + "*" + count + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                            if ("ENERGY".equals(type)) {
                                totalEnergy += count;
                            } else {
                                otherAwards.add(name + "x" + count);
                            }
                        }
                    }
                    Statistics.addData(Statistics.DataType.COLLECTED, totalEnergy);
                    // 输出统计结果
                    StringBuilder logMsg = new StringBuilder("森林乐园🎁[开宝箱]共计");
                    if (totalEnergy > 0) {
                        logMsg.append("获得能量").append(totalEnergy).append("g");
                    }
                    if (!otherAwards.isEmpty()) {
                        if (totalEnergy > 0) {
                            logMsg.append(", ");
                        }
                        logMsg.append("其他: ").append(String.join("/", otherAwards));
                    }
                    Log.forest(logMsg.toString());
                    Toast.show(logMsg.toString());

                }

                // 2. 判断是否需要刷任务
                int remainToTask = limitCount - usedCount;
                if (remainToTask > 0) {
                    GameTask.Forest_slxcc.report("森林", remainToTask, Log::forest);
                } else {
                    Log.record("今日森林乐园游戏任务已满额");
                }
            }

        } catch (CancellationException e) {
            throw e;
        } catch (Throwable t) {
            Log.printStackTrace("doforestgame 流程异常", t);
        }
    }

    //乐园限定活动
    private void queryOptionalPlay() {
        try {
            int round = 0;
            // 首轮按实际任务阶段数算定上限（见 optionalPlayRoundLimit），之后不再变。
            // 原实现硬编码 2 轮，导致 6 档的多阶段任务每次运行只能推进 2 档，
            // 必须反复重开 App 才能跑完（用户可见现象：每开一次只能领一两次奖）。
            int roundLimit = 0;
            // 交易/履约类在本方法内的去重：这类任务常驻列表，多轮重拉会把同一批任务反复写盘并刷屏
            Set<String> attempted = new LinkedHashSet<>();
            while (round++ < roundLimit || roundLimit == 0) {
                // 单轮是否有新进展：无进展即跳出，防服务端恒回 TODO 时空转
                boolean progressed = false;
                JSONObject jo = new JSONObject(AntForestRpcCall.queryOptionalPlay());
                if (!MessageUtil.checkSuccess(TAG, jo)) {
                    return;
                }
                if (!jo.has("taskTriggerPlayInfo")) {
                    return;
                }
                JSONObject taskTriggerPlayInfo = jo.optJSONObject("taskTriggerPlayInfo");
                if (!taskTriggerPlayInfo.has("taskList")) {
                    return;
                }
                JSONArray taskList = taskTriggerPlayInfo.getJSONArray("taskList");
                // 账号当前没有乐园限定任务，直接收工
                if (taskList.length() == 0) {
                    Log.forest("森林乐园🎈当前无限定任务");
                    return;
                }
                if (roundLimit == 0) {
                    roundLimit = optionalPlayRoundLimit(taskList);
                }
                for (int j = 0; j < taskList.length(); j++) {
                    JSONObject task = taskList.getJSONObject(j);
                    String taskStatus = task.getString("taskStatus");
                    int alreadyReceiveAwardCount = task.optInt("alreadyReceiveAwardCount");
                    int awardCount = task.optInt("awardCount");
                    int awardCountForReceive = awardCount - alreadyReceiveAwardCount;
                    int rightsTimesLimit = task.optInt("rightsTimesLimit");
                    int rightsTimes = task.optInt("rightsTimes");
                    String awardType = task.optString("awardType", "能量");
                    JSONObject bizInfo = task.getJSONObject("bizInfo");
                    String title = bizInfo.getString("title");
                    String source = task.optString("source", "ch_appcenter__chsub_9patch");
                    String sceneCode = task.optString("sceneCode", "");
                    String taskType = task.optString("taskType", "");
                    String groupId = task.optString("groupId", "");
                    // 记录任务状态
                    if (taskStatus.equals("FINISHED")) {
                        // 当日已领过的**同一阶段**不再重复请求：服务端对已领的档位会回
                        // 「400000030 任务已完结」，重复领既刷屏又让能量统计虚高。
                        // key 必须带 rightsTimes——不带的话第一档打上的标记会把后面几档全挡死，
                        // 多阶段任务就停在第一档了。
                        String awardKey = "forest::optionalplay::award::" + sceneCode + "/" + taskType
                                + (rightsTimesLimit > 1 ? "#" + rightsTimes : "");
                        if (awardCountForReceive > 0 && !Status.hasFlagToday(awardKey)) {
                            // 领取奖励
                            JSONObject joReceived = new JSONObject(AntForestRpcCall.receiveTaskAwardopengreen(source, sceneCode, taskType));
                            if (MessageUtil.checkSuccess(TAG, joReceived)) {
                                Status.flagToday(awardKey);
                                int incAwardCount = joReceived.optInt("incAwardCount");
                                JSONObject taskConfigResultVO = joReceived.optJSONObject("taskConfigResultVO");
                                if (taskConfigResultVO != null) {
                                    awardType = taskConfigResultVO.optString("awardType", awardType);
                                }
                                // 能量统计
                                if ("能量".equals(awardType) || "ENERGY".equals(awardType)) {
                                    Log.forest("森林乐园🎖️领取[" + title + "]奖励[" + incAwardCount + "g能量]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, incAwardCount);
                                } else {
                                    Log.forest("森林乐园🎖️领取[" + title + "]奖励[" + awardType + "*" + incAwardCount + "]");
                                }
                            }
                        }
                    }
                    if (TaskStatus.TODO.name().equals(taskStatus) || rightsTimes < rightsTimesLimit) {
                        //黑名单任务跳过
                        if (AntForestVitalityTaskList.getValue().contains(blackTaskKey(title))) {
                            continue;
                        }
                        // 底线：交易/支付/充值类一律不申报、一次即永久拉黑，绝不伪造。
                        // 必须同时查 groupId：充值类的 taskType 常无关键词，判据在 groupId（cz=充值）
                        if (TaskAlternative.isTransactionTask(taskType)
                                || TaskAlternative.isTransactionTask(groupId)
                                || TaskAlternative.isTransactionTask(sceneCode)) {
                            if (attempted.add(blackTaskKey(title))) {
                                MessageUtil.MarkTaskBlackListPermanent("AntForestV2", "AntForestVitalityTaskList",
                                        "森林乐园限定任务", blackTaskKey(title));
                                Log.forest("森林乐园⏭️交易/履约类[" + title + "]#不申报，已永久拉黑");
                            }
                            continue;
                        }
                        // 开箱类任务（如"开10个乐园宝箱"）的完成条件是服务端累计开箱计数，由 doforestgame()
                        // 统一开箱（本方法之前已跑、用尽当天额度）；这里若再开必然读到 0 箱子、每轮重拉都刷
                        // "暂无可开宝箱"。未达满额时直接交给次日累计与上方 Finished 分支领奖即可。
                        if (isUnwrapAction(bizInfo.optString("actionType", ""))) {
                            continue;
                        }
                        // 标题带档位的（多阶段任务推进到下一档）与纯标题分开：定位键必须带档位，
                        // 否则第一档打上的当日标记会把后续档位全挡下、任务停在第一档。
                        // 单次任务保持原 key，语义不变。
                        String label = TaskStatus.FINISHED.name().equals(taskStatus)
                                ? title + "(" + (rightsTimes + 1) + "/" + rightsTimesLimit + ")" : title;
                        String policyKey = "forest::optionalplay::" + sceneCode + "/" + taskType
                                + (rightsTimesLimit > 1 ? "#" + rightsTimes : "");
                        // 走统一策略：400000040 时由 TaskAttemptPolicy 代为 doFarmTask 伪申报；
                        // 打满判据（rightsTimes 达上限）只打当日标记、不进黑名单（额度每日重置）
                        TaskAttemptPolicy.Site playSite = new TaskAttemptPolicy.Site(
                                "AntForestVitalityTaskList", "森林乐园限定任务",
                                taskType, sceneCode, TaskAlternative.DEFAULT_VERSION, null,
                                (k) -> probeOptionalPlayStatus(sceneCode, taskType),
                                (k) -> probeOptionalPlayExhausted(sceneCode, taskType),
                                false)
                                .withScene("ANTFOREST");
                        if (rightsTimesLimit > 1 && rightsTimes + 1 < rightsTimesLimit) {
                            // 多档里程碑的中间档（这次做完还有余量）：列表要打满才变完成，
                            // 列表复核会把它误判"未完成"，进而挡住后续档位（实测会卡在第一档），
                            // 故中间档以响应为准；末档仍走列表复核
                            playSite = playSite.withTrustResponse();
                        }
                        Outcome outcome = TaskAttemptPolicy.handle(
                                policyKey, label, null,
                                () -> attemptFinishTask(sceneCode, taskType, label), Log::forest,
                                playSite);
                        // 只有真正推进了才算"本轮有新动作"：被当日标记挡下（已试过）、临时故障（RETRY）
                        // 与做不了（UNABLE）都不该让外层重拉列表空转（与庄园一致）
                        if (outcome == Outcome.DONE || outcome == Outcome.TRIGGERED) {
                            progressed = true;
                        }
                    }
                }
                if (!progressed) {
                    break;
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "queryOptionalPlay err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 乐园限定任务的轮次上限：把所有任务的阶段数累加，再多给 6 轮余量。
     *
     * <p>为什么必须按实际阶段数算：多阶段任务（如「玩一玩我的花园世界」6 档）每轮只推进一档，
     * 上限写死会导致跑不完，用户必须反复重开 App 才能一档一档领完。
     *
     * <p>下限 {@value #OPTIONAL_PLAY_ROUND_MIN} 给足阶段数，上限 {@value #OPTIONAL_PLAY_ROUND_MAX}
     * 防任务列表异常时长时间空转。
     */
    private int optionalPlayRoundLimit(JSONArray taskList) {
        int total = 0;
        for (int i = 0; i < taskList.length(); i++) {
            JSONObject task = taskList.optJSONObject(i);
            if (task == null) {
                continue;
            }
            int limit = task.optInt("rightsTimesLimit", 1);
            total += Math.max(limit, 1);
        }
        return Math.min(Math.max(total + 6, OPTIONAL_PLAY_ROUND_MIN), OPTIONAL_PLAY_ROUND_MAX);
    }

    /**
     * 乐园限定任务状态探针：重拉 {@code queryOptionalPlay} 列表（**该任务的归属列表**），
     * 按 sceneCode+taskType 匹配该任务当前状态。
     * <p>不能借用活力值任务的探针（{@code listTaskopengreen}）——乐园任务不在那个列表里，
     * 会恒匹配不到而被当成 {@code GONE}（已消失=已完成），把"做不了"误判成"已完成"。
     */
    private static TaskAttemptPolicy.ProbeResult probeOptionalPlayStatus(String sceneCode, String taskType) {
        // 任务数组嵌在 taskTriggerPlayInfo 内层
        return TaskAttemptPolicy.probeByNestedList(TAG, AntForestRpcCall::queryOptionalPlay,
                jo -> MessageUtil.checkSuccess(TAG, jo),
                jo -> {
                    JSONObject playInfo = jo.optJSONObject("taskTriggerPlayInfo");
                    return playInfo == null ? null : playInfo.optJSONArray("taskList");
                },
                task -> taskType.equals(task.optString("taskType")) && sceneCode.equals(task.optString("sceneCode"))
                        ? task.optString("taskStatus") : null);
    }

    /** 打满判据：重拉乐园限定任务列表，读该任务的已做次数是否达上限（达上限只打当日标记、不进黑名单）。 */
    private static boolean probeOptionalPlayExhausted(String sceneCode, String taskType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryOptionalPlay());
            if (!MessageUtil.checkSuccess(TAG, jo) || !jo.has("taskTriggerPlayInfo")) {
                return false;
            }
            JSONObject playInfo = jo.optJSONObject("taskTriggerPlayInfo");
            JSONArray taskList = playInfo == null ? null : playInfo.optJSONArray("taskList");
            for (int i = 0; taskList != null && i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || !taskType.equals(task.optString("taskType"))
                        || !sceneCode.equals(task.optString("sceneCode"))) {
                    continue;
                }
                int limit = task.optInt("rightsTimesLimit", 0);
                return limit > 0 && task.optInt("rightsTimes", 0) >= limit;
            }
        } catch (Throwable t) {
            Log.i(TAG, "probeOptionalPlayExhausted err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    /** 开箱类动作（累计开箱 N 个）：完成条件在服务端的开箱计数上，只能真开箱，申报无效 */
    private static boolean isUnwrapAction(String actionType) {
        return "ACC_UNWRAP".equals(actionType) || "UNWRAP".equals(actionType);
    }

    /**
     * 黑名单键：剥掉标题末尾的 "(n/N)" 次数后缀。
     * <p>多阶段任务在上报时标题会被拼上 "(2/10)"，而运行时的黑名单检查用的是纯标题；
     * 不处理会导致写进黑名单的键永远匹配不上、拉黑失效。
     */
    private static String blackTaskKey(String taskTitle) {
        return taskTitle == null ? null : taskTitle.replaceAll("\\(\\d+/\\d+\\)$", "");
    }

    private void continuousUseCardOptions() {
        //双击卡
        continuousUseAndExchangeCard("doubleClick", "SK20240805004754");
        //收能量倍卡
        continuousUseAndExchangeCard("robExpandCard", "");
        //保护罩
        continuousUseAndExchangeCard("shield", "CR20230516000370");
        //隐身卡
        continuousUseAndExchangeCard("stealthCard", "SK20230521000206");
        //炸弹卡
        //continuousUseAndExchangeCard("energyBombCard", "SK20250219006517");
    }

    private void continuousUseAndExchangeCard(String propGroupType, String exchangeProp) {
        try {
            if (continuousUseCardOptions.getValue().contains(propGroupType)) {
                long continuousUseCardSecond = continuousUseCardCheak(propGroupType);
                if (continuousUseCardSecond >= 0) {
                    TimeUtil.sleep(500);
                    JSONObject rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                    if (rightCard == null) {
                        if (exchangeProp != null) {
                            exchangeBenefit(exchangeProp);
                            TimeUtil.sleep(500);
                        }
                        rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                        if (rightCard == null) {
                            return;
                        }
                    }
                    int holdsNum = rightCard.optInt("holdsNum");
                    if (holdsNum == 0) {
                        return;
                    }
                    int loopCount = 0; // 循环次数计数
                    final int MAX_LOOP = 10; // 最大循环次数，避免死循环
                    do {
                        rightCard = chooseContinuousLIMITTIMECard(propGroupType);
                        if (rightCard == null) {
                            return;
                        }
                        holdsNum = rightCard.optInt("holdsNum");
                        if (holdsNum == 0) {
                            return;
                        }
                        if (!rightCard.has("propIdList")) {
                            return;
                        }
                        JSONArray propIdList = rightCard.optJSONArray("propIdList");
                        if (propIdList.length() == 0) {
                            return;
                        }
                        String propId = propIdList.optString(0);
                        String propType = rightCard.optString("propType");
                        String propName = rightCard.getJSONObject("propConfigVO").getString("propName");
                        JSONObject joResult;
                        switch (propGroupType) {
                            case "doubleClick":
                            case "shield":
                            case "robExpandCard":
                                if (continuousUseCardSecond > 0) {
                                    joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType, true));
                                } else {
                                    joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType, false));
                                }
                                holdsNum--;
                                TimeUtil.sleep(500);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                }
                                break;

                            case "stealthCard":
                                joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType));
                                holdsNum--;
                                TimeUtil.sleep(1000);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                }
                                break;
                            /*case "energyBombCard":
                                joResult = new JSONObject(AntForestRpcCall.consumeProp(propGroupType, propId, propType,false));
                                holdsNum--;
                                TimeUtil.sleep(1000);
                                if (MessageUtil.checkResultCode(TAG, joResult)) {
                                    Log.forest("使用道具🎭[" + propName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                }
                                break;*/
                        }
                        continuousUseCardSecond = continuousUseCardCheak(propGroupType);
                        if (continuousUseCardSecond < 0) {
                            return;
                        }
                        TimeUtil.sleep(500);
                    } while (holdsNum > 0 && ++loopCount < MAX_LOOP);
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "continuousUseAndExchangeCard err:");
            Log.printStackTrace(TAG, th);
        }
    }

    //判断是否可以使用道具卡片
    //返回值-1为不可用，0为可用，大于0为剩余时间
    private long continuousUseCardCheak(String propGroupType) {
        try {
            JSONObject joMiscHomes = new JSONObject(AntForestRpcCall.queryMiscInfo());
            System.out.println(joMiscHomes);
            if (!MessageUtil.checkResultCode(TAG, joMiscHomes)) {
                return -1;
            }
            if (!joMiscHomes.has("combineHandlerVOMap")) {
                return -1;
            }
            long now = System.currentTimeMillis();
            JSONObject combineHandlerVOMap = joMiscHomes.optJSONObject("combineHandlerVOMap");
            if (!combineHandlerVOMap.has("usingProp")) {
                return -1;
            }
            JSONObject usingProp = combineHandlerVOMap.optJSONObject("usingProp");
            if (!usingProp.has("userPropVOS")) {
                return -1;
            }
            JSONArray userPropVOS = usingProp.getJSONArray("userPropVOS");
            for (int i = 0; i < userPropVOS.length(); i++) {
                JSONObject userPropVO = userPropVOS.getJSONObject(i);
                String propGroup = userPropVO.optString("propGroup");
                if (propGroup.equals(propGroupType)) {
                    long endTime = userPropVO.optLong("endTime");
                    long duringTime = endTime - now;
                    if (duringTime < 0) {
                        return -1;
                    }
                    switch (propGroupType) {
                        case "doubleClick":
                            if (duringTime / (1000 * 60) < 60 * 24 * 31) {
                                return duringTime;
                            } else {
                                return -1;
                            }
                        case "robExpandCard":
                            if (duringTime / (1000 * 60) < 60 * 24 * 30) {
                                return duringTime;
                            } else {
                                return -1;
                            }
                        case "stealthCard":
                            return -1;
                        case "shield":
                            if (duringTime / (1000 * 60) < 60 * continuousUseShieldHour.getValue()) {
                                return duringTime;
                            } else {
                                return -1;
                            }
                        /*case "energyBombCard":
                            if (duringTime / (1000 * 60) < 3*60 * 24) {
                                Log.forest("duringTime");
                                return duringTime;
                            }
                            else {
                                return -1;
                            }*/

                    }
                }
            }
            return 0;
        } catch (Throwable th) {
            Log.i(TAG, "useDoubleCard err:");
            Log.printStackTrace(TAG, th);
        }
        return -1;
    }

    private String useRobExpandCardFactor() {
        try {
            JSONObject joMiscHomes = new JSONObject(AntForestRpcCall.queryMiscInfo());
            System.out.println(joMiscHomes);
            if (!MessageUtil.checkResultCode(TAG, joMiscHomes)) {
                return null;
            }
            if (!joMiscHomes.has("combineHandlerVOMap")) {
                return null;
            }
            long now = System.currentTimeMillis();
            JSONObject combineHandlerVOMap = joMiscHomes.optJSONObject("combineHandlerVOMap");
            if (!combineHandlerVOMap.has("usingProp")) {
                return null;
            }
            JSONObject usingProp = combineHandlerVOMap.optJSONObject("usingProp");
            if (!usingProp.has("userPropVOS")) {
                return null;
            }
            JSONArray userPropVOS = usingProp.getJSONArray("userPropVOS");
            for (int i = 0; i < userPropVOS.length(); i++) {
                JSONObject userPropVO = userPropVOS.getJSONObject(i);
                String propGroup = userPropVO.optString("propGroup");
                if (propGroup.equals("robExpandCard")) {
                    if (!userPropVO.has("detail")) {
                        return null;
                    }
                    return userPropVO.optJSONObject("detail").optString("factor");
                }
            }
            return null;
        } catch (Throwable th) {
            Log.i(TAG, "UserobExpandCardFactor err:");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }


    //选出可以使用的限时道具卡片
    private JSONObject chooseContinuousLIMITTIMECard(String propGroupType) {
        try {
            JSONArray forestPropVOList = getForestPropVOList();
            JSONObject rightCard = null;
            String useFactor = useRobExpandCardFactor();
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestBagProp = forestPropVOList.getJSONObject(i);
                String propGroup = forestBagProp.optString("propGroup");
                if (forestBagProp.has("recentExpireTime") && propGroup.equals(propGroupType)) {
                    switch (propGroup) {
                        case "stealthCard":
                        case "shield":
                        case "doubleClick":
                            //case "energyBombCard":
                            if (rightCard != null) {
                                long recentExpireTimerightCard = rightCard.optLong("recentExpireTime");
                                long recentExpireTimeforestBagProp = forestBagProp.optLong("recentExpireTime");
                                if (recentExpireTimerightCard > recentExpireTimeforestBagProp) {
                                    rightCard = forestBagProp;
                                }
                            } else {
                                rightCard = forestBagProp;
                            }
                            break;
                        case "robExpandCard":
                            //有在用倍卡，选择同倍率快到期卡
                            if (useFactor != null) {
                                String factorforestBagProp = forestBagProp.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                if (factorforestBagProp.equals(useFactor)) {
                                    if (rightCard != null) {
                                        String factorrightCard = rightCard.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                        long recentExpireTimerightCard = rightCard.optLong("recentExpireTime");
                                        long recentExpireTimeforestBagProp = forestBagProp.optLong("recentExpireTime");
                                        if (Float.parseFloat(factorrightCard) == Float.parseFloat(factorforestBagProp)) {
                                            if (recentExpireTimerightCard > recentExpireTimeforestBagProp) {
                                                rightCard = forestBagProp;
                                            }
                                        }
                                    } else {
                                        rightCard = forestBagProp;
                                    }
                                }
                            }
                            //没有在用倍卡，选择最高倍率卡
                            else {
                                if (rightCard != null) {
                                    String factorrightCard = rightCard.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                    String factorforestBagProp = forestBagProp.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                                    if (Float.parseFloat(factorrightCard) < Float.parseFloat(factorforestBagProp)) {
                                        rightCard = forestBagProp;
                                    }
                                } else {
                                    rightCard = forestBagProp;
                                }
                            }
                            /*

                            String factorrightCard = rightCard.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                            String factorforestBagProp = forestBagProp.optJSONObject("propConfigVO").optJSONObject("detail").optString("factor");
                            long recentExpireTimerightCard = rightCard.optLong("recentExpireTime");
                            long recentExpireTimeforestBagProp = forestBagProp.optLong("recentExpireTime");
                            Log.forest("factorrightCard:"+factorrightCard);
                            Log.forest("factorforestBagProp:"+factorforestBagProp);
                            Log.forest("recentExpireTimerightCard:"+recentExpireTimerightCard);
                            Log.forest("recentExpireTimeforestBagProp:"+recentExpireTimeforestBagProp);
                            //有在用倍卡，选择同倍率快到期卡
                            if (useFactor != null) {
                                if (Float.parseFloat(factorrightCard) == Float.parseFloat(factorforestBagProp)) {
                                    if (recentExpireTimerightCard > recentExpireTimeforestBagProp) {
                                        rightCard = forestBagProp;
                                    }
                                }
                            }
                            //没有在用倍卡，选择最高倍率卡
                            else {
                                if (rightCard != null) {
                                    if (Float.parseFloat(factorrightCard) < Float.parseFloat(factorforestBagProp)) {
                                        rightCard = forestBagProp;
                                    }
                                } else {
                                    rightCard = forestBagProp;
                                }
                            }*/
                    }
                }
            }
            return rightCard;
        } catch (Throwable th) {
            Log.i(TAG, "useDoubleCard err:");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }

    private void usePropBeforeCollectEnergy(String userId) {
        if (Objects.equals(selfId, userId)) {
            return;
        }
        if (needDoubleClick()) {
            synchronized (usePropLockObj) {
                if (needDoubleClick()) {
                    useDoubleCard(getForestPropVOList());
                }
            }
        }
    }

    private Boolean needDoubleClick() {
        if (doubleClickType.getValue() == UsePropType.CLOSE) {
            return false;
        }
        Long doubleClickEndTime = usingProps.get(PropGroup.doubleClick.name());
        if (doubleClickEndTime == null) {
            return true;
        }
        return doubleClickEndTime < System.currentTimeMillis();
    }

    private void useDoubleCard(JSONArray forestPropVOList) {
        try {
            if (hasDoubleCardTime() && Status.canDoubleToday()) {
                // 背包查找 能量双击卡
                JSONObject jo = null;
                List<JSONObject> list = getPropGroup(forestPropVOList, PropGroup.doubleClick.name());
                if (!list.isEmpty()) {
                    jo = list.get(0);
                }
                if (jo == null || !jo.has("recentExpireTime")) {
                    if (doubleCardConstant.getValue()) {
                        // 商店兑换 限时能量双击卡
                        if (exchangeBenefit("SK20240805004754")) {
                            jo = getForestPropVO(getForestPropVOList(), "ENERGY_DOUBLE_CLICK_31DAYS");
                        } else if (exchangeBenefit("CR20230516000363")) {
                            jo = getForestPropVO(getForestPropVOList(), "LIMIT_TIME_ENERGY_DOUBLE_CLICK");
                        }
                    }
                }
                if (jo == null) {
                    return;
                }
                if (!jo.has("recentExpireTime") && doubleClickType.getValue() == UsePropType.ONLY_LIMIT_TIME) {
                    return;
                }
                // 使用能量双击卡
                if (consumeProp(jo)) {
                    Long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(jo.getJSONObject("propConfigVO").getLong("durationTime"));
                    usingProps.put(PropGroup.doubleClick.name(), endTime);
                    Status.DoubleToday();
                } else {
                    updateUsingPropsEndTime();
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "useDoubleCard err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private boolean hasDoubleCardTime() {
        long currentTimeMillis = System.currentTimeMillis();
        return TimeUtil.checkInTimeRange(currentTimeMillis, doubleCardTime.getValue());
    }

    /* 赠送道具 */
    private void giveProp() {
        Set<String> set = whoYouWantToGiveTo.getValue();
        if (set.isEmpty()) {
            return;
        }
        for (String userId : set) {
            if (UserIdMap.getCurrentUid() == null || Objects.equals(UserIdMap.getCurrentUid(), userId)) {
                continue;
            }
            giveProp(userId);
            break;
        }
    }

    private void giveProp(String targetUserId) {
        try {
            do {
                try {
                    JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(true));
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    JSONArray forestPropVOList = jo.optJSONArray("forestPropVOList");
                    if (forestPropVOList != null && forestPropVOList.length() > 0) {
                        jo = forestPropVOList.getJSONObject(0);
                        String giveConfigId = jo.getJSONObject("giveConfigVO").getString("giveConfigId");
                        int holdsNum = jo.optInt("holdsNum", 0);
                        String propName = jo.getJSONObject("propConfigVO").getString("propName");
                        String propId = jo.getJSONArray("propIdList").getString(0);
                        jo = new JSONObject(AntForestRpcCall.giveProp(giveConfigId, propId, targetUserId));
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            Log.forest("赠送道具🎭[" + UserIdMap.getMaskName(targetUserId) + "]#" + propName);
                            if (holdsNum > 1 || forestPropVOList.length() > 1) {
                                continue;
                            }
                        }
                    }
                } finally {
                    TimeUtil.sleep(1500);
                }
                break;
            } while (true);
        } catch (Throwable th) {
            Log.i(TAG, "giveProp err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 绿色行动
     */
    private void ecoLife() {
        try {
            JSONObject jo = new JSONObject(EcoLifeRpcCall.queryHomePage());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.getBoolean("openStatus")) {
                Log.forest("绿色任务☘未开通");
                jo = new JSONObject(EcoLifeRpcCall.openEcolife());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                if (!String.valueOf(true).equals(JsonUtil.getValueByPath(jo, "data.opResult"))) {
                    return;
                }
                Log.forest("绿色任务🍀报告大人，开通成功(～￣▽￣)～可以愉快的玩耍了");
                jo = new JSONObject(EcoLifeRpcCall.queryHomePage());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                data = jo.getJSONObject("data");
            }
            String dayPoint = data.getString("dayPoint");
            JSONArray actionListVO = data.getJSONArray("actionListVO");
            if (ecoLifeOptions.getValue().contains("dish")) {
                photoGuangPan(dayPoint);
            }
            if (ecoLifeOptions.getValue().contains("tick")) {
                ecoLifeTick(actionListVO, dayPoint);
            }
        } catch (Throwable th) {
            Log.i(TAG, "ecoLife err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /* 绿色行动打卡 */

    private void ecoLifeTick(JSONArray actionListVO, String dayPoint) {
        try {
            String source = "source";
            for (int i = 0; i < actionListVO.length(); i++) {
                JSONObject actionVO = actionListVO.getJSONObject(i);
                JSONArray actionItemList = actionVO.getJSONArray("actionItemList");
                for (int j = 0; j < actionItemList.length(); j++) {
                    JSONObject actionItem = actionItemList.getJSONObject(j);
                    if (!actionItem.has("actionId")) {
                        continue;
                    }
                    if (actionItem.getBoolean("actionStatus")) {
                        continue;
                    }
                    String actionId = actionItem.getString("actionId");
                    String actionName = actionItem.getString("actionName");
                    if ("photoguangpan".equals(actionId)) {
                        continue;
                    }
                    JSONObject jo = new JSONObject(EcoLifeRpcCall.tick(actionId, dayPoint, source));
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.forest("绿色打卡🍀[" + actionName + "]");
                    }
                    TimeUtil.sleep(500);
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "ecoLifeTick err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /**
     * 光盘行动
     */
    private void photoGuangPan(String dayPoint) {
        // if (!TaskCommon.IS_AFTER_6AM) {
        //    return;
        // }
        try {
            String source = "renwuGD";
            // 检查今日任务状态
            JSONObject jo = new JSONObject(EcoLifeRpcCall.queryDish(source, dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 更新光盘照片
            Map<String, String> dishImage = new HashMap<>();
            JSONObject data = jo.optJSONObject("data");
            if (data != null) {
                String beforeMealsImageUrl = data.optString("beforeMealsImageUrl");
                String afterMealsImageUrl = data.optString("afterMealsImageUrl");
                if (!StringUtil.isEmpty(beforeMealsImageUrl) && !StringUtil.isEmpty(afterMealsImageUrl)) {
                    Pattern pattern = Pattern.compile("img/(.*)/original");
                    Matcher beforeMatcher = pattern.matcher(beforeMealsImageUrl);
                    if (beforeMatcher.find()) {
                        dishImage.put("BEFORE_MEALS", beforeMatcher.group(1));
                    }
                    Matcher afterMatcher = pattern.matcher(afterMealsImageUrl);
                    if (afterMatcher.find()) {
                        dishImage.put("AFTER_MEALS", afterMatcher.group(1));
                    }
                    TokenConfig.saveDishImage(dishImage);
                }
            }
            if (Objects.equals("SUCCESS", jo.getJSONObject("data").getString("status"))) {
                // Log.forest("光盘行动💿今日打卡已完成");
                return;
            }

            dishImage = TokenConfig.getRandomDishImage();
            if (dishImage == null) {
                Log.forest("光盘行动💿请先完成一次光盘打卡");
                return;
            }
            // 上传餐前照片
            jo = new JSONObject(EcoLifeRpcCall.uploadBeforeMealsDishImage(dishImage.get("BEFORE_MEALS"), dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 上传餐后照片
            jo = new JSONObject(EcoLifeRpcCall.uploadAfterMealsDishImage(dishImage.get("AFTER_MEALS"), dayPoint));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            // 提交
            jo = new JSONObject(EcoLifeRpcCall.tick("photoguangpan", dayPoint, source));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            String toastMsg = jo.getJSONObject("data").getString("toastMsg");
            Toast.show("光盘行动💿打卡完成#" + toastMsg);
            Log.forest("光盘行动💿打卡完成#" + toastMsg + "[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
        } catch (Throwable t) {
            Log.i(TAG, "photoGuangPan err:");
            Log.printStackTrace(TAG, t);
        }
    }

    private int forFriendCollectEnergy(String targetUserId, long bubbleId) {
        int helped = 0;
        try {
            String s = AntForestRpcCall.forFriendCollectEnergy(targetUserId, bubbleId);
            JSONObject jo = new JSONObject(s);
            if ("SUCCESS".equals(jo.getString("resultCode"))) {
                JSONArray jaBubbles = jo.getJSONArray("bubbles");
                for (int i = 0; i < jaBubbles.length(); i++) {
                    jo = jaBubbles.getJSONObject(i);
                    helped += jo.getInt("collectedEnergy");
                }
                if (helped > 0) {
                    Log.forest("帮收能量🧺[" + UserIdMap.getMaskName(targetUserId) + "]#" + helped + "g");
                    totalHelpCollected += helped;
                    Statistics.addData(Statistics.DataType.HELPED, helped);
                } else {
                    Log.record("帮[" + UserIdMap.getMaskName(targetUserId) + "]收取失败");
                    Log.i("，UserID：" + targetUserId + "，BubbleId" + bubbleId);
                }
            } else {
                Log.record("[" + UserIdMap.getMaskName(targetUserId) + "]" + jo.getString("resultDesc"));
                Log.i(s);
            }
        } catch (Throwable t) {
            Log.i(TAG, "forFriendCollectEnergy err:");
            Log.printStackTrace(TAG, t);
        }
        return helped;
    }

    public static JSONArray getForestPropVOList() {
        JSONArray forestPropVOList = new JSONArray();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPropList(false));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                forestPropVOList = jo.getJSONArray("forestPropVOList");
            }
        } catch (Throwable th) {
            Log.i(TAG, "getForestPropVOList err:");
            Log.printStackTrace(TAG, th);
        }
        return forestPropVOList;
    }

    // 获取道具组全部道具
    public static List<JSONObject> getPropGroup(JSONArray forestPropVOList, String propGroup) {
        List<JSONObject> list = new ArrayList<>();
        try {
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestPropVO = forestPropVOList.getJSONObject(i);
                if (forestPropVO.getString("propGroup").equals(propGroup)) {
                    list.add(forestPropVO);
                }
            }
            Collections.sort(list, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject jsonObject1, JSONObject jsonObject2) {
                    try {
                        int durationTime1 = jsonObject1.getJSONObject("propConfigVO").getInt("durationTime");
                        int durationTime2 = jsonObject2.getJSONObject("propConfigVO").getInt("durationTime");
                        boolean hasExpireTime1 = jsonObject1.has("recentExpireTime");
                        boolean hasExpireTime2 = jsonObject2.has("recentExpireTime");
                        if (hasExpireTime1 && hasExpireTime2) {
                            long endTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(durationTime1);
                            long recentExpireTime = jsonObject2.getLong("recentExpireTime");
                            if (endTime < recentExpireTime) {
                                return -1;
                            } else {
                                return durationTime2 - durationTime1;
                            }
                        } else if (!hasExpireTime1 && !hasExpireTime2) {
                            return durationTime1 - durationTime2;
                        } else {
                            return hasExpireTime1 ? -1 : 1;
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (Throwable th) {
            Log.i(TAG, "getPropGroup err:");
            Log.printStackTrace(TAG, th);
        }
        return list;
    }

    /*
     * 查找背包道具
     * prop
     * propGroup, propType, holdsNum, propIdList[], propConfigVO[propName]
     */
    private JSONObject getForestPropVO(JSONArray forestPropVOList, String propType) {
        try {
            for (int i = 0; i < forestPropVOList.length(); i++) {
                JSONObject forestPropVO = forestPropVOList.getJSONObject(i);
                if (forestPropVO.getString("propType").equals(propType)) {
                    return forestPropVO;
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "getForestPropVO err:");
            Log.printStackTrace(TAG, th);
        }
        return null;
    }

    /*
     * 使用背包道具
     * prop
     * propGroup, propType, holdsNum, propIdList[], propConfigVO[propName]
     */
    public static Boolean consumeProp(JSONObject prop) {
        try {
            // 使用道具
            String propId = prop.getJSONArray("propIdList").getString(0);
            String propType = prop.getString("propType");
            String propGroup = prop.getString("propGroup");
            String propName = prop.getJSONObject("propConfigVO").getString("propName");
            return consumeProp(propGroup, propId, propType, propName);
        } catch (Throwable th) {
            Log.i(TAG, "consumeProp err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private static Boolean consumeProp(String propGroup, String propId, String propType, String propName) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.consumeProp(propGroup, propId, propType));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Log.forest("使用道具🎭[" + propName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                return true;
            }
        } catch (Throwable th) {
            Log.i(TAG, "consumeProp err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    /** 活力值商店的分类：官方按这 5 个 labelType 分别拉取，少查一个列表就不全 */
    private static final String[] VITALITY_LABEL_TYPES = {"", "SC_ASSETS", "SKIN", "JEWELRY", "OTHER"};

    /** 单分类翻页上限：hasMore 异常一直为真时兜底 */
    private static final int VITALITY_ITEM_MAX_PAGES = 10;

    /** 活力值商店列表拉取失败的当日重试上限：服务端持续失败时避免每轮都重拉（单轮最多 5 分类 × 10 页） */
    private static final int VITALITY_SKU_RETRY_MAX = 3;

    /** 活力值商店列表拉取失败次数的当日计数 key */
    private static final String FLAG_VITALITY_SKU_RETRY = "forest::vitalitySkuListRetry";

    /**
     * 获取活力值商店所有商品信息。
     * <p>原先只查 SC_ASSETS 的第一页 ⇒ 权益列表只有一小部分，想兑换的权益选不到，
     * 兑换时报「活力兑换🎐找不到要兑换的权益！」；改为按官方实测的 5 个 labelType
     * 分别拉取，并用响应里的 hasMore 翻页。
     * <p>当日标记的判断放在方法内部：除了 {@link #vitalityExchangeBenefit()}，
     * 兑换道具等路径也会直接调用它，那些路径同样需要「当日只拉一次」——
     * 否则单轮最多 5 分类 × 10 页的请求会被反复发出。
     *
     * @return 是否真的取到 SKU（取不到时留给下一轮重试）
     */
    private boolean getAllSkuInfo() {
        try {
            if (Status.hasFlagToday("forest::vitalitySkuList")) {
                return true;
            }
            // 失败重试上限：服务端持续失败时避免每轮都重拉（单轮最多 5 分类 × 10 页）
            int retries = Status.getIntFlagToday(FLAG_VITALITY_SKU_RETRY);
            if (retries >= VITALITY_SKU_RETRY_MAX) {
                return false;
            }
            int got = 0;
            for (String labelType : VITALITY_LABEL_TYPES) {
                int cnt = 0;
                for (int page = 0; page < VITALITY_ITEM_MAX_PAGES; page++) {
                    JSONObject jo = new JSONObject(AntForestRpcCall.itemList(labelType, page * AntForestRpcCall.VITALITY_ITEM_PAGE_SIZE));
                    if (!MessageUtil.checkSuccess(TAG, jo)) {
                        break;
                    }
                    JSONArray itemInfoVOList = optItemInfoVOList(jo);
                    if (itemInfoVOList == null || itemInfoVOList.length() == 0) {
                        break;
                    }
                    for (int i = 0; i < itemInfoVOList.length(); i++) {
                        getSkuInfoByItemInfoVO(itemInfoVOList.getJSONObject(i));
                        cnt++;
                    }
                    if (!hasMore(jo)) {
                        break;
                    }
                }
                got += cnt;
                Log.i(TAG, "活力值商店列表：[" + (labelType.isEmpty() ? "全部" : labelType) + "]取到" + cnt + "条");
            }
            VitalityBenefitIdMap.save(UserIdMap.getCurrentUid());
            Log.i(TAG, "活力值商店列表：共取" + got + "条，清单共" + VitalityBenefitIdMap.getMap().size() + "条");
            if (got > 0) {
                // 拉到了才打当日标记；拉不到留给下一轮重试（但当日重试次数有上限）
                Status.flagToday("forest::vitalitySkuList");
                return true;
            }
            accountVitalitySkuRetry();
            return false;
        } catch (Throwable th) {
            Log.i(TAG, "getAllSkuInfo err:");
            Log.printStackTrace(TAG, th);
            accountVitalitySkuRetry();
            return false;
        }
    }

    /** 活力值商店列表拉取失败计数：达上限后当日不再重拉，避免每轮都重试 */
    private static void accountVitalitySkuRetry() {
        int tries = Status.getIntFlagToday(FLAG_VITALITY_SKU_RETRY) + 1;
        Status.setIntFlagToday(FLAG_VITALITY_SKU_RETRY, tries);
        if (tries >= VITALITY_SKU_RETRY_MAX) {
            Log.i(TAG, "活力值商店列表：连续" + tries + "次未取到，今日不再重试");
        }
    }

    /** 商品列表可能在顶层、也可能在 resData 下（抓包两层都出现过），两层都取 */
    private static JSONArray optItemInfoVOList(JSONObject jo) {
        JSONArray list = jo.optJSONArray("itemInfoVOList");
        if (list == null) {
            JSONObject resData = jo.optJSONObject("resData");
            if (resData != null) {
                list = resData.optJSONArray("itemInfoVOList");
            }
        }
        return list;
    }

    /** hasMore 表示还有下一页，同样两层都取 */
    private static boolean hasMore(JSONObject jo) {
        if (jo.has("hasMore")) {
            return jo.optBoolean("hasMore", false);
        }
        JSONObject resData = jo.optJSONObject("resData");
        return resData != null && resData.optBoolean("hasMore", false);
    }

    private void getSkuInfoBySpuId(String spuId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.itemDetail(spuId));
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            JSONObject spuItemInfoVo = jo.getJSONObject("spuItemInfoVO");
            getSkuInfoByItemInfoVO(spuItemInfoVo);
            VitalityBenefitIdMap.save(UserIdMap.getCurrentUid());
        } catch (Throwable th) {
            Log.i(TAG, "getSkuInfoBySpuId err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private void getSkuInfoByItemInfoVO(JSONObject spuItem) {
        try {
            String spuId = spuItem.getString("spuId");
            JSONArray skuModelList = spuItem.getJSONArray("skuModelList");
            for (int i = 0; i < skuModelList.length(); i++) {
                JSONObject skuModel = skuModelList.getJSONObject(i);
                String skuId = skuModel.getString("skuId");
                String skuName = skuModel.getString("skuName");
                if (!skuModel.has("spuId")) {
                    skuModel.put("spuId", spuId);
                }
                skuInfo.put(skuId, skuModel);
                VitalityBenefitIdMap.add(skuId, skuName);
            }
        } catch (Throwable th) {
            Log.i(TAG, "getSkuInfoByItemInfoVO err:");
            Log.printStackTrace(TAG, th);
        }
    }

    /*
     * 兑换活力值商品
     * sku
     * spuId, skuId, skuName, exchangedCount, price[amount]
     * exchangedCount == 0......
     */
    private Boolean exchangeBenefit(String skuId) {
        if (skuInfo.isEmpty()) {
            getAllSkuInfo();
        }
        JSONObject sku = skuInfo.get(skuId);
        if (sku == null) {
            // 失效 skuId（权益下架/换号/服务端改 ID）：打当日标记，当天不再重试；
            // 否则每轮都会遍历到它并刷一条日志，既刷屏又拖累后续任务
            if (!Status.hasFlagToday("forest::exchangeSkuMissing::" + skuId)) {
                Log.record("活力兑换🎐找不到要兑换的权益！" + skuId + "#今日不再尝试");
                Status.flagToday("forest::exchangeSkuMissing::" + skuId);
            }
            return false;
        }
        try {
            String skuName = sku.getString("skuName");
            JSONArray itemStatusList = sku.getJSONArray("itemStatusList");
            for (int i = 0; i < itemStatusList.length(); i++) {
                String itemStatus = itemStatusList.getString(i);
                if (ItemStatus.REACH_LIMIT.name().equals(itemStatus) || ItemStatus.NO_ENOUGH_POINT.name().equals(itemStatus) || ItemStatus.NO_ENOUGH_STOCK.name().equals(itemStatus)) {
                    Log.record("活力兑换🎐[" + skuName + "]停止:" + ItemStatus.valueOf(itemStatus).nickName());
                    if (ItemStatus.REACH_LIMIT.name().equals(itemStatus)) {
                        Status.flagToday("forest::exchangeLimit::" + skuId);
                    }
                    return false;
                }
            }
            String spuId = sku.getString("spuId");
            if (exchangeBenefit(spuId, skuId, skuName)) {
                return true;
            }
            getSkuInfoBySpuId(spuId);
        } catch (Throwable th) {
            Log.i(TAG, "exchangeBenefit err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    public static Boolean exchangeBenefit(String spuId, String skuId, String skuName) {
        try {
            if (exchangeBenefit(spuId, skuId)) {
                Status.vitalityExchangeBenefitToday(skuId);
                int exchangedCount = Status.getVitalityExchangeBenefitCountToday(skuId);
                Log.forest("活力兑换🎐[" + skuName + "]#第" + exchangedCount + "次");
                return true;
            }
        } catch (Throwable th) {
            Log.i(TAG, "exchangeBenefit err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private static Boolean exchangeBenefit(String spuId, String skuId) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.exchangeBenefit(spuId, skuId));
            if (jo.has("errorMessage")) {
                String errorMessage = jo.optString("errorMessage");
                //如果出错今天停止兑换
                if (errorMessage.equals("系统繁忙，请稍后再试。")) {
                    Status.flagToday("forest::exchangeLimit::" + skuId);
                }
            }
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.i(TAG, "exchangeBenefit err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private void teamCooperateWater() {
        try {

            int userDailyTarget = Math.min(Math.max(partnerteamWaterNum.getValue(), 10), 5000);
            int todayUsed = Status.getforestHuntHelpToday("FLAG_TEAM_WATER_DAILY_COUNT");
            int userRemainingQuota = userDailyTarget - todayUsed;

            if (userRemainingQuota < 10) {
                Log.record("组队合种今日已达标 (已浇" + todayUsed + "g / 目标" + userDailyTarget + "g)，跳过");
                return;
            }

            // 获取组队合种基础信息
            String homeStr = AntForestRpcCall.queryHomePage();
            JSONObject homeJo = new JSONObject(homeStr);
            if (!MessageUtil.checkResultCode(TAG, homeJo)) {
                Log.record("queryHomePage 返回异常");
                return;
            }

            String teamId = homeJo.optJSONObject("teamHomeResult").optJSONObject("teamBaseInfo").optString("teamId", "");
            if (teamId.isEmpty()) {
                Log.record("未获取到组队合种 TeamID");
                return;
            }

            int currentEnergy = homeJo.optJSONObject("userBaseInfo").optInt("currentEnergy", 0);
            if (currentEnergy < 10) {
                Log.record("当前能量不足10g(" + currentEnergy + "g)，无法浇水");
                return;
            }

            // 切换团队模式
            boolean needReturn = false;
            if (!isTeam(homeJo)) {
                Log.record("不在队伍模式,已为您切换至组队浇水");
                updateUserConfiginTeam(!needReturn);
                needReturn = true;
            }

            // 获取服务端限制
            String miscStr = AntForestRpcCall.queryMiscInfo("teamCanWaterCount", teamId);
            JSONObject miscJo = new JSONObject(miscStr);
            if (!MessageUtil.checkResultCode(TAG, miscJo)) {
                Log.record("queryMiscInfo 查询失败");
                if (needReturn) {
                    updateUserConfiginTeam(!needReturn);
                }
                return;
            }

            int serverRemaining = miscJo.optJSONObject("combineHandlerVOMap").optJSONObject("teamCanWaterCount").optInt("waterCount", 0);
            Log.record("组队状态检查:目标剩余" + userRemainingQuota + "g|官方剩余" + serverRemaining + "g|背包能量" + currentEnergy + "g");

            if (serverRemaining < 10) {
                Log.record("官方限制今日无可浇水额度，跳过");
                if (needReturn) {
                    updateUserConfiginTeam(!needReturn);
                }
                return;
            }

            // 计算最终浇水量
            int finalWaterAmount = Math.min(userRemainingQuota, Math.min(serverRemaining, currentEnergy));
            if (finalWaterAmount < 10) {
                Log.record("计算后浇水量(" + finalWaterAmount + "g)低于最小限制10g，不执行");
                if (needReturn) {
                    updateUserConfiginTeam(!needReturn);
                }
                return;
            }

            // 执行浇水。记账原则与真爱合种一致：只有「确定没发出去」才不计当日额度，
            // 其余（响应判定不成、超时、解析异常）一律按已浇记账——否则每次自动运行都会重发一次。
            JSONObject waterJo = null;
            Throwable waterErr = null;
            try {
                waterJo = new JSONObject(AntForestRpcCall.teamWater(teamId, finalWaterAmount));
            } catch (Throwable t) {
                waterErr = t;
            }
            boolean accountWater = true;
            if (waterErr != null) {
                if (ApplicationHook.isOffline()) {
                    // 确定没发出去：不记账，下一轮再试
                    accountWater = false;
                    Log.record("组队合种浇水未发出(离线)，下次运行再试");
                } else {
                    // 超时 / 响应解析异常：请求可能已经到服务端，按已浇记账避免重复
                    Log.i(TAG, "teamWater err:");
                    Log.printStackTrace(TAG, waterErr);
                    Log.record("组队合种浇水结果不明(超时/解析异常)，按已浇记账避免重复");
                }
            } else if (MessageUtil.checkResultCode(TAG, waterJo) || MessageUtil.checkSuccess(TAG, waterJo)) {
                Log.forest("组队合种🚿给合种浇水" + finalWaterAmount + "g#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                Toast.show("组队合种🚿给合种浇水" + finalWaterAmount + "g");
            } else {
                // 判定不成（结构不符 / 今日已浇过 / 额度用尽）：按已浇记账，保留响应原文便于排查
                Log.record("组队合种浇水未确认成功，按已浇记账避免重复：" + waterJo);
            }
            if (accountWater) {
                Status.forestHuntHelpToday("FLAG_TEAM_WATER_DAILY_COUNT", todayUsed + finalWaterAmount, UserIdMap.getCurrentUid());
                Log.record("组队合种今日浇水累计: " + (todayUsed + finalWaterAmount) + "g / " + userDailyTarget + "g");
            }

            // 切换回个人模式
            if (needReturn) {
                updateUserConfiginTeam(!needReturn);
                Log.record("已返回个人模式");
            }
        } catch (Throwable t) {
            Log.printStackTrace("teamCooperateWater 异常:", t);
        }
    }

    //needReturn:false切回个人模式，true切到组队模式
    private static boolean updateUserConfiginTeam(Boolean needReturn) {
        try {
            String updateStr = AntForestRpcCall.updateUserConfiginTeam(needReturn);
            JSONObject updateJo = new JSONObject(updateStr);
            if (!MessageUtil.checkResultCode(TAG, updateJo)) {
                Log.record("updateUserConfig 返回异常");
                return false;
            } else {
                Log.record("合种浇水切换成功：" + (needReturn ? "切到组队模式" : "切到个人模式"));
                return true;
            }
        } catch (Throwable th) {
            Log.i(TAG, "updateUserConfig err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private static boolean isTeam(JSONObject homeObj) {
        return "Team".equals(homeObj.optString("nextAction", ""));
    }

    private static void loveteam(int loveteamWater) {
        if (!Status.hasFlagToday("Forest::loveteamWater")) {
            try {
                JSONObject jo = new JSONObject(AntForestRpcCall.loveteamHome());
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                if (jo.has("userInfo")) {
                    JSONObject userInfo = jo.getJSONObject("userInfo");
                    if (userInfo.has("teamId")) {
                        String teamId = userInfo.getString("teamId");
                        loveteamWater(teamId, loveteamWater);
                    }
                }
            } catch (Throwable th) {
                Log.i(TAG, "loveteam err:");
                Log.printStackTrace(TAG, th);
            }
        }
    }

    private static void loveteamWater(String loveteamWater, int loveteamWaterNum) {
        // 当日标记「先落再发请求」：这个动作一天只该做一次，不能把标记挂在响应判定上——
        // 响应一旦不被判定为成功（结构不符、或服务端提示今日已浇/额度用尽），标记就落不下去，
        // 每次自动运行都会重发一次浇水请求，表现为"反复浇水"。
        Status.flagToday("Forest::loveteamWater");
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.loveteamWater(loveteamWater, loveteamWaterNum));
            if (MessageUtil.checkSuccess(TAG, jo)) {
                Log.forest("真爱浇水🚿给[" + loveteamWater + "]合种浇水" + loveteamWaterNum + "g#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                Toast.show("真爱浇水🚿给[" + loveteamWater + "]合种浇水" + loveteamWaterNum + "g");
                return;
            }
            // 判定不成（结构不符 / 今日已浇过 / 额度用尽）当日不再重发，保留响应原文便于排查
            Log.record("真爱合种浇水未确认成功，今日不再尝试：" + jo);
        } catch (Throwable th) {
            if (ApplicationHook.isOffline()) {
                // 确定没发出去：撤销标记，下一轮再试
                Status.clearFlag("Forest::loveteamWater");
                Log.record("真爱合种浇水未发出(离线)，下次运行再试");
            } else {
                // 可能已经发出去（超时 / 响应解析异常）：保留标记，避免每轮重浇
                Log.i(TAG, "loveteamWater err:");
                Log.printStackTrace(TAG, th);
            }
        }
    }

    private static boolean updateUserConfigEnergyPvp() {
        try {
            String Str = AntForestRpcCall.queryUserTag();
            JSONObject Jo = new JSONObject(Str);
            if (!MessageUtil.checkResultCode(TAG, Jo)) {
                Log.record("queryUserTag 查询1V1状态返回异常");
                return false;
            }
            if (Jo.has("tagMap")) {
                JSONObject tagMap = Jo.getJSONObject("tagMap");
                if (!tagMap.has("energyPvp")) {
                    Log.record("查询1V1无状态返回[无法参加]若出现标识立马为大人开启");
                    return false;
                }
                String energyPvp = tagMap.getString("energyPvp");
                if (energyPvp.equals("Y")) {
                    return true;
                }
            }
            //自动开启挑战
            String updateStr = AntForestRpcCall.updateUserConfigEnergyPvp(true);
            JSONObject updateJo = new JSONObject(updateStr);
            if (!MessageUtil.checkResultCode(TAG, updateJo)) {
                Log.record("updateUserConfigEnergyPvp 返回异常");
            } else {
                //Log.record("1V1能量挑战切换成功：" + (needReturn ? "开启" : "关闭"));
                Log.forest("比赛情况🆚1V1能量挑战成功开启");
                return true;
            }
            return false;
        } catch (Throwable th) {
            Log.i(TAG, "updateUserConfigEnergyPvp err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private static void queryPvpHomeInfo() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryPvpHomeInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (jo.has("currentEnergyPvpBattleRecord")) {
                JSONObject currentEnergyPvpBattleRecord = jo.getJSONObject("currentEnergyPvpBattleRecord");
                //获取1v1比赛情况
                String attackerDisplayName = currentEnergyPvpBattleRecord.optString("attackerDisplayName");
                int attackerEnergy = currentEnergyPvpBattleRecord.optInt("attackerEnergy");
                int attackerWinCount = currentEnergyPvpBattleRecord.optInt("attackerWinCount");
                String battleType = currentEnergyPvpBattleRecord.optString("battleType");
                String defenderDisplayName = currentEnergyPvpBattleRecord.optString("defenderDisplayName");
                int defenderEnergy = currentEnergyPvpBattleRecord.optInt("defenderEnergy");
                int defenderWinCount = currentEnergyPvpBattleRecord.optInt("defenderWinCount");
                String battleStatus = currentEnergyPvpBattleRecord.optString("battleStatus");
                Log.record("比赛情况🆚" + battleType + "赛" + battleStatus + "[" + attackerDisplayName + "]" + attackerWinCount + "胜(" + attackerEnergy + "g)VS[" + defenderDisplayName + "]" + attackerWinCount + "胜(" + defenderEnergy + "g)");
                //领取奖励
                if (currentEnergyPvpBattleRecord.optBoolean("hasReward")) {
                    jo = new JSONObject(AntForestRpcCall.receivePvpRewards());
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        return;
                    }
                    if (jo.has("receivedRewards")) {
                        JSONArray receivedRewards = jo.getJSONArray("receivedRewards");
                        for (int i = 0; i < receivedRewards.length(); i++) {
                            JSONObject reward = receivedRewards.getJSONObject(i);
                            String rewardName = reward.getString("rewardName");
                            String rewardType = reward.getString("rewardType");
                            if ("energy".equals(rewardType)) {
                                int energy = reward.getInt("energy");
                                Log.forest("领取奖励🎖️1V1[" + rewardName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                Statistics.addData(Statistics.DataType.COLLECTED, energy);
                            } else {
                                Log.forest("领取奖励🎖️1V1[" + rewardName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                            }
                        }
                    }

                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "queryPvpHomeInfo err:");
            Log.printStackTrace(TAG, th);
        }

    }

    private static void receivePvpRewards() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.checkRewardqueryMiscInfo());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (jo.has("combineHandlerVOMap")) {
                JSONObject combineHandlerVOMap = jo.getJSONObject("combineHandlerVOMap");
                if (combineHandlerVOMap.has("energyPvpInfo")) {
                    JSONObject energyPvpInfo = combineHandlerVOMap.getJSONObject("energyPvpInfo");
                    //领取奖励
                    if (energyPvpInfo.optBoolean("hasReward")) {
                        jo = new JSONObject(AntForestRpcCall.receivePvpRewards());
                        if (!MessageUtil.checkResultCode(TAG, jo)) {
                            return;
                        }
                        if (jo.has("receivedRewards")) {
                            JSONArray receivedRewards = jo.getJSONArray("receivedRewards");
                            for (int i = 0; i < receivedRewards.length(); i++) {
                                JSONObject reward = receivedRewards.getJSONObject(i);
                                String rewardName = reward.getString("rewardName");
                                String rewardType = reward.getString("rewardType");
                                if ("energy".equals(rewardType)) {
                                    int energy = reward.getInt("energy");
                                    Log.forest("领取奖励🎖️1V1[" + rewardName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                    Statistics.addData(Statistics.DataType.COLLECTED, energy);
                                } else {
                                    Log.forest("领取奖励🎖️1V1[" + rewardName + "]#[" + UserIdMap.getShowName(UserIdMap.getCurrentUid()) + "]");
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable th) {
            Log.i(TAG, "receivePvpRewards err:");
            Log.printStackTrace(TAG, th);
        }
    }


    private void dress() {
        String dressDetail = dressDetailList.getValue();
        if (dressDetail.isEmpty()) {
            setDressDetail(getDressDetail().toString());
        } else {
            checkDressDetail(dressDetail);
        }
    }

    private JSONObject getDressDetail() {
        JSONObject dressDetail = new JSONObject();
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.queryHomePage());
            JSONArray ja = jo.getJSONObject("indexDressVO").getJSONArray("dressDetailList");
            for (int i = 0; i < ja.length(); i++) {
                jo = ja.getJSONObject(i);
                String position = jo.getString("position");
                String batchType = jo.getString("batchType");
                dressDetail.put(position, batchType);
            }
        } catch (Throwable th) {
            Log.i(TAG, "getDressDetail err:");
            Log.printStackTrace(TAG, th);
        }
        return dressDetail;
    }

    private void setDressDetail(String dressDetail) {
        dressDetailList.setValue(dressDetail);
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.forest("装扮保护🔐皮肤保存,Sesame-Wu 将为你持续保护!");
        }
    }

    private void removeDressDetail(String position) {
        JSONObject jo = getDressDetail();
        jo.remove(position);
        setDressDetail(jo.toString());
    }

    private void checkDressDetail(String dressDetail) {
        String[] positions = {"tree__main", "bg__sky_0", "bg__sky_cloud", "bg__ground_a", "bg__ground_b", "bg__ground_c"};
        try {
            boolean isDressExchanged = false;
            JSONObject jo = new JSONObject(dressDetail);
            for (String position : positions) {
                String batchType = "";
                if (jo.has(position)) {
                    batchType = jo.getString(position);
                }
                if (queryUserDressForBackpack(dressMap.get(position), batchType)) {
                    isDressExchanged = true;
                }
            }
            if (isDressExchanged) {
                Log.forest("装扮保护🔐皮肤修改,Sesame-Wu 已为你自动恢复!");
            }
        } catch (Throwable th) {
            Log.i(TAG, "checkDressDetail err:");
            Log.printStackTrace(TAG, th);
        }
    }

    private Boolean queryUserDressForBackpack(String positionType, String batchType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.listUserDressForBackpack(positionType));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return false;
            }
            JSONArray userHoldDressVOList = jo.getJSONArray("userHoldDressVOList");
            boolean isTakeOff = false;
            for (int i = 0; i < userHoldDressVOList.length(); i++) {
                jo = userHoldDressVOList.getJSONObject(i);
                if (jo.optInt("remainNum", 1) == 0) {
                    if (batchType.equals(jo.getString("batchType"))) {
                        return false;
                    }
                    String position = jo.getJSONArray("posList").getString(0);
                    isTakeOff = takeOffDress(jo.getString("dressType"), position);
                } else if (batchType.equals(jo.getString("batchType"))) {
                    return wearDress(jo.getString("dressType"));
                }
            }

            if (!batchType.isEmpty()) {
                removeDressDetail(dressMap.get(positionType));
                Log.forest("装扮保护🔐皮肤过期,Sesame-Wu 已为你恢复默认!");
            }
            return isTakeOff;
        } catch (Throwable th) {
            Log.i(TAG, "queryUserDressForBackpack err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private Boolean wearDress(String dressType) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.wearDress(dressType));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.i(TAG, "wearDress err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    private Boolean takeOffDress(String dressType, String position) {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.takeOffDress(dressType, position));
            return MessageUtil.checkResultCode(TAG, jo);
        } catch (Throwable th) {
            Log.i(TAG, "takeOffDress err:");
            Log.printStackTrace(TAG, th);
        }
        return false;
    }

    /**
     * The enum Collect status.
     */
    public enum CollectStatus {
        /**
         * Available collect status.
         */
        AVAILABLE,
        /**
         * Waiting collect status.
         */
        WAITING,
        /**
         * Insufficient collect status.
         */
        INSUFFICIENT,
        /**
         * Robbed collect status.
         */
        ROBBED
    }

    /**
     * The type Bubble timer task.
     */
    private class BubbleTimerTask extends ChildModelTask {

        /**
         * The User id.
         */
        private final String userId;

        /**
         * The Bubble id.
         */
        private final long bubbleId;

        /**
         * The ProduceTime.
         */
        private final long produceTime;
        private final String userName;

        /**
         * Instantiates a new Bubble timer task.
         */
        BubbleTimerTask(String ui, long bi, long pt, String un) {
            super(AntForestV2.getBubbleTimerTid(ui, bi), pt - advanceTimeInt);
            userId = ui;
            bubbleId = bi;
            produceTime = pt;
            userName = un;
        }

        @Override
        public Runnable setRunnable() {
            return () -> {
                //String userName = UserIdMap.getMaskName(userId);
                int averageInteger = offsetTimeMath.getAverageInteger();
                long readyTime = produceTime - advanceTimeInt + averageInteger - delayTimeMath.getAverageInteger() - System.currentTimeMillis() + 70;
                if (readyTime > 0) {
                    try {
                        Thread.sleep(readyTime);
                    } catch (InterruptedException e) {
                        Log.i("终止[" + userName + "]蹲点收取任务, 任务ID[" + getId() + "]");
                        return;
                    }
                }
                Log.record("执行蹲点收取[" + userName + "]" + "时差[" + averageInteger + "]ms" + "提前[" + advanceTimeInt + "]ms");
                collectEnergy(new CollectEnergyEntity(userId, null, AntForestRpcCall.getCollectEnergyRpcEntity(null, userId, bubbleId)), userName);
            };
        }
    }

    public static String getBubbleTimerTid(String ui, long bi) {
        return "BT|" + ui + "|" + bi;
    }

    public enum ItemStatus {
        NO_ENOUGH_POINT, NO_ENOUGH_STOCK, REACH_LIMIT, SECKILL_NOT_BEGIN, SECKILL_HAS_END, HAS_NEVER_EXPIRE_DRESS;

        public static final String[] nickNames = {"活力值不足", "库存量不足", "兑换达上限", "秒杀未开始", "秒杀已结束", "不限时皮肤"};

        public String nickName() {
            return nickNames[ordinal()];
        }
    }

    public enum PropGroup {
        shield, boost, doubleClick, energyRain, vitalitySignDouble, stealthCard, robExpandCard;

        public static final String[] nickNames = {"能量保护罩", "时光加速器", "能量双击卡", "能量雨卡", "活力翻倍卡", "隐身卡", "能量翻倍卡"};

        public String nickName() {
            return nickNames[ordinal()];
        }
    }

    public interface WaterFriendType {

        int WATER_00 = 0;
        int WATER_10 = 1;
        int WATER_18 = 2;
        int WATER_33 = 3;
        int WATER_66 = 4;

        String[] nickNames = {"不浇水", "浇水10克", "浇水18克", "浇水33克", "浇水66克"};
        int[] waterEnergy = {0, 10, 18, 33, 66};
    }

    public interface HelpFriendCollectType {

        int NONE = 0;
        int HELP = 1;
        int NOT_HELP = 2;

        String[] nickNames = {"不复活能量", "复活已选好友", "复活未选好友"};
    }

    public interface UsePropType {

        int CLOSE = 0;
        int ALL = 1;
        int ONLY_LIMIT_TIME = 2;

        String[] nickNames = {"关闭", "所有道具", "限时道具"};
    }

    public interface CollectSelfType {
        int ALL = 0;
        int OVER_THRESHOLD = 1;
        int BELOW_THRESHOLD = 2;

        String[] nickNames = {"所有", "大于阈值", "小于阈值"};
    }

    public interface whackModeNames {
        int CLOSE = 0;
        int WHACK_MODE_COMPATIBLE = 1;
        int WHACK_MODE_AGGRESSIVE = 2;
        String[] nickNames = {"关闭", "兼容模式", "激进模式"};
    }
}
