package com.surexu.sesame.model.task.antMember;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.surexu.sesame.data.ConfigV2;
import com.surexu.sesame.data.ModelFields;

import com.surexu.sesame.data.ModelGroup;
import com.surexu.sesame.data.modelFieldExt.BooleanModelField;
import com.surexu.sesame.data.modelFieldExt.SelectModelField;
import com.surexu.sesame.data.task.ModelTask;
import com.surexu.sesame.entity.AlipayAntMemberTaskList;
import com.surexu.sesame.entity.AlipayMemberCreditSesameTaskList;
import com.surexu.sesame.entity.MemberBenefit;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.model.base.TaskCommon;
import com.surexu.sesame.model.base.TaskAlternative;
import com.surexu.sesame.model.extensions.ExtensionsHandle;
import com.surexu.sesame.model.task.antGame.GameCenterPlayRpcCall;
import com.surexu.sesame.model.task.antOrchard.AntOrchardRpcCall;
import com.surexu.sesame.util.*;
import com.surexu.sesame.util.idMap.AlchemyAutoBlacklistMap;
import com.surexu.sesame.util.idMap.AntFarmDoFarmTaskListMap;
import com.surexu.sesame.util.idMap.AntMemberTaskListMap;
import com.surexu.sesame.util.idMap.MemberBenefitIdMap;
import com.surexu.sesame.util.idMap.MemberCreditSesameTaskListMap;
import com.surexu.sesame.util.idMap.PromiseSimpleTemplateIdMap;
import com.surexu.sesame.util.idMap.UserIdMap;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class AntMember extends ModelTask {
    private static final String TAG = AntMember.class.getSimpleName();

    // Sesame-AG 风格：芝麻炼金攒粒任务默认黑名单
    // 这些模板/任务需要真实游戏内事件或其他条件才能完成，通过 RPC 调用必然返回 ILLEGAL_ARGUMENT / PROMISE_TEMPLATE_NOT_EXIST
    private static final Set<String> ALCHENY_BLACKLIST_TEMPLATE_IDS = new HashSet<>(java.util.Arrays.asList(
            "alchemy_check_in_subscribe_task", // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST（生活记录模板不存在）
            "hjwf_xiangjiangshikaipao_renwu",   // 游戏事件不能由 pushActivity 完成
            "hjwf_zcylt_chongzhi",              // 真实充值不能由 pushActivity 完成
            "hjwf_langmancanting_renwu",        // 完成订单游戏事件，pushActivity 返回 ILLEGAL_ARGUMENT
            "hjwf_zcylt_zhuanhua",
            "hjwf_eduka_renwu",
            "hjwf_tbqd_qiandao_sanfang",
            "hjwf_tbbbnc_shifei_sanfang",
            "zml_check_in_subscribe_task",      // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
            "zml_set_home_task"                 // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
    ));
    private static final Set<String> ALCHENY_BLACKLIST_TITLES = new HashSet<>(java.util.Arrays.asList(
            "玩游戏赢最高8888元红包", // 动态 taskId，需游戏内事件，ILLEGAL_ARGUMENT promiseActivityExtCheck
            "订阅炼金签到提醒"        // joinActivity 返回 PROMISE_TEMPLATE_NOT_EXIST
    ));

    /** 游戏中心宝箱每日上限（首页 {@code taskModule.dailyTaskCertCntUpperLimit} 的兜底值）。 */
    private static final int GAME_CENTER_CERT_LIMIT = 30;

    /** 会员签到页任务最多翻多少页：只防服务端 pageNo 不生效时死循环。 */
    private static final int MAX_SIGN_PAGE = 8;
    
    @Override
    public String getName() {
        return "会员";
    }
    
    @Override
    public ModelGroup getGroup() {
        return ModelGroup.MEMBER;
    }
    
    private BooleanModelField AntMemberTask;
    private BooleanModelField AutoAntMemberTaskList;
    private SelectModelField AntMemberTaskList;
    private BooleanModelField memberSign;
    private BooleanModelField memberSignPageTask;              // 会员积分 | 签到页任务
    private BooleanModelField memberPointExchangeBenefit;
    private SelectModelField memberPointExchangeBenefitList;
    
    private BooleanModelField collectSesame;
    private BooleanModelField AutoMemberCreditSesameTaskList;
    private SelectModelField MemberCreditSesameTaskList;
    private BooleanModelField SesameGrowthBehavior;
    private BooleanModelField promise;
    private SelectModelField promiseList;
    private BooleanModelField KuaiDiFuLiJia;
    private BooleanModelField antInsurance;
    private SelectModelField antInsuranceOptions;
    private BooleanModelField signinCalendar;
    private BooleanModelField enableGoldTicket;
    private BooleanModelField enableGameCenter;
    private BooleanModelField memberGameParadise;  // 游戏中心 | 会员乐园浏览奖励
    private BooleanModelField merchantSignIn;
    private BooleanModelField merchantKMDK;
    private BooleanModelField enableSesameAlchemy;

    /** 备用接口同轮待核对（见 TaskAlternative.verify） */
    private static final Map<String, String> pendingVerifyTasks = new LinkedHashMap<>();

    /** 同轮核对配置（见 TaskAlternative.verify） */
    private static final TaskAlternative.VerifyConfig VERIFY_CFG = new TaskAlternative.VerifyConfig(
            "AntMember", "AntMemberTaskList", "会员任务", "游戏中心", "🎮完成", true,
            msg -> Log.member(msg));

    @Override
    public ModelFields getFields() {
        ModelFields modelFields = new ModelFields();
        modelFields.addField(AntMemberTask = new BooleanModelField("AntMemberTask", "会员任务", false));
        modelFields.addField(AutoAntMemberTaskList = new BooleanModelField("AutoAntMemberTaskList", "会员任务 | 自动黑白名单", true));
        modelFields.addField(AntMemberTaskList = new SelectModelField("AntMemberTaskList", "会员任务 | 黑名单列表", new LinkedHashSet<>(), AlipayAntMemberTaskList::getList));
        modelFields.addField(memberSign = new BooleanModelField("memberSign", "会员签到", false));
        // 签到页任务：默认开，对齐 Sesame-M 的既有行为（M 一直是启用状态）；
        // 独立成开关是为了万一它触发风控时能单独关掉，不影响「会员任务」的其它分支
        modelFields.addField(memberSignPageTask = new BooleanModelField("memberSignPageTask", "会员积分 | 签到页任务", true));
        modelFields.addField(memberPointExchangeBenefit = new BooleanModelField("memberPointExchangeBenefit", "会员积分 | 兑换权益", false));
        modelFields.addField(memberPointExchangeBenefitList = new SelectModelField("memberPointExchangeBenefitList", "会员积分 | 权益列表", new LinkedHashSet<>(), MemberBenefit::getList));
        modelFields.addField(collectSesame = new BooleanModelField("collectSesame", "芝麻粒 | 领取", false));
        modelFields.addField(AutoMemberCreditSesameTaskList = new BooleanModelField("AutoMemberCreditSesameTaskList", "芝麻粒任务 | 自动黑白名单", true));
        modelFields.addField(MemberCreditSesameTaskList = new SelectModelField("MemberCreditSesameTaskList", "芝麻粒任务 | 黑名单列表", new LinkedHashSet<>(), AlipayMemberCreditSesameTaskList::getList));
        modelFields.addField(enableSesameAlchemy = new BooleanModelField("enableSesameAlchemy", "芝麻炼金 | 开启", false));
        modelFields.addField(SesameGrowthBehavior = new BooleanModelField("SesameGrowthBehavior", "攒芝麻分进度", false));
        modelFields.addField(enableGameCenter = new BooleanModelField("enableGameCenter", "游戏中心 | 得乐园豆", false));
        modelFields.addField(memberGameParadise = new BooleanModelField("memberGameParadise", "游戏中心 | 会员乐园浏览奖励", false));
        //modelFields.addField(promise = new BooleanModelField("promise", "生活记录 | 坚持做", false));
        //modelFields.addField(promiseList = new SelectModelField("promiseList", "生活记录 | 坚持做列表", new LinkedHashSet<>(), PromiseSimpleTemplate::getList));
        modelFields.addField(KuaiDiFuLiJia = new BooleanModelField("KuaiDiFuLiJia", "我的快递 | 福利加", false));
        //modelFields.addField(antInsurance = new BooleanModelField("antInsurance", "蚂蚁保 | 开启", false));
        //modelFields.addField(antInsuranceOptions = new SelectModelField("antInsuranceOptions", "蚂蚁保 | 选项", new LinkedHashSet<>(), CustomOption::getAntInsuranceOptions));
        modelFields.addField(signinCalendar = new BooleanModelField("signinCalendar", "消费金 | 签到", false));
        modelFields.addField(enableGoldTicket = new BooleanModelField("enableGoldTicket", "黄金票 | 签到", false));
        modelFields.addField(merchantSignIn = new BooleanModelField("merchantSignIn", "商家服务 | 签到", false));
        modelFields.addField(merchantKMDK = new BooleanModelField("merchantKMDK", "商家服务 | 开门打卡", false));
        return modelFields;
    }
    
    @Override
    public Boolean check() {
        if (TaskCommon.IS_ENERGY_TIME) {
            Log.member("任务暂停⏸️蚂蚁会员:当前为仅收能量时间");
            return false;
        }
        return true;
    }
    
    @Override
    public void run() {
        try {
            //初始任务列表
            if (!Status.hasFlagToday("BlackList::initMember")) {
                initMemberTaskListMap(AutoAntMemberTaskList.getValue(), AutoMemberCreditSesameTaskList.getValue(), AntMemberTask.getValue(), collectSesame.getValue());
                Status.flagToday("BlackList::initMember");
            }
            
            if (memberSign.getValue()) {
                memberSign();
            }
            
            if (AntMemberTask.getValue()) {
                queryPointCert(1, 8);
                // 签到页任务（原先是注释状态，对齐 Sesame-M 重新启用）
                if (memberSignPageTask.getValue()) {
                    signPageTaskList();
                }
                // 签到广告任务（sourceBusiness=signInAd）
                queryAllStatusTaskList();
                // 会员任务阶段奖励：任务推进到阶段后单独可领，和上面的「领积分」是两条路
                collectMemberTaskProcessAwards();
            }
            
            if (memberPointExchangeBenefit.getValue()) {
                memberPointExchangeBenefit();
            }
            if (collectSesame.getValue()) {
                CheckInTaskRpcManager();
                collectSesame();
            }
            if (enableSesameAlchemy.getValue()) {
                doSesameAlchemy();
            }
            
            //芝麻积攒进度
            if (SesameGrowthBehavior.getValue()) {
                // if (!Status.hasFlagToday("AntMember::SesameGrowthBehavior")) {
                //完成攒进度任务
                handleGrowthGuideTasks();
                //领取进度球
                queryAndCollect();
                //    Status.flagToday("AntMember::SesameGrowthBehavior");
                //}
                
            }
            // 生活记录
            //if (promise.getValue()) {
            //    promise();
            //}
            // 我的快递任务
            if (KuaiDiFuLiJia.getValue()) {
                RecommendTask();
                OrdinaryTask();
            }
            if (enableGoldTicket.getValue()) {
                goldTicket();
            }
            //if (antInsurance.getValue()) {
            //    AntInsurance.executeTask(antInsuranceOptions.getValue());
            //}
            // 消费金签到
            if (signinCalendar.getValue()) {
                signinCalendar();
            }

            // 会员游戏乐园浏览奖励（独立闭环，不走任务流引擎）
            if (memberGameParadise.getValue()) {
                memberGameParadiseBrowseReward();
            }
            if (enableGameCenter.getValue()) {
                //检查并执行签到
                checkAndDoSignIn();
                //查询并处理任务列表
                queryAndProcessTaskList();
                //游戏中心任务奖励（整场景一键领取；游戏类任务需真实游玩才能推进，不做完成尝试）
                gameCenterTaskPrize();

                //查询玩乐豆小球列表，有则领取
                queryPointBallList();
                
            }
            if (merchantSignIn.getValue() || merchantKMDK.getValue()) {
                if (MerchantService.transcodeCheck()) {
                    if (merchantSignIn.getValue()) {
                        MerchantService.taskListQueryV2();
                    }
                    if (merchantKMDK.getValue()) {
                        MerchantService.merchantKMDK();
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    public static void initMemberTaskListMap(boolean AutoAntMemberTaskList, boolean AutoMemberCreditSesameTaskList, boolean AntMemberTask, boolean collectSesame) {
        try {
            //初始化AntMemberTaskListMap
            AntMemberTaskListMap.load();
            Set<String> blackList = new HashSet<>();
            //blackList.add("去淘金币逛一逛");
            // 可继续添加更多黑名单任务
            
            Set<String> whiteList = new HashSet<>();// 从黑名单中移除该任务
            //whiteList.add("逛一逛芝麻树");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                AntMemberTaskListMap.add(task, task);
            }
            
            JSONObject jo;
            if (AntMemberTask) {
                boolean hasNextPage = true;
                int page = 1;
                do {
                    jo = new JSONObject(AntMemberRpcCall.queryPointCert(page, 8));
                    TimeUtil.sleep(500);
                    if (!MessageUtil.checkResultCode(TAG, jo)) {
                        break;
                    }
                    hasNextPage = jo.getBoolean("hasNextPage");
                    page++;
                    JSONArray jaCertList = jo.getJSONArray("certList");
                    for (int i = 0; i < jaCertList.length(); i++) {
                        jo = jaCertList.getJSONObject(i);
                        String bizTitle = jo.getString("bizTitle");
                        AntMemberTaskListMap.add(bizTitle, bizTitle);
                    }
                }
                while (hasNextPage);
                
                jo = new JSONObject(AntMemberRpcCall.queryAllStatusTaskList());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONArray availableTaskList = jo.getJSONArray("availableTaskList");
                    for (int i = 0; i < availableTaskList.length(); i++) {
                        JSONObject task = availableTaskList.getJSONObject(i);
                        JSONObject taskConfigInfo = task.getJSONObject("taskConfigInfo");
                        String name = taskConfigInfo.getString("name");
                        AntMemberTaskListMap.add(name, name);
                    }
                    JSONArray taskHistoryList = jo.getJSONArray("taskHistoryList");
                    for (int i = 0; i < taskHistoryList.length(); i++) {
                        JSONObject task = taskHistoryList.getJSONObject(i);
                        JSONObject taskConfigInfo = task.getJSONObject("taskConfigInfo");
                        String name = taskConfigInfo.getString("name");
                        AntMemberTaskListMap.add(name, name);
                    }
                }
                
                // 游戏中心任务流里的任务也要进候选，否则用户看不到、也无法手动勾选
                // （原读取的 v4.queryTaskList 实测恒返回空 data，连带这里一直拿不到任务）
                for (int feedPage = 0; feedPage < 5; feedPage++) {
                    jo = new JSONObject(AntMemberRpcCall.gameCenterGameFeeds(feedPage, 10));
                    if (!MessageUtil.checkSuccess(TAG, jo)) {
                        break;
                    }
                    JSONObject feedsData = jo.optJSONObject("data");
                    JSONArray feedsList = feedsData == null ? null : feedsData.optJSONArray("feedsList");
                    if (feedsList == null || feedsList.length() == 0) {
                        break;
                    }
                    for (int i = 0; i < feedsList.length(); i++) {
                        JSONObject feed = feedsList.optJSONObject(i);
                        String title = feed == null ? "" : gameCenterTaskTitle(feed);
                        if (!title.isEmpty()) {
                            AntMemberTaskListMap.add(title, title);
                        }
                    }
                    if (!feedsData.optBoolean("haveNextPage", false)) {
                        break;
                    }
                }

                //保存任务到配置文件
                AntMemberTaskListMap.save();
                Log.record("同步任务🉑会员任务列表");
                
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoAntMemberTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields antMember = config.getModelFieldsMap().get("AntMember");
                    SelectModelField AntMemberTaskList = (SelectModelField) antMember.get("AntMemberTaskList");
                    if (AntMemberTaskList == null) {
                        return;
                    }
                    
                    Set<String> currentValues = AntMemberTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {
                        for (String task : blackList) {
                            if (!currentValues.contains(task)) {
                                AntMemberTaskList.add(task, 0);
                            }
                        }
                        
                        MessageUtil.registerTaskWhiteList("AntMemberTaskList", whiteList);
                    }
                    // 4. 保存配置
                    if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                        Log.record("黑白名单🈲会员任务自动设置: " + AntMemberTaskList.getValue());
                    }
                    else {
                        Log.record("会员任务黑白名单设置失败");
                    }
                }
            }
            //初始化MemberCreditSesameTaskListMap
            MemberCreditSesameTaskListMap.load();
            blackList = new HashSet<>();
            // 芝麻粒任务改走 taskFeedback 后，服务端**不校验是否真的参与过**，
            // 未报名的「去玩xx」一次即 success ⇒ 游戏/浏览/签到/组件/施肥类不再预置拉黑，
            // 全部交给任务循环自动完成。仍预置拉黑的只剩**真实交易/履约类**
            // （下单/租赁/订酒店/回收/雇佣/付钱/查车），这类没真做就申报"完成"属虚假履约，有风控风险
            blackList.add("用额度免押金下单");
            blackList.add("去租赁下单");
            blackList.add("芝麻租赁下单得芝麻粒");
            blackList.add("去飞猪订酒店");
            blackList.add("0.1元起租会员攒粒");
            blackList.add("9.9元抢租3天大疆");
            blackList.add("1分起囤神券茶咖美食");
            blackList.add("完成旧衣回收得现金");
            blackList.add("去雇佣芝麻大表鸽");
            blackList.add("送你10.6元支付红包");
            blackList.add("一键查询爱车估值");
            // 可继续添加更多黑名单任务
            
            whiteList = new HashSet<>();// 从黑名单中移除该任务
            whiteList.add("逛一逛芝麻树");
            whiteList.add("浏览15秒视频广告");
            whiteList.add("逛15秒商品橱窗");
            whiteList.add("逛一逛集汗滴找现金");
            whiteList.add("去体验先用后付");
            whiteList.add("去抛竿钓鱼");
            whiteList.add("去参与花呗活动");
            whiteList.add("坚持攒保障金");
            whiteList.add("去领支付宝积分");
            whiteList.add("去浏览租赁大促会场");
            // 可继续添加更多白名单任务
            for (String task : blackList) {
                MemberCreditSesameTaskListMap.add(task, task);
            }
            
            if (collectSesame) {
                jo = new JSONObject(AntMemberRpcCall.queryHome());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    JSONObject entrance = jo.getJSONObject("entrance");
                    if (entrance.optBoolean("openApp")) {
                        jo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
                        TimeUtil.sleep(300);
                        if (MessageUtil.checkResultCode(TAG, jo)) {
                            if (jo.has("data")) {
                                JSONObject data = jo.getJSONObject("data");
                                if (data.has("completeVOS")) {
                                    JSONArray completeVOS = data.getJSONArray("completeVOS");
                                    for (int i = 0; i < completeVOS.length(); i++) {
                                        JSONObject toCompleteVO = completeVOS.getJSONObject(i);
                                        String title = toCompleteVO.optString("title");
                                        if (title.isEmpty()) {
                                            continue;
                                        }
                                        MemberCreditSesameTaskListMap.add(title, title);
                                    }
                                }
                                if (data.has("toCompleteVOS")) {
                                    JSONArray toCompleteVOS = data.getJSONArray("toCompleteVOS");
                                    for (int i = 0; i < toCompleteVOS.length(); i++) {
                                        JSONObject toCompleteVO = toCompleteVOS.getJSONObject(i);
                                        String title = toCompleteVO.optString("title");
                                        if (title.isEmpty()) {
                                            continue;
                                        }
                                        MemberCreditSesameTaskListMap.add(title, title);
                                    }
                                }
                            }
                        }
                    }
                }
                //保存任务到配置文件
                MemberCreditSesameTaskListMap.save();
                Log.record("同步任务🉑会员芝麻信用任务芝麻粒列表");
                
                //自动按模块初始化设定调整黑名单和白名单
                if (AutoMemberCreditSesameTaskList) {
                    // 初始化黑白名单（使用集合统一操作）
                    ConfigV2 config = ConfigV2.INSTANCE;
                    ModelFields antMember = config.getModelFieldsMap().get("AntMember");
                    SelectModelField MemberCreditSesameTaskList = (SelectModelField) antMember.get("MemberCreditSesameTaskList");
                    if (MemberCreditSesameTaskList == null) {
                        return;
                    }
                    
                    Set<String> currentValues = MemberCreditSesameTaskList.getValue();//该处直接返回列表地址
                    if (currentValues != null) {
                        for (String task : blackList) {
                            if (!currentValues.contains(task)) {
                                MemberCreditSesameTaskList.add(task, 0);
                            }
                        }
                        
                        MessageUtil.registerTaskWhiteList("MemberCreditSesameTaskList", whiteList);
                    }
                    // 4. 保存配置
                    if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
                        Log.record("黑白名单🈲会员芝麻信用任务芝麻粒自动设置: " + MemberCreditSesameTaskList.getValue());
                    }
                    else {
                        Log.record("会员芝麻信用任务芝麻粒黑白名单设置失败");
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "initMemberTaskListMap err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    private void memberSign() {
        try {
            if (!Status.hasFlagToday("member::sign")) {
                JSONObject jo = new JSONObject(AntMemberRpcCall.queryMemberSigninCalendar());
                TimeUtil.sleep(500);
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    if (jo.getBoolean("autoSignInSuccess")) {
                        Log.member("会员任务📅签到[坚持" + jo.getString("signinSumDay") + "天]#获得[" + jo.getString("signinPoint") + "积分]");
                    }
                    Status.flagToday("member::sign");
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "memberSign err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    private void queryPointCert(int page, int pageSize) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryPointCert(page, pageSize));
            TimeUtil.sleep(500);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            boolean hasNextPage = jo.getBoolean("hasNextPage");
            JSONArray jaCertList = jo.getJSONArray("certList");
            for (int i = 0; i < jaCertList.length(); i++) {
                jo = jaCertList.getJSONObject(i);
                String bizTitle = jo.getString("bizTitle");
                //黑名单任务跳过
                if (AntMemberTaskList.getValue().contains(bizTitle)) {
                    continue;
                }
                String id = jo.getString("id");
                int pointAmount = jo.getInt("pointAmount");
                jo = new JSONObject(AntMemberRpcCall.receivePointByUser(id));
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    Log.member("会员任务🎖️领取[" + bizTitle + "]奖励#获得[" + pointAmount + "积分]");
                }
            }
            if (hasNextPage) {
                queryPointCert(page + 1, pageSize);
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "queryPointCert err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 签到页任务（会员积分）：拉取签到页任务列表并逐类推进。
     * <p>BROWSE 类走 {@link #doBrowseTask(JSONArray)}，其余类型交给
     * {@link ExtensionsHandle#handleAlphaRequest} 的 doMoreTask 分支；
     * 只要这一轮有推进就重新拉一次，直到没有可做的为止。
     * <p>RPC 之间用 {@link #randomSleep(int, int)} 拉开随机间隔，模拟人工节奏、降低风控。
     */
    private void signPageTaskList() {
        try {
            // 分页拉取签到页任务。原先无 pageNo，签到页任务多于 1 页时只会处理第一页。
            // 服务端若忽略 pageNo（恒返回第一页），下面的 id 去重会在第二页立刻结束，
            // 行为与原先「只查一次」完全一致，故本改动是安全的。
            String session = java.util.UUID.randomUUID().toString();
            Set<Long> seenTaskIds = new HashSet<>();
            for (int page = 1; page <= MAX_SIGN_PAGE; page++) {
                boolean hasNextPage = false;
                while (true) {
                    JSONObject jo = new JSONObject(AntMemberRpcCall.signPageTaskList(page, session));
                    randomSleep(1200, 2200);
                    boolean doubleCheck = false;
                    if (!MessageUtil.checkResultCode(TAG + " signPageTaskList", jo)) {
                        return;
                    }
                    if (!jo.has("categoryTaskList")) {
                        return;
                    }
                    JSONArray categoryTaskList = jo.getJSONArray("categoryTaskList");
                    // 本页是否出现了没见过的任务 id：一个都没有说明分页到底了
                    boolean anyNewTask = false;
                    for (int i = 0; i < categoryTaskList.length(); i++) {
                        JSONObject category = categoryTaskList.getJSONObject(i);
                        JSONArray taskList = category.getJSONArray("taskList");
                        for (int k = 0; k < taskList.length(); k++) {
                            JSONObject task = taskList.optJSONObject(k);
                            JSONObject cfg = task == null ? null : task.optJSONObject("taskConfigInfo");
                            if (cfg != null && cfg.has("id") && seenTaskIds.add(cfg.getLong("id"))) {
                                anyNewTask = true;
                            }
                        }
                        String type = category.getString("type");
                        if (Objects.equals("BROWSE", type)) {
                            // doBrowseTask 内部已按 applyTask / executeTask 逐步随机延迟
                            doubleCheck = doBrowseTask(taskList);
                        }
                        else {
                            ExtensionsHandle.handleAlphaRequest("antMember", "doMoreTask", category);
                            randomSleep(1500, 3000);
                        }
                    }
                    if (doubleCheck) {
                        // 本页仍有任务被推进：重查本页，直到没有新进展
                        continue;
                    }
                    hasNextPage = anyNewTask;
                    break;
                }
                if (!hasNextPage) {
                    break;
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "signPageTaskList err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 会员任务阶段奖励领取。
     * <p>会员任务推进到某个阶段后，服务端会在 {@code availableTaskProcessList} 下发独立的
     * 阶段奖励条目（{@code stageProcessList[].stageStatus == "COMPLETE"}），
     * 这些奖励<b>不会</b>被 {@code queryPointCert} 的「一键领积分」覆盖，需要单独调 award 接口。
     * <p>同一 ({@code taskProcessId}, {@code awardRelatedOutBizNo}) 只领一次（服务端在
     * {@code retryable=false} 时会拒绝重复请求，去重能省掉这些噪音）。
     * <p>每领一项之间随机等待，模拟人工节奏。
     */
    private void collectMemberTaskProcessAwards() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryMemberTaskProcessList());
            randomSleep(800, 1800);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray taskProcessList = jo.optJSONArray("availableTaskProcessList");
            if (taskProcessList == null || taskProcessList.length() == 0) {
                return;
            }
            Set<String> seen = new HashSet<>();
            int claimedCount = 0;
            for (int i = 0; i < taskProcessList.length(); i++) {
                JSONObject taskProcess = taskProcessList.optJSONObject(i);
                if (taskProcess == null) {
                    continue;
                }
                String taskProcessId = taskProcess.optString("taskProcessId", "");
                if (taskProcessId.isEmpty()) {
                    continue;
                }
                JSONObject taskConfig = taskProcess.optJSONObject("taskConfig");
                String title = taskConfig != null ? taskConfig.optString("title", "") : "";
                if (title.isEmpty()) {
                    title = taskProcess.optString("title", "会员任务");
                }
                JSONArray stageProcessList = taskProcess.optJSONArray("stageProcessList");
                if (stageProcessList == null) {
                    continue;
                }
                for (int k = 0; k < stageProcessList.length(); k++) {
                    JSONObject stage = stageProcessList.optJSONObject(k);
                    if (stage == null) {
                        continue;
                    }
                    if (!"COMPLETE".equalsIgnoreCase(stage.optString("stageStatus", ""))) {
                        continue;
                    }
                    String awardOutBizNo = stage.optString("awardRelatedOutBizNo", "");
                    if (awardOutBizNo.isEmpty()) {
                        continue;
                    }
                    if (!seen.add(taskProcessId + "#" + awardOutBizNo)) {
                        continue;
                    }
                    int stageIndex = stage.optInt("stageIndex", k + 1);
                    int awardPoint = stage.optInt("awardPoint", 0);
                    JSONObject awardJo = new JSONObject(
                            AntMemberRpcCall.awardMemberTaskProcess(awardOutBizNo, taskProcessId));
                    randomSleep(800, 1800);
                    if (!MessageUtil.checkResultCode(TAG, awardJo)) {
                        Log.record("会员任务[" + title + "]#阶段奖励领取失败");
                        continue;
                    }
                    claimedCount++;
                    String stageSuffix = stageIndex > 0 ? "-阶段" + stageIndex : "";
                    if (awardPoint > 0) {
                        Log.member("会员任务🎖️[" + title + stageSuffix + "]#获得[" + awardPoint + "积分]");
                    } else {
                        Log.member("会员任务🎖️[" + title + stageSuffix + "]#领取阶段奖励");
                    }
                }
            }
            if (claimedCount > 0) {
                Log.record("会员任务[阶段奖励]#本次领取" + claimedCount + "项");
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "collectMemberTaskProcessAwards err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 查询所有状态任务列表（签到广告任务，sourceBusiness=signInAd）
     */
    private void queryAllStatusTaskList() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryAllStatusTaskList());
            randomSleep(1200, 2200);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray availableTaskList = jo.getJSONArray("availableTaskList");
            if (doBrowseTask(availableTaskList)) {
                queryAllStatusTaskList();
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "queryAllStatusTaskList err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    // 生活记录
    private void promise() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.promiseQueryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data");
            JSONArray promiseSimpleTemplates = jo.getJSONArray("promiseSimpleTemplates");
            for (int i = 0; i < promiseSimpleTemplates.length(); i++) {
                jo = promiseSimpleTemplates.getJSONObject(i);
                String templateId = jo.getString("templateId");
                String promiseName = jo.getString("promiseName");
                String status = jo.getString("status");
                if ("un_join".equals(status) && promiseList.getValue().contains(templateId)) {
                    promiseJoin(querySingleTemplate(templateId));
                }
                PromiseSimpleTemplateIdMap.add(templateId, promiseName);
            }
            PromiseSimpleTemplateIdMap.save(UserIdMap.getCurrentUid());
        }
        catch (Throwable t) {
            Log.i(TAG, "promise err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    private JSONObject querySingleTemplate(String templateId) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.querySingleTemplate(templateId));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return null;
            }
            jo = jo.getJSONObject("data");
            JSONObject result = new JSONObject();
            
            result.put("joinFromOuter", false);
            result.put("templateId", jo.getString("templateId"));
            result.put("autoRenewStatus", Boolean.valueOf(jo.getString("autoRenewStatus")));
            
            JSONObject joinGuarantyRule = jo.getJSONObject("joinGuarantyRule");
            joinGuarantyRule.put("selectValue", joinGuarantyRule.getJSONArray("canSelectValues").getString(0));
            joinGuarantyRule.remove("canSelectValues");
            result.put("joinGuarantyRule", joinGuarantyRule);
            
            JSONObject joinRule = jo.getJSONObject("joinRule");
            joinRule.put("selectValue", joinRule.getJSONArray("canSelectValues").getString(0));
            joinRule.remove("joinRule");
            result.put("joinRule", joinRule);
            
            JSONObject periodTargetRule = jo.getJSONObject("periodTargetRule");
            periodTargetRule.put("selectValue", periodTargetRule.getJSONArray("canSelectValues").getString(0));
            periodTargetRule.remove("canSelectValues");
            result.put("periodTargetRule", periodTargetRule);
            
            JSONObject dataSourceRule = jo.getJSONObject("dataSourceRule");
            dataSourceRule.put("selectValue", dataSourceRule.getJSONArray("canSelectValues").getJSONObject(0).getString("merchantId"));
            dataSourceRule.remove("canSelectValues");
            result.put("dataSourceRule", dataSourceRule);
            return result;
        }
        catch (Throwable t) {
            Log.i(TAG, "querySingleTemplate err:");
            Log.printStackTrace(TAG, t);
        }
        return null;
    }
    
    private void promiseJoin(JSONObject data) {
        if (data == null) {
            return;
        }
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.promiseJoin(data));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            jo = jo.getJSONObject("data");
            String promiseName = jo.getString("promiseName");
            Log.member("生活记录📝加入[" + promiseName + "]");
        }
        catch (Throwable t) {
            Log.i(TAG, "promiseJoin err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    // 查询持续做明细任务
    private JSONObject promiseQueryDetail(String recordId) throws JSONException {
        JSONObject jo = new JSONObject(AntMemberRpcCall.promiseQueryDetail(recordId));
        if (!jo.optBoolean("success")) {
            return null;
        }
        return jo;
    }
    
    // 蚂蚁积分-做浏览任务
    private Boolean doBrowseTask(JSONArray taskList) {
        boolean doubleCheck = false;
        try {
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.getJSONObject(i);
                if (task.getBoolean("hybrid")) {
                    int periodCurrentCount = Integer.parseInt(task.getJSONObject("extInfo").getString("PERIOD_CURRENT_COUNT"));
                    int periodTargetCount = Integer.parseInt(task.getJSONObject("extInfo").getString("PERIOD_TARGET_COUNT"));
                    int count = periodTargetCount > periodCurrentCount ? periodTargetCount - periodCurrentCount : 0;
                    if (count > 0) {
                        doubleCheck = doubleCheck || doBrowseTask(task, periodTargetCount, periodTargetCount);
                    }
                }
                else {
                    doubleCheck = doubleCheck || doBrowseTask(task, 1, 1);
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "doBrowseTask err:");
            Log.printStackTrace(TAG, t);
        }
        return doubleCheck;
    }
    
    private Boolean doBrowseTask(JSONObject task, int left, int right) {
        boolean doubleCheck = false;
        try {
            JSONObject taskConfigInfo = task.getJSONObject("taskConfigInfo");
            String name = taskConfigInfo.getString("name");
            //黑名单任务跳过
            if (AntMemberTaskList.getValue().contains(name)) {
                return false;
            }
            Long id = taskConfigInfo.getLong("id");
            String awardParamPoint = taskConfigInfo.getJSONObject("awardParam").getString("awardParamPoint");
            JSONArray targetBusinessArr = taskConfigInfo.optJSONArray("targetBusiness");
            if (targetBusinessArr == null || targetBusinessArr.length() == 0) {
                Log.record("会员浏览任务[" + name + "]无 targetBusiness 配置，跳过");
                return false;
            }
            String targetBusiness = targetBusinessArr.getString(0);
            for (int i = left; i <= right; i++) {
                JSONObject jo = new JSONObject(AntMemberRpcCall.applyTask(name, id));
                sleepWithJitter(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    continue;
                }
                String[] targetBusinessArray = targetBusiness.split("#");
                String bizType;
                String bizParam;
                String bizSubType;
                if (targetBusinessArray.length > 2) {
                    bizType = targetBusinessArray[0];
                    bizSubType = targetBusinessArray[1];
                    bizParam = targetBusinessArray[2];
                }
                else {
                    bizType = "BROWSE";
                    bizSubType = targetBusinessArray[0];
                    bizParam = targetBusinessArray[1];
                }
                jo = new JSONObject(AntMemberRpcCall.executeTask(bizParam, bizSubType, bizType));
                sleepWithJitter(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    continue;
                }
                String ex = left == right && left == 1 ? "" : "(" + (i + 1) + "/" + right + ")";
                Log.member("会员任务🎖️完成[" + name + ex + "]#获得[" + awardParamPoint + "积分]");
                doubleCheck = true;
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "doBrowseTask err:");
            Log.printStackTrace(TAG, t);
        }
        return doubleCheck;
    }
    
    private void goldTicket() {
        try {
            // 签到
            //已失效
            //goldBillCollect("\"campId\":\"CP1417744\",\"directModeDisableCollect\":true,\"from\":\"antfarm\",");
            // 收取其他
            //goldBillCollect("");
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 芝麻分任务处理（每日问答、公益任务、芭芭农场施肥等）
     */
    private void handleGrowthGuideTasks() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject root = new JSONObject(AntMemberRpcCall.queryGrowthBehaviorToDoList());
            if (!MessageUtil.checkResultCode(TAG, root)) {
                return;
            }
            
            // 待处理任务列表
            JSONArray toDoList = root.optJSONArray("toDoList");
            int toDoCount = toDoList == null ? 0 : toDoList.length();
            if (toDoList == null || toDoCount == 0) {
                return;
            }
            
            for (int i = 0; i < toDoList.length(); i++) {
                JSONObject task = toDoList.optJSONObject(i);
                if (task == null) {
                    continue;
                }
                
                String behaviorId = task.optString("behaviorId", "");
                String title = task.optString("title", "");
                String status = task.optString("status", "");
                String subTitle = task.optString("subTitle", "");
                
                // 公益类任务（待领取）
                if ("wait_receive".equals(status)) {
                    String openResp = AntMemberRpcCall.openBehaviorCollect(behaviorId);
                    JSONObject openJo = new JSONObject(openResp);
                    if (MessageUtil.checkResultCode(TAG, openJo)) {
                        Log.member("攒芝麻分🧾任务领取：" + title);
                    }
                    continue;
                }
                
                // 每日问答
                if ("meiriwenda".equals(behaviorId) && "wait_doing".equals(status)) {
                    if (subTitle.contains("今日已参与")) {
                        Log.member("攒芝麻分🧾[每日问答] " + subTitle + "（跳过答题）");
                        continue;
                    }
                    
                    // 查询题目
                    JSONObject quizJo = new JSONObject(AntMemberRpcCall.queryDailyQuiz(behaviorId));
                    if (!MessageUtil.checkSuccess(TAG, quizJo)) {
                        continue;
                    }
                    JSONObject data = quizJo.optJSONObject("data");
                    if (data == null) {
                        continue;
                    }
                    
                    JSONObject qVo = data.optJSONObject("questionVo");
                    if (qVo == null) {
                        continue;
                    }
                    
                    JSONObject rightAnswer = qVo.optJSONObject("rightAnswer");
                    if (rightAnswer == null) {
                        continue;
                    }
                    
                    long bizDate = data.optLong("bizDate", 0L);
                    String questionId = qVo.optString("questionId", "");
                    String questionContent = qVo.optString("questionContent", "");
                    String answerId = rightAnswer.optString("answerId", "");
                    String answerContent = rightAnswer.optString("answerContent", "");
                    
                    if (bizDate <= 0 || questionId.isEmpty() || answerId.isEmpty()) {
                        continue;
                    }
                    
                    // 提交答案
                    JSONObject pushJo = new JSONObject(AntMemberRpcCall.pushDailyQuizAnswer(behaviorId, bizDate, answerId, questionId, "RIGHT"));
                    if (MessageUtil.checkResultCode(TAG, pushJo)) {
                        Log.member("攒芝麻分🎖️[每日答题成功] " + questionContent + " | 答案=" + answerContent + "(" + answerId + ")" + (subTitle.isEmpty() ? "" : " | " + subTitle));
                    }
                }
                
                // 视频问答
                if ("shipingwenda".equals(behaviorId) && "wait_doing".equals(status)) {
                    long bizDate = System.currentTimeMillis();
                    String questionId = "question3";
                    String answerId = "A";
                    String answerType = "RIGHT";
                    
                    jo = new JSONObject(AntMemberRpcCall.pushDailyQuizAnswer(behaviorId, bizDate, answerId, questionId, answerType));
                    
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.member("攒芝麻分🎖️[视频问答提交成功]");
                    }
                }
                
                // 芭芭农场施肥
                if ("babanongchang_7d".equals(behaviorId) && "wait_doing".equals(status)) {
                    
                    // 获取WUA
                    String wua = getWuaByReflection();
                    String source = "DNHZ_NC_zhimajingnangSF";
                    
                    JSONObject spreadManureData = new JSONObject(AntOrchardRpcCall.orchardSpreadManure("main", false, wua));
                    
                    if (!"100".equals(spreadManureData.optString("resultCode"))) {
                        continue;
                    }
                    
                    String taobaoDataStr = spreadManureData.optString("taobaoData", "");
                    if (taobaoDataStr.isEmpty()) {
                        continue;
                    }
                    
                    JSONObject spreadTaobaoData = new JSONObject(taobaoDataStr);
                    
                    JSONObject currentStage = spreadTaobaoData.optJSONObject("currentStage");
                    if (currentStage == null) {
                        Log.error(TAG + "GrowthGuideTasks" + "芭芭农场[缺少currentStage]");
                        continue;
                    }
                    
                    String stageText = currentStage.optString("stageText", "");
                    JSONObject statistics = spreadTaobaoData.optJSONObject("statistics");
                    int dailyAppWateringCount = statistics == null ? 0 : statistics.optInt("dailyAppWateringCount", 0);
                    
                    Log.farm("芭芭农场🌳施肥" + dailyAppWateringCount + "次[" + stageText + "]");
                    Log.member("攒芝麻分🎖️芭芭农场施肥[" + title + "]已施肥" + dailyAppWateringCount + "次");
                    
                }
            }
        }
        catch (Throwable e) {
            Log.printStackTrace(TAG + ".handleGrowthGuideTasks", e);
        }
    }
    
    // 在antMember任意类中添加反射调用方法
    private String getWuaByReflection() {
        try {
            // 1. 获取AntOrchard类
            Class<?> antOrchardClass = Class.forName("com.surexu.sesame.model.task.antOrchard.AntOrchard");
            // 2. 实例化类（若方法是静态的，无需实例化）
            Object antOrchardInstance = antOrchardClass.newInstance();
            // 3. 获取私有方法getWua()
            java.lang.reflect.Method getWuaMethod = antOrchardClass.getDeclaredMethod("getWua");
            // 4. 取消访问检查
            getWuaMethod.setAccessible(true);
            // 5. 调用方法并返回结果
            return (String) getWuaMethod.invoke(antOrchardInstance);
        }
        catch (ClassNotFoundException e) {
            Log.error("未找到AntOrchard类" + e);
        }
        catch (NoSuchMethodException e) {
            Log.error("未找到getWua方法" + e);
        }
        catch (IllegalAccessException | InstantiationException | java.lang.reflect.InvocationTargetException e) {
            Log.error("调用getWua方法失败" + e);
        }
        return "";
    }
    
    public static void queryAndCollect() {
        try {
            // 1. 查询进度球状态
            String queryResp = AntMemberRpcCall.queryScoreProgress();
            if (queryResp == null || queryResp.isEmpty()) {
                return;
            }
            
            JSONObject json = new JSONObject(queryResp);
            
            // 检查 success
            if (!MessageUtil.checkSuccess(TAG, json)) {
                return;
            }
            
            JSONObject totalWait = json.optJSONObject("totalWaitProcessVO");
            if (totalWait == null) {
                return;
            }
            
            JSONArray idList = totalWait.optJSONArray("totalProgressIdList");
            if (idList == null || idList.length() == 0) {
                return;
            }
            
            // 直接传 JSONArray
            String collectResp = AntMemberRpcCall.collectProgressBall(idList);
            if (collectResp == null) {
                return;
            }
            
            JSONObject collectJson = new JSONObject(collectResp);
            int collectedAccelerateProgress = collectJson.optInt("collectedAccelerateProgress", -1);
            int currentAccelerateValue = collectJson.optInt("currentAccelerateValue", 0);
            int totalAccelerateProgress = collectJson.optInt("totalAccelerateProgress", 0);
            Log.member("攒芝麻分🎁领取#本次加速进度:" + collectedAccelerateProgress + "(总" + totalAccelerateProgress + "%)加速倍率:" + currentAccelerateValue);
        }
        catch (JSONException e) {
            Log.printStackTrace(TAG + "queryAndCollect JSON err", e);
        }
        catch (Exception e) {
            Log.printStackTrace(TAG + "queryAndCollect err", e);
        }
    }
    
    /**
     * 收取黄金票
     */
    private void goldBillCollect(String signInfo) {
        try {
            String str = AntMemberRpcCall.goldBillCollect(signInfo);
            JSONObject jsonObject = new JSONObject(str);
            if (!jsonObject.optBoolean("success")) {
                Log.i(TAG + ".goldBillCollect.goldBillCollect", jsonObject.optString("resultDesc"));
                return;
            }
            JSONObject object = jsonObject.getJSONObject("result");
            JSONArray jsonArray = object.getJSONArray("collectedList");
            int length = jsonArray.length();
            if (length == 0) {
                return;
            }
            for (int i = 0; i < length; i++) {
                Log.member("黄金票🙈[" + jsonArray.getString(i) + "]");
            }
            Log.member("黄金票🏦本次总共获得[" + JsonUtil.getValueByPath(object, "collectedCamp.amount") + "]");
        }
        catch (Throwable th) {
            Log.i(TAG, "signIn err:");
            Log.printStackTrace(TAG, th);
        }
    }
    //游戏中心任务
    
    /**
     * 批量领取玩乐豆
     */
    public static void batchReceivePointBall() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.batchReceivePointBall());
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject dataObj = jsonObject.getJSONObject("data");
                String totalAmount = dataObj.getString("totalAmount");
                Log.member("游戏中心🎮批量领取#获得[" + totalAmount + "玩乐豆]");
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "batchReceivePointBall err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 每日签到
     *
     * @return 签到是否成功
     */
    public static boolean dailySignIn() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.continueSignIn());
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject toastModule = jsonObject.getJSONObject("data").getJSONObject("autoSignInToastModule");
                String desc = toastModule.getString("desc");
                String beanNum = desc.substring(desc.indexOf("玩乐豆+") + 4);
                Log.member("游戏中心🎮每日签到#获得[" + beanNum + "玩乐豆]");
                return true;
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "continueSignIn err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }
    
    /**
     * 处理单个任务
     *
     * @param taskObj 任务JSON对象
     */
    public static void processTask(JSONObject taskObj) {
        try {
            if (!"VIEW".equals(taskObj.getString("actionType"))) {
                return;
            }
            
            String taskId = taskObj.getString("taskId");
            String subTitle = taskObj.getString("subTitle");
            String taskStatus = taskObj.getString("taskStatus");
            int prizeAmount = taskObj.getInt("prizeAmount");
            
            // 任务未完成且需要报名
            if ("NOT_DONE".equals(taskStatus) && taskObj.getBoolean("needSignUp")) {
                JSONObject jsonObject = new JSONObject(AntMemberRpcCall.doTaskSignup(taskId));
                if (!MessageUtil.checkSuccess(TAG, jsonObject)) {
                    return;
                }
            }
            
            // 执行任务
            JSONObject doTaskjo = new JSONObject(AntMemberRpcCall.doTaskSend(taskId));
            if (MessageUtil.checkSuccess(TAG, doTaskjo)) {
                Log.member("游戏中心🎮完成任务[" + subTitle + "]#待领[" + prizeAmount + "玩乐豆]");
            } else {
                // doTaskSend 常被 400000040 拒绝，改用另一种实现方案（见 TaskAlternative）
                String sceneCode = taskObj.optString("sceneCode", "").trim();
                if (TaskAlternative.hit(doTaskjo, sceneCode)) {
                    // bizKey 优先取任务自带的：游戏中心任务流的 bizKey/gameId 与 v3 的 taskId 不是一回事
                    String bizKey = taskObj.optString("bizKey", "").trim();
                    if (bizKey.isEmpty()) {
                        bizKey = taskId;
                    }
                    TaskAlternative.trigger(pendingVerifyTasks, taskId, subTitle, bizKey, sceneCode,
                            AntMemberRpcCall.DO_FARM_TASK_VERSION,
                            "游戏中心", msg -> Log.member(msg));
                } else {
                    // 检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", subTitle, doTaskjo);
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "doTask err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 查询并处理任务列表
     */
    public static void queryAndProcessTaskList() {
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.queryModularTaskList());
            if (!MessageUtil.checkSuccess(TAG, jsonObject)) {
                return;
            }
            if (!jsonObject.has("data")) {
                return;
            }
            JSONArray taskModuleList = jsonObject.getJSONObject("data").getJSONArray("taskModuleList");
            for (int i = 0; i < taskModuleList.length(); i++) {
                JSONObject moduleObj = taskModuleList.getJSONObject(i);
                JSONArray taskList = moduleObj.getJSONArray("taskList");
                for (int j = 0; j < taskList.length(); j++) {
                    processTask(taskList.getJSONObject(j));
                }
            }
            verifyPendingTasks();
        }
        catch (Throwable t) {
            Log.i(TAG, "queryModularTaskList err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 核对「已触发但响应不可信」的游戏中心任务：等几秒后重拉任务列表，
     * 仍为 NOT_DONE 的才计入自动拉黑。
     */
    private static void verifyPendingTasks() {
        TaskAlternative.verify(pendingVerifyTasks, VERIFY_CFG, () -> {
            Set<String> notDone = new LinkedHashSet<>();
            collectNotDoneIds(AntMemberRpcCall.queryModularTaskList(), notDone);
            return notDone;
        });
    }

    private static void collectNotDoneIds(String response, Set<String> out) {
        try {
            JSONObject data = new JSONObject(response).optJSONObject("data");
            if (data == null) return;
            JSONArray modules = data.optJSONArray("taskModuleList");
            if (modules != null) {
                for (int i = 0; i < modules.length(); i++) {
                    JSONObject moduleObj = modules.optJSONObject(i);
                    if (moduleObj == null) continue;
                    JSONArray taskList = moduleObj.optJSONArray("taskList");
                    if (taskList == null) continue;
                    for (int j = 0; j < taskList.length(); j++) {
                        JSONObject task = taskList.optJSONObject(j);
                        if (task == null) continue;
                        if ("NOT_DONE".equals(task.optString("taskStatus"))) {
                            String id = task.optString("taskId");
                            if (id != null && !id.isEmpty()) out.add(id);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.record(TAG + " collectNotDoneIds err: " + t.getMessage());
        }
    }    
    /**
     * 会员游戏乐园浏览奖励（独立闭环）。
     * <p>
     * 流程：查询入口信息 → 解析 sceneId/source → 访问外部游戏中心首页 → 读取浏览任务
     * → 按服务端下发时长分段等待并上报游戏时长 → 提交浏览奖励 → 回查任务与积分。
     * 任一步骤缺少必要参数或失败时直接返回，不影响其他模块。
     */
    private void memberGameParadiseBrowseReward() {
        try {
            JSONObject entrance = new JSONObject(AntMemberRpcCall.queryGameEntranceInfo());
            if (!GameCenterPlayRpcCall.isAcceptedJson(entrance)) {
                Log.i(TAG, "会员游戏乐园入口查询失败:" + entrance);
                return;
            }
            String actionUrl = entrance.optString("actionUrl");
            if (actionUrl.isEmpty()) {
                Log.i(TAG, "会员游戏乐园入口缺少actionUrl:" + entrance);
                return;
            }
            // actionUrl 内可能再嵌套一层 url 参数，真正的页面在里层
            String innerUrl = StringUtil.getUrlQueryParam(actionUrl, "url");
            String pageUrl = innerUrl == null || innerUrl.isEmpty() ? actionUrl : innerUrl;
            String scene = StringUtil.getUrlQueryParam(pageUrl, "sceneId");
            String source = StringUtil.getUrlQueryParam(actionUrl, "chInfo");
            if (scene == null || scene.isEmpty() || source == null || source.isEmpty()) {
                Log.i(TAG, "会员游戏乐园入口缺少sceneId/source:" + entrance);
                return;
            }

            String homeRes = GameCenterPlayRpcCall.queryExternalGameCenter(scene, "", "", source, "");
            JSONObject home = new JSONObject(homeRes);
            if (!GameCenterPlayRpcCall.isAcceptedJson(home)) {
                Log.i(TAG, "会员游戏乐园访问失败:" + homeRes);
                return;
            }
            JSONObject homeData = home.optJSONObject("data");
            if (homeData == null) {
                Log.i(TAG, "会员游戏乐园首页缺少data:" + homeRes);
                return;
            }
            String memberSignTaskId = homeData.optString("memberSignTaskId");
            int memberSignAmount = homeData.optInt("memberSignAmount", 0);

            JSONObject browse = homeData.optJSONObject("browseTaskVO");
            if (browse == null) {
                Log.i(TAG, "会员游戏乐园暂无浏览任务");
                return;
            }
            String taskId = browse.optString("taskId");
            String taskTitle = browse.optString("taskTitle", "游戏浏览奖励");
            String sceneExtInfo = browse.optString("sceneExtInfo");
            String appId = browse.optString("appId");
            String gameJumpUrl = browse.optString("gameJumpUrl");
            String gameSource = gameJumpUrl.isEmpty() ? null : StringUtil.getUrlQueryParam(gameJumpUrl, "chInfo");
            JSONObject floatingBall = browse.optJSONObject("floatingBallVO");
            int timeSeconds = floatingBall == null ? 0 : floatingBall.optInt("timeSeconds", 0);

            if (taskId.isEmpty() || sceneExtInfo.isEmpty() || appId.isEmpty()
                    || gameSource == null || gameSource.isEmpty() || timeSeconds <= 0) {
                Log.i(TAG, "会员游戏乐园浏览任务缺少任务标识、场景签名或游戏时长参数:" + browse);
                return;
            }

            // 分段等待并上报游戏时长（服务端要求时长 +1 秒，避免边界判定不足）
            if (!GameCenterPlayRpcCall.reportPlayDurationInChunks(appId, timeSeconds + 1, gameSource)) {
                Log.i(TAG, "会员游戏乐园浏览时长上报失败，保留待续:" + taskId);
                return;
            }

            String completeRes = GameCenterPlayRpcCall.completeExternalBrowseTask(scene, sceneExtInfo);
            if (!GameCenterPlayRpcCall.isAccepted(completeRes)) {
                Log.i(TAG, "会员游戏乐园浏览奖励提交失败:" + completeRes);
                return;
            }
            Log.member("会员游戏乐园🎮[" + taskTitle + "]已提交，刷新任务和积分");

            // 回查任务与积分
            GameCenterPlayRpcCall.isAccepted(
                    GameCenterPlayRpcCall.queryExternalGameCenter(scene, "", "", source, ""));
            if (!memberSignTaskId.isEmpty() && memberSignAmount > 0) {
                JSONObject points = new JSONObject(AntMemberRpcCall.queryPointCert(1, 20));
                Log.member("会员游戏乐园🎮[入账" + memberSignAmount + "积分] taskId=" + memberSignTaskId
                        + " 余额=" + JsonUtil.getValueByPath(points, "data.pointBalance"));
            }
        } catch (Throwable t) {
            Log.i(TAG, "memberGameParadiseBrowseReward err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 游戏中心（会员场景 xlyy_WJCNJT）宝箱领取。
     * <p>任务流 {@code queryGameFeeds} 里的条目都是「玩游戏得1个宝箱」（只有
     * gameId/cardId/appId + taskDesc，没有 taskId/taskStatus）；官方客户端在该场景同样只查不写
     * （query* + batchReceiveTaskPrize），宝箱由游戏侧上报才 +1——实测 doFarmTask 的
     * bizKey/taskSceneCode 七种组合全回 307「任务不存在」，所以这里不对任务流发完成请求，
     * 只在首页报有待领时把宝箱领掉；任务名仍进「会员任务」黑名单候选，供手动剔除。
     * <p>待领判据取官方字段：{@code taskModule.needReceive} / {@code needReceiveTaskCertCnt}，
     * 上限 {@code reachTaskCertLimit}（{@code dailyTaskCertCntUpperLimit}，30/天）。
     */
    public static void gameCenterTaskPrize() {
        try {
            JSONObject homeJo = new JSONObject(AntMemberRpcCall.gameCenterHomePage());
            if (!MessageUtil.checkSuccess(TAG, homeJo)) {
                Log.record("游戏中心首页异常：" + homeJo);
                return;
            }
            JSONObject homeData = homeJo.optJSONObject("data");
            JSONObject taskModule = homeData == null ? null : homeData.optJSONObject("taskModule");
            if (taskModule == null) {
                return;
            }
            if (taskModule.optBoolean("reachTaskCertLimit", false)) {
                Log.record("游戏中心宝箱已达今日上限（"
                        + taskModule.optString("taskProgressDesc", GAME_CENTER_CERT_LIMIT + "/天") + "）");
                return;
            }
            boolean needReceive = homeData.has("taskPrizePopup") || taskModule.optBoolean("needReceive", false)
                    || taskModule.optInt("needReceiveTaskCertCnt", 0) > 0;
            if (!needReceive) {
                return;
            }

            JSONObject receiveJo = new JSONObject(AntMemberRpcCall.batchReceiveTaskPrize());
            if (!MessageUtil.checkSuccess(TAG, receiveJo)) {
                Log.record("游戏中心宝箱领取失败：" + receiveJo);
                return;
            }
            JSONObject receiveData = receiveJo.optJSONObject("data");
            JSONObject prizePopup = receiveData == null ? null : receiveData.optJSONObject("prizePopup");
            String tip = prizePopup == null ? "" : prizePopup.optString("subTitle", "").trim();
            if (tip.isEmpty() && prizePopup != null) {
                tip = prizePopup.optString("actionText", "").trim();
            }
            String desc = taskModule.optString("taskProgressDesc", "").trim();
            Log.member("游戏中心🎁领取宝箱" + (tip.isEmpty() ? "" : "#" + tip) + (desc.isEmpty() ? "" : "#" + desc));
        }
        catch (Throwable t) {
            Log.i(TAG, "gameCenterTaskPrize err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 游戏中心任务流条目在「会员任务｜黑名单列表」里的标题（如「九梦仙域：玩游戏得1个宝箱」）。
     * <p>字段实测：{@code mainTitle}=游戏名、{@code subTitle}=宣传语、
     * {@code taskDesc}=「玩游戏得1个宝箱」、{@code taskTagText}=「玩游戏得」。
     */
    private static String gameCenterTaskTitle(JSONObject feed) {
        String title = firstNonEmpty(feed, "mainTitle", "subTitle");
        String taskDesc = firstNonEmpty(feed, "taskDesc", "taskTagText");
        if (title.isEmpty()) {
            title = taskDesc;
        } else if (!taskDesc.isEmpty() && !title.contains(taskDesc)) {
            title = title + "：" + taskDesc;
        }
        if (title.isEmpty()) {
            title = firstNonEmpty(feed, "gameId", "cardId", "appId");
        }
        return title;
    }

    /** 按顺序取第一个非空字符串字段。 */
    private static String firstNonEmpty(JSONObject jo, String... keys) {
        for (String key : keys) {
            String value = jo.optString(key, "").trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
    
    /**
     * 查询玩乐豆小球列表，有则领取
     */
    public static void queryPointBallList() {
        try {
            String response = ApplicationHook.requestString("com.alipay.gamecenteruprod.biz.rpc.v3.queryPointBallList", "[{}]");
            JSONObject jsonObject = new JSONObject(response);
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONArray pointBallList = jsonObject.getJSONObject("data").getJSONArray("pointBallList");
                if (pointBallList.length() > 0) {
                    batchReceivePointBall();
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "queryPointBallList err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 检查并执行签到
     */
    public static void checkAndDoSignIn() {
        if (Status.hasFlagToday("gameCenterSignIn")) {
            return;
        }
        
        try {
            JSONObject jsonObject = new JSONObject(AntMemberRpcCall.queryPointBallList());
            if (MessageUtil.checkSuccess(TAG, jsonObject)) {
                JSONObject dataObj = jsonObject.getJSONObject("data");
                if (dataObj.has("signInBallModule")) {
                    JSONObject signInModule = dataObj.getJSONObject("signInBallModule");
                    if (!signInModule.getBoolean("signInStatus")) {
                        if (dailySignIn()) {
                            Status.flagToday("gameCenterSignIn");
                        }
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "querySignInBall err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    /*
    private void enableGameCenter() {
        try {
            try {
                String str = AntMemberRpcCall.querySignInBall();
                JSONObject jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".signIn.querySignInBall", jsonObject.optString("resultDesc"));
                    return;
                }
                str = JsonUtil.getValueByPath(jsonObject, "data.signInBallModule.signInStatus");
                if (String.valueOf(true).equals(str)) {
                    return;
                }
                str = AntMemberRpcCall.continueSignIn();
                TimeUtil.sleep(300);
                jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".signIn.continueSignIn", jsonObject.optString("resultDesc"));
                    return;
                }
                Log.record("游戏中心🎮签到成功");
            }
            catch (Throwable th) {
                Log.i(TAG, "signIn err:");
                Log.printStackTrace(TAG, th);
            }
            try {
                String str = AntMemberRpcCall.queryPointBallList();
                JSONObject jsonObject = new JSONObject(str);
                if (!jsonObject.optBoolean("success")) {
                    Log.i(TAG + ".batchReceive.queryPointBallList", jsonObject.optString("resultDesc"));
                    return;
                }
                JSONArray jsonArray = (JSONArray) JsonUtil.getValueByPathObject(jsonObject, "data.pointBallList");
                if (jsonArray == null || jsonArray.length() == 0) {
                    return;
                }
                str = AntMemberRpcCall.batchReceivePointBall();
                TimeUtil.sleep(300);
                jsonObject = new JSONObject(str);
                if (jsonObject.optBoolean("success")) {
                    Log.member("游戏中心🎮全部领取成功[" + JsonUtil.getValueByPath(jsonObject, "data.totalAmount") + "]乐豆");
                }
                else {
                    Log.i(TAG + ".batchReceive.batchReceivePointBall", jsonObject.optString("resultDesc"));
                }
            }
            catch (Throwable th) {
                Log.i(TAG, "batchReceive err:");
                Log.printStackTrace(TAG, th);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    */
    // 会员积分兑换
    private void memberPointExchangeBenefit() {
        try {
            String userId = UserIdMap.getCurrentUid();
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryDeliveryZoneDetail(userId, "94000SR2024011106752003"));
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("entityInfoList")) {
                Log.record("会员积分[未实名账号无可兑换权益]");
                return;
            }
            JSONArray entityInfoList = jo.getJSONArray("entityInfoList");
            for (int i = 0; i < entityInfoList.length(); i++) {
                JSONObject entityInfo = entityInfoList.getJSONObject(i);
                JSONObject benefitInfo = entityInfo.getJSONObject("benefitInfo");
                JSONObject pricePresentation = benefitInfo.getJSONObject("pricePresentation");
                if (!"POINT_PAY".equals(pricePresentation.optString("strategyType"))) {
                    continue;
                }
                String name = benefitInfo.getString("name");
                String benefitId = benefitInfo.getString("benefitId");
                MemberBenefitIdMap.add(benefitId, name);
                if (!Status.canMemberPointExchangeBenefitToday(benefitId) || !memberPointExchangeBenefitList.getValue().contains(benefitId)) {
                    continue;
                }
                String itemId = benefitInfo.getString("itemId");
                if (exchangeBenefit(benefitId, itemId)) {
                    String point = pricePresentation.getString("point");
                    Log.member("会员积分🎐兑换[" + name + "]#花费[" + point + "积分]");
                }
            }
            MemberBenefitIdMap.save(userId);
        }
        catch (Throwable t) {
            Log.i(TAG, "memberPointExchangeBenefit err:");
            Log.printStackTrace(TAG, t);
        }
    }
    
    private Boolean exchangeBenefit(String benefitId, String itemId) {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.exchangeBenefit(benefitId, itemId));
            if (MessageUtil.checkResultCode(TAG, jo)) {
                Status.memberPointExchangeBenefitToday(benefitId);
                return true;
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "exchangeBenefit err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }
    
    private void collectSesame() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryHome());
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONObject entrance = jo.getJSONObject("entrance");
            if (!entrance.optBoolean("openApp")) {
                Log.member("芝麻信用💌未开通");
                return;
            }
            
            jo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
            TimeUtil.sleep(300);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            if (!jo.has("data")) {
                return;
            }
            JSONObject data = jo.getJSONObject("data");
            if (!data.has("toCompleteVOS")) {
                return;
            }
            JSONArray toCompleteVOS = data.getJSONArray("toCompleteVOS");
            // 本轮已上报的任务（templateId → 标题）及上报前完成度，用于回读校验
            LinkedHashMap<String, String> reported = new LinkedHashMap<>();
            LinkedHashMap<String, Integer> beforeComplete = new LinkedHashMap<>();
            for (int i = 0; i < toCompleteVOS.length(); i++) {
                JSONObject toCompleteVO = toCompleteVOS.getJSONObject(i);
                String taskTitle = toCompleteVO.has("title") ? toCompleteVO.getString("title") : "未知任务";
                //黑名单任务跳过
                if (MemberCreditSesameTaskList.getValue().contains(taskTitle)) {
                    continue;
                }
                
                boolean finishFlag = toCompleteVO.optBoolean("finishFlag", false);
                String actionText = toCompleteVO.optString("actionText", "");
                
                // 检查任务是否已完成
                if (finishFlag || "已完成".equals(actionText)) {
                    continue;
                }
                
                if (!toCompleteVO.has("templateId")) {
                    continue;
                }
                
                String taskTemplateId = toCompleteVO.getString("templateId");
                int needCompleteNum = toCompleteVO.has("needCompleteNum") ? toCompleteVO.getInt("needCompleteNum") : 1;
                int completedNum = toCompleteVO.optInt("completedNum", 0);
                String s = null;
                String recordId = null;
                JSONObject responseObj = null;

                // 官方链路为三段：PromiseRpcManager.joinActivity（拿 data.recordId）
                // → CreditAccumulateStrategyRpcManager.taskFeedback → PromiseRpcManager.pushActivity（带 recordId）
                // 注意：只发中间的 taskFeedback 时接口照样返回 success（data:true），但任务状态不推进，
                // 会表现为「日志报完成、实际未完成、每轮都报」，故 join/push 都必须发。
                // 进行中记录优先从任务列表直接读：join 被「存在进行中的生活记录」
                // （PROMISE_HAS_PROCESSING_TEMPLATE）拒绝时，必须用原记录的 recordId 才能推完它，
                // 否则该任务当天再也进不来
                recordId = activeSesameRecordId(data, taskTemplateId);
                if (recordId == null) {
                    // 列表数据可疑（同一任务多条不一致记录）：跳过，等下轮刷新
                    continue;
                }
                if (!recordId.isEmpty()) {
                    Log.member("芝麻信用💳[" + taskTitle + "]沿用进行中的记录#recordId=" + recordId);
                } else if (toCompleteVO.has("todayFinish")) {
                    // 无可推的记录
                    Log.error(TAG + "芝麻信用💳任务[" + taskTitle + "未获取到]recordId#" + toCompleteVO);
                    continue;
                } else {
                    // 领取任务
                    s = AntMemberRpcCall.joinSesameTask(taskTemplateId);
                    responseObj = new JSONObject(s);
                    //检查并标记黑名单任务
                    MessageUtil.checkResultCodeAndMarkTaskBlackList("MemberCreditSesameTaskList", taskTitle, responseObj);
                    TimeUtil.sleep(200);
                    if (MessageUtil.checkResultCode(TAG, responseObj)) {
                        recordId = responseObj.getJSONObject("data").getString("recordId");
                    } else {
                        // join 被「存在进行中的生活记录」拒绝：刷新列表取本任务那条继续推完
                        if ("PROMISE_HAS_PROCESSING_TEMPLATE".equals(responseObj.optString("resultCode"))) {
                            JSONObject fresh = new JSONObject(AntMemberRpcCall.queryAvailableSesameTask());
                            TimeUtil.sleep(300);
                            JSONObject freshData = fresh.optJSONObject("data");
                            String freshRecordId = freshData == null ? null
                                    : activeSesameRecordId(freshData, taskTemplateId);
                            if (freshRecordId != null && !freshRecordId.isEmpty()) {
                                recordId = freshRecordId;
                                Log.member("芝麻信用💳[" + taskTitle + "]沿用进行中的记录#recordId=" + recordId);
                            }
                        }
                        if (recordId == null || recordId.isEmpty()) {
                            Log.error(TAG + "芝麻信用💳领取任务[" + taskTitle + "]失败#" + s);
                            continue;
                        }
                    }
                }

                // 今日已上报且回读确认状态没推进：不再重复上报（次日再试），避免每轮刷同一批
                if (Status.hasFlagToday("AntMember::sesame::" + taskTitle)) {
                    continue;
                }

                // 完成任务：三段链路 join → taskFeedback → push 须依次全部发送。
                // 只发中间的 taskFeedback 时接口照样返回 success（data:true），但任务状态不推进，
                // 会表现为「日志报完成、实际未完成、每轮都报」。
                // 官方「每日最多可以完成 1 次」，故每轮每任务只上报一次，不再把剩余天数一次发完
                s = AntMemberRpcCall.feedBackSesameTaskNew(taskTemplateId);
                TimeUtil.sleep(2000);
                responseObj = new JSONObject(s);
                //检查并标记黑名单任务
                MessageUtil.checkResultCodeAndMarkTaskBlackList("MemberCreditSesameTaskList", taskTitle, responseObj);

                if (!MessageUtil.checkResultCode(TAG, responseObj)) {
                    Log.error("芝麻信用💳完成任务[" + taskTitle + "]taskFeedback未受理#" + s);
                    continue;
                }

                // push：带 recordId 收尾，任务状态才真正推进
                JSONObject pushJo = new JSONObject(AntMemberRpcCall.finishSesameTask(recordId));
                TimeUtil.sleep(500);
                if ("ILLEGAL_ARGUMENT".equals(pushJo.optString("resultCode"))) {
                    // 真实跳转类任务不可能自动完成，服务端固定拒 push：一次即拉黑，该错误不再打印
                    MessageUtil.MarkTaskBlackList("AntMember", "MemberCreditSesameTaskList", "芝麻粒任务", taskTitle);
                    Log.member("芝麻信用💳[" + taskTitle + "]#真实跳转类，无法自动完成，已加入黑名单");
                    continue;
                }
                if (!MessageUtil.checkResultCode(TAG, pushJo)) {
                    // push 被 promiseActivityExtCheck 校验拒不算中止，完成与否一律由回读判定
                    Log.member("芝麻信用💳[" + taskTitle + "]push被拒#不影响完成判定");
                }
                // 上报被受理 ≠ 任务完成：登记回读，真实状态一律以回读为准
                reported.put(taskTemplateId, taskTitle);
                beforeComplete.put(taskTemplateId, completedNum);
                
                jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
                TimeUtil.sleep(300);
                if (!MessageUtil.checkResultCode(TAG, jo)) {
                    return;
                }
                JSONArray ja = jo.getJSONArray("creditFeedbackVOS");
                for (int j = 0; j < ja.length(); j++) {
                    jo = ja.getJSONObject(j);
                    if (!"UNCLAIMED".equals(jo.getString("status"))) {
                        continue;
                    }
                    //String title = jo.getString("title");
                    String creditFeedbackId = jo.getString("creditFeedbackId");
                    String potentialSize = jo.getString("potentialSize");
                    jo = new JSONObject(AntMemberRpcCall.collectCreditFeedback(creditFeedbackId));
                    TimeUtil.sleep(300);
                    if (MessageUtil.checkResultCode(TAG, jo)) {
                        Log.member("收芝麻粒🙇🏻‍♂️领取[" + taskTitle + "]奖励[芝麻粒*" + potentialSize + "]");
                    }
                }
            }
            // 回读校验：上报被受理不等于任务完成，按服务端最新完成度判定，日志只写真实结果
            if (!reported.isEmpty()) {
                TimeUtil.sleep(500);
                JSONObject freshJo = new JSONObject(AntMemberRpcCall.CreditAccumulateStrategyRpcManager());
                LinkedHashMap<String, String> freshProgress = new LinkedHashMap<>();
                boolean freshOk = false;
                if (MessageUtil.checkResultCode(TAG, freshJo) && freshJo.has("data")) {
                    JSONArray freshList = freshJo.getJSONObject("data").optJSONArray("toCompleteVOS");
                    freshOk = true;
                    if (freshList != null) {
                        for (int k = 0; k < freshList.length(); k++) {
                            JSONObject vo = freshList.getJSONObject(k);
                            String tid = vo.optString("templateId", "");
                            if (tid.isEmpty()) {
                                continue;
                            }
                            int need = vo.has("needCompleteNum") ? vo.getInt("needCompleteNum") : 1;
                            if (vo.optBoolean("finishFlag", false) || "已完成".equals(vo.optString("actionText", ""))) {
                                freshProgress.put(tid, "DONE");
                            } else {
                                freshProgress.put(tid, vo.optInt("completedNum", 0) + "/" + need);
                            }
                        }
                    }
                }
                for (Map.Entry<String, String> entry : reported.entrySet()) {
                    String tid = entry.getKey();
                    String title = entry.getValue();
                    Integer before = beforeComplete.get(tid);
                    int beforeNum = before == null ? 0 : before;
                    if (!freshOk) {
                        Log.member("芝麻信用💳[" + title + "]已上报，回读校验未执行");
                        continue;
                    }
                    String progress = freshProgress.get(tid);
                    if (progress == null) {
                        Log.member("芝麻信用💳完成任务[" + title + "]#已不在待完成列表");
                        continue;
                    }
                    if ("DONE".equals(progress)) {
                        Log.member("芝麻信用💳完成任务[" + title + "]#已标记完成");
                        continue;
                    }
                    int slash = progress.indexOf('/');
                    int nowNum = slash <= 0 ? 0 : Integer.parseInt(progress.substring(0, slash));
                    int needNum = slash <= 0 ? 1 : Integer.parseInt(progress.substring(slash + 1));
                    if (nowNum <= beforeNum) {
                        // 未推进：今日不再重试，避免每轮刷同一批。
                        // 不自动拉黑——这类任务服务端要求真实参与，但状态可能异步未落，
                        // 一次未推进不足以判定"做不了"，留给用户手动勾选黑名单
                        Log.member("芝麻信用💳[" + title + "]上报未生效#仍为(" + progress + "天)，今日不再重试");
                        Status.flagToday("AntMember::sesame::" + title);
                    } else if (nowNum >= needNum) {
                        Log.member("芝麻信用💳完成任务[" + title + "]#(" + progress + "天)");
                    } else {
                        // 多天任务：今天只推进一份，剩下的留给次日
                        Log.member("芝麻信用💳完成任务[" + title + "]#(" + progress + "天)，剩余次日继续");
                        Status.flagToday("AntMember::sesame::" + title);
                    }
                }
            }
            jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
            TimeUtil.sleep(300);
            if (!MessageUtil.checkResultCode(TAG, jo)) {
                return;
            }
            JSONArray creditFeedbackVOS = jo.getJSONArray("creditFeedbackVOS");
            if (creditFeedbackVOS.length() != 0) {
                jo = new JSONObject(AntMemberRpcCall.collectAllCreditFeedback());
                if (MessageUtil.checkResultCode(TAG, jo)) {
                    String resultCode = jo.optString("resultCode");
                    Log.member("收芝麻粒🙇🏻‍♂️[一键收取]" + resultCode);
                }
            }
            
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }
    
    /**
     * 从任务列表取本任务进行中的记录：recordId 须为字符串且仍在进行中，
     * 同一任务出现多条不一致记录时返回 null（视为列表数据可疑，调用方应跳过本轮）。
     *
     * @return 进行中的 recordId；空串表示列表无本任务记录，null 表示列表数据可疑
     */
    private String activeSesameRecordId(JSONObject data, String taskTemplateId) {
        if (data == null) {
            return "";
        }
        JSONObject daily = data.optJSONObject("dailyTaskListVO");
        JSONArray[] lists = {data.optJSONArray("toCompleteVOS"),
                daily == null ? null : daily.optJSONArray("waitCompleteTaskVOS"),
                daily == null ? null : daily.optJSONArray("waitJoinTaskVOS")};
        String recordId = "";
        for (JSONArray list : lists) {
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject task = list.optJSONObject(i);
                if (task == null || !taskTemplateId.equals(task.optString("templateId", ""))) {
                    continue;
                }
                Object value = task.opt("recordId");
                if (value == null || JSONObject.NULL.equals(value) || "".equals(value)) {
                    continue;
                }
                boolean valid = value instanceof String && !((String) value).trim().isEmpty()
                        && Boolean.FALSE.equals(task.opt("finishFlag"))
                        && task.optInt("completedNum", 0) < task.optInt("needCompleteNum", 1)
                        && (recordId.isEmpty() || recordId.equals(value));
                if (!valid) {
                    Log.member("芝麻信用💳记录校验未通过#[" + taskTemplateId + "]跳过");
                    return null;
                }
                recordId = (String) value;
            }
        }
        return recordId;
    }

    private void CheckInTaskRpcManager() {
        if (Status.hasFlagToday("AntMember::zmlCheckIn")) {
            return;
        }
        try {
            
            String checkInRes = AntMemberRpcCall.alchemyQueryCheckIn("zml");
            JSONObject checkInJo = new JSONObject(checkInRes);
            if (MessageUtil.checkResultCode(TAG, checkInJo)) {
                JSONObject data = checkInJo.optJSONObject("data");
                if (data != null) {
                    JSONObject currentDay = data.optJSONObject("currentDateCheckInTaskVO");
                    if (currentDay != null) {
                        String status = currentDay.optString("status");
                        String checkInDate = currentDay.optString("checkInDate");
                        if ("CAN_COMPLETE".equals(status) && !checkInDate.isEmpty()) {
                            String completeRes = AntMemberRpcCall.zmCheckInCompleteTask(checkInDate, "zml");
                            try {
                                JSONObject completeJo = new JSONObject(completeRes);
                                if (MessageUtil.checkResultCode(TAG, completeJo)) {
                                    JSONObject prize = completeJo.optJSONObject("data");
                                    int num = 0;
                                    if (prize != null) {
                                        num = prize.optInt("zmlNum", prize.optJSONObject("prize") != null ? prize.optJSONObject("prize").optInt("num", 0) : 0);
                                    }
                                    Log.member("收芝麻粒🙇🏻‍♂️领取[每日签到成功]#获得" + num + "粒");
                                }
                                else {
                                    Log.error(".doSesameAlchemy#" + "签到失败:" + completeRes);
                                }
                            }
                            catch (Throwable e) {
                                Log.printStackTrace(TAG + ".doSesameAlchemy.alchemyCheckInComplete", e);
                            }
                        }
                    }
                }
            }
            Status.flagToday("AntMember::zmlCheckIn");
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG + ".doSesameZmlCheckIn", t);
        }
    }

    // 随机毫秒延迟：模拟人工、降低风控。区间 [min, max)
    private static void randomSleep(int minMillis, int maxMillis) {
        TimeUtil.sleep(RandomUtil.nextInt(minMillis, maxMillis));
    }

    // 固定延迟基础上叠加 0~60ms 随机抖动，避免触发时间过于规整
    private static void sleepWithJitter(int baseMillis) {
        TimeUtil.sleep(baseMillis + RandomUtil.nextInt(0, 61));
    }

    /**
     * 炼金专用成功判断：对齐 Sesame-AG 的 ResChecker，比全局 checkResultCode 更宽松。
     * 仅炼金流程使用，避免影响其它已有模块。
     */
    private static boolean checkAlchemyRes(JSONObject jo) {
        try {
            if (jo == null) {
                return false;
            }
            if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
                return true;
            }
            Object resCode = jo.opt("resultCode");
            if (resCode instanceof Integer && (Integer) resCode == 200) {
                return true;
            }
            if (resCode instanceof String) {
                String s = (String) resCode;
                if ("SUCCESS".equalsIgnoreCase(s) || "100".equals(s)) {
                    return true;
                }
            }
            return "SUCCESS".equalsIgnoreCase(jo.optString("memo", ""));
        }
        catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按服务端错误码判定炼金任务是否「确定做不了」，是则写入动态黑名单并落盘。
     *
     * <p>参照 Sesame-AG 的 {@code TaskBlacklist.autoAddToBlacklist}：只认少数**确定性**错误码，
     * 未知码一律不拉黑——偶发失败/服务端抖动不该被当成永久失败误杀。按 templateId 存而非标题
     * （标题是「玩寻道大千」式动态文案，会随活动改版失效）。
     *
     * @return 是否已判定为「做不了」并落盘（true 时调用方按提示级别记日志，不打 ERROR）
     */
    private static boolean autoBlacklistAlchemyTask(String templateId, String title, JSONObject jo) {
        if (templateId == null || templateId.trim().isEmpty()) {
            return false;
        }
        String errorCode = jo.optString("resultCode", "").trim();
        if (errorCode.isEmpty()) {
            errorCode = jo.optString("errorCode", "").trim();
        }
        String resultView = jo.optString("resultView", "");
        String reason;
        switch (errorCode) {
            case "400000040":
                reason = "不支持rpc调用";
                break;
            case "20020012":
                reason = "任务ID为空或无效";
                break;
            case "ILLEGAL_ARGUMENT":
                // 仅当服务端明确指认 promiseActivityExtCheck 时才算「需真实参与」；
                // 其它参数错误可能只是本次请求有问题，不拉黑
                if (!resultView.contains("promiseActivityExtCheck")) {
                    return false;
                }
                reason = "需真实参与(promiseActivityExtCheck)";
                break;
            case "TASK_ID_INVALID":
                reason = "任务ID非法";
                break;
            case "PROMISE_TEMPLATE_NOT_EXIST":
                reason = "生活记录模板不存在";
                break;
            case "FAKE_SUCCESS":
                reason = "检测到伪成功";
                break;
            case "AD_TRAFFIC_RISK":
            case "217":
            case "61002":
                reason = "广告流量风控";
                break;
            default:
                // 未知错误码：可能是偶发/服务端抖动，不拉黑，留给下轮重试
                return false;
        }
        if (AlchemyAutoBlacklistMap.contains(templateId)) {
            return true;
        }
        AlchemyAutoBlacklistMap.add(templateId, title);
        AlchemyAutoBlacklistMap.save();
        Log.member("芝麻炼金⚗[" + title + "]#" + reason + "，无法自动完成，已加入跳过列表");
        return true;
    }

    private void doSesameAlchemy() {
        // 限时任务（早/午/晚饭）每次 run 都尝试，饭点窗口内即可完成，不占每日总 flag
        doSesameAlchemyLimitedTasks();

        // 次日奖励每天都要领（独立 flag）
        doSesameAlchemyNextDayAward();

        // 主流程每天一轮
        if (Status.hasFlagToday("AntMember::sesameAlchemy")) return;
        try {
            // 1. 自动炼金（消耗芝麻粒升级）
            doSesameAlchemyExecute();

            // 2. 每日签到
            doSesameAlchemyCheckIn();

            // 3. 攒粒任务：join + feedback + finish 三连（Sesame-AG 实战路径）
            doSesameAlchemyDailyTasks();

            // 4. 一键收取芝麻粒
            doSesameAlchemyCollectFeedback();

            Status.flagToday("AntMember::sesameAlchemy");
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Step 1: 自动炼金（消耗芝麻粒升级，直到余额不足或满级）
     */
    private void doSesameAlchemyExecute() {
        try {
            randomSleep(1200, 2200);
            JSONObject home = new JSONObject(AntMemberRpcCall.alchemyQueryHome());
            if (!checkAlchemyRes(home)) {
                Log.record("芝麻炼金⚗主页未开通或接口变化#" + home);
                return;
            }
            JSONObject data = home.optJSONObject("data");
            if (data == null) {
                Log.error("芝麻炼金⚗主页[data 缺失]#" + home);
                return;
            }
            int zmlBalance = data.optInt("zmlBalance", 0);
            int cost = data.optInt("alchemyCostZml", 5);
            boolean capReached = data.optBoolean("capReached", false);
            int currentLevel = data.optInt("currentLevel", 0);

            if (cost <= 0) {
                Log.error("芝麻炼金⚗炼金[消耗为0，跳过]#" + home);
                return;
            }

            // 循环炼金，最多 50 次兜底，防止接口异常导致死循环
            int maxLoop = 50;
            while (zmlBalance >= cost && !capReached && maxLoop-- > 0) {
                randomSleep(1500, 3500); // 炼金执行延迟
                String alchemyRes = AntMemberRpcCall.doAlchemy();
                JSONObject alchemyJo = new JSONObject(alchemyRes);
                if (!checkAlchemyRes(alchemyJo)) {
                    String resultView = alchemyJo.optString("resultView", alchemyRes);
                    if (resultView.contains("盖帽")) {
                        // 服务端「盖帽值」（每日炼金上限）拦截：属正常终止条件，不是错误
                        Log.record("芝麻炼金⚗已达每日上限#" + resultView);
                    } else {
                        Log.error("芝麻炼金⚗炼金失败#" + resultView);
                    }
                    break;
                }
                JSONObject alData = alchemyJo.optJSONObject("data");
                if (alData == null) {
                    break;
                }
                boolean levelUp = alData.optBoolean("levelUp", false);
                boolean levelFull = alData.optBoolean("levelFull", false);
                int goldNum = alData.optInt("goldNum", 0);
                if (levelUp) {
                    currentLevel++;
                }
                if (levelFull) {
                    capReached = true;
                }
                Log.member("芝麻炼金⚗[炼金成功]#消耗" + cost + "粒 | 获得" + goldNum + "金 | 当前等级Lv." + currentLevel
                        + (levelUp ? "（升级🎉）" : "") + (levelFull ? "（满级🏆）" : ""));
                zmlBalance -= cost;
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Step 2: 每日签到（scene=alchemy）
     */
    private void doSesameAlchemyCheckIn() {
        try {
            randomSleep(1200, 2200);
            JSONObject jo = new JSONObject(AntMemberRpcCall.alchemyQueryCheckInTasks());
            if (!checkAlchemyRes(jo)) {
                Log.error("芝麻炼金⚗签到查询异常#" + jo);
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data == null) {
                Log.error("芝麻炼金⚗签到[data 缺失]#" + jo);
                return;
            }
            JSONObject currentDay = data.optJSONObject("currentDateCheckInTaskVO");
            if (currentDay == null) {
                Log.error("芝麻炼金⚗签到[currentDateCheckInTaskVO 缺失]#" + jo);
                return;
            }
            String status = currentDay.optString("status");
            String checkInDate = currentDay.optString("checkInDate");
            if ("CAN_COMPLETE".equals(status) && !checkInDate.isEmpty()) {
                randomSleep(800, 1800); // 任务执行延迟
                String completeRes = AntMemberRpcCall.completeAlchemyCheckIn(checkInDate);
                JSONObject completeJo = new JSONObject(completeRes);
                if (checkAlchemyRes(completeJo)) {
                    JSONObject prize = completeJo.optJSONObject("data");
                    int num = 0;
                    if (prize != null) {
                        num = prize.optInt("zmlNum", prize.optJSONObject("prize") != null
                                ? prize.optJSONObject("prize").optInt("num", 0) : 0);
                    }
                    Log.member("芝麻炼金⚗[每日签到成功]#获得" + num + "粒");
                }
                else {
                    Log.error("芝麻炼金⚗签到失败#" + completeRes);
                }
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Step 3: 攒粒任务（join → feedback → finish 三连；广告任务走 bizId 上报）
     */
    private void doSesameAlchemyDailyTasks() {
        try {
            randomSleep(1200, 2200);
            JSONObject jo = new JSONObject(AntMemberRpcCall.alchemyQueryTasks());
            if (!checkAlchemyRes(jo)) {
                Log.error("芝麻炼金⚗攒粒任务查询异常#" + jo);
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data == null) {
                Log.error("芝麻炼金⚗攒粒任务[data 缺失]#" + jo);
                return;
            }
            processAlchemyTasks(data.optJSONArray("toCompleteVOS"));

            JSONObject daily = data.optJSONObject("dailyTaskListVO");
            if (daily != null) {
                processAlchemyTasks(daily.optJSONArray("waitJoinTaskVOS"));
                processAlchemyTasks(daily.optJSONArray("waitCompleteTaskVOS"));
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    private void processAlchemyTasks(JSONArray taskList) {
        if (taskList == null || taskList.length() == 0) {
            return;
        }
        try {
            for (int i = 0; i < taskList.length(); i++) {
                JSONObject task = taskList.optJSONObject(i);
                if (task == null || task.optBoolean("finishFlag", false)) {
                    continue;
                }
                String title = task.optString("title", "未知任务");
                String templateId = task.optString("templateId");
                String bizType = task.optString("bizType", "");

                // 广告任务：无 templateId，用 logExtMap.bizId 走 adtask.finish 上报
                if ("AD_TASK".equals(bizType)) {
                    JSONObject logExtMap = task.optJSONObject("logExtMap");
                    String bizId = logExtMap == null ? "" : logExtMap.optString("bizId", "");
                    if (bizId.isEmpty()) {
                        Log.error("芝麻炼金⚗广告任务缺少 bizId，跳过: " + title);
                        continue;
                    }
                    randomSleep(1500, 3000);
                    TimeUtil.sleep(8000); // 浏览类任务等一段时间再上报
                    String adRes = AntMemberRpcCall.taskFinish(bizId);
                    JSONObject adJo = new JSONObject(adRes);
                    if (checkAlchemyRes(adJo) || "0".equals(adJo.optString("errCode"))) {
                        Log.member("芝麻炼金⚗[广告任务完成] " + title + "#获得" + task.optInt("rewardAmount", 0) + "粒");
                    }
                    else {
                        Log.error("芝麻炼金⚗广告任务上报失败: " + title + " - " + adRes);
                    }
                    continue;
                }

                // 需外部 App 或特定模板，仅靠 hook 无法完成
                if (templateId.contains("invite") || templateId.contains("upload")
                        || templateId.contains("auth") || templateId.contains("banli")) {
                    continue;
                }
                String actionUrl = task.optString("actionUrl", "");
                if (actionUrl.startsWith("alipays://") && !actionUrl.contains("chInfo")) {
                    continue;
                }

                // 默认黑名单：这些模板/标题通过 RPC 必然返回 ILLEGAL_ARGUMENT / PROMISE_TEMPLATE_NOT_EXIST
                // 动态黑名单：运行时按服务端错误码判定后落盘（见 autoBlacklistAlchemyTask），跨启动生效
                if (ALCHENY_BLACKLIST_TEMPLATE_IDS.contains(templateId)
                        || ALCHENY_BLACKLIST_TITLES.contains(title)
                        || AlchemyAutoBlacklistMap.contains(templateId)) {
                    Log.record("芝麻炼金⚗[黑名单跳过] " + title);
                    continue;
                }

                randomSleep(800, 1800);
                String recordId = task.optString("recordId", "");
                if (recordId.isEmpty()) {
                    if (templateId.trim().isEmpty()) {
                        Log.error("芝麻炼金⚗攒粒任务[templateId 缺失]#" + task);
                        continue;
                    }
                    String joinRes = AntMemberRpcCall.joinSesameTask(templateId);
                    JSONObject joinJo = new JSONObject(joinRes);
                    if (!checkAlchemyRes(joinJo)) {
                        if (autoBlacklistAlchemyTask(templateId, title, joinJo)) {
                            // 服务端按错误码明确判定做不了：已落盘跳过，不打 ERROR
                        } else {
                            // 注意：PROMISE_HAS_PROCESSING_TEMPLATE（上轮留下的进行中记录）属可恢复状态，
                            // 不在错误码白名单内，不会拉黑，下轮会重试
                            Log.error("芝麻炼金⚗攒粒任务 join 失败: " + title + " - " + joinJo.optString("resultView", joinRes));
                        }
                        continue;
                    }
                    JSONObject joinData = joinJo.optJSONObject("data");
                    recordId = joinData == null ? "" : joinData.optString("recordId");
                    randomSleep(800, 1800);
                }

                // feedback（无 sceneCode 的老 taskFeedback）
                AntMemberRpcCall.feedBackSesameTask(templateId);

                // 浏览/逛类任务多等一会
                TimeUtil.sleep(title.contains("浏览") || title.contains("逛") ? 15000 : 3000);

                if (!recordId.isEmpty()) {
                    String finishRes = AntMemberRpcCall.finishSesameTask(recordId);
                    JSONObject finishJo = new JSONObject(finishRes);
                    if (checkAlchemyRes(finishJo)) {
                        Log.member("芝麻炼金⚗[任务完成] " + title + "#获得" + task.optInt("rewardAmount", 0) + "粒");
                    }
                    else if (autoBlacklistAlchemyTask(templateId, title, finishJo)) {
                        // 服务端按错误码明确判定做不了：已落盘跳过，日志按提示级别记而非 ERROR
                    }
                    else {
                        Log.error("芝麻炼金⚗攒粒任务 finish 失败: " + title + " - " + finishRes);
                    }
                }
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * Step 4: 一键收取芝麻粒
     */
    private void doSesameAlchemyCollectFeedback() {
        try {
            randomSleep(1500, 2500);
            JSONObject jo = new JSONObject(AntMemberRpcCall.queryCreditFeedback());
            if (!checkAlchemyRes(jo)) {
                return;
            }
            JSONArray feedbackList = jo.optJSONArray("creditFeedbackVOS");
            if (feedbackList != null && feedbackList.length() > 0) {
                Log.record("芝麻炼金⚗[发现" + feedbackList.length() + "个待收取项，执行一键收取]");
                JSONObject collectJo = new JSONObject(AntMemberRpcCall.collectAllCreditFeedback());
                if (checkAlchemyRes(collectJo)) {
                    Log.member("芝麻炼金⚗[一键收取成功]#收割完毕");
                }
                else {
                    Log.error("芝麻炼金⚗[一键收取失败]#" + collectJo.optString("resultView"));
                }
            }
            else {
                Log.record("芝麻炼金⚗[当前无待收取芝麻粒]");
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 次日奖励：每天领一次，独立 flag（成功/失败都打标记，避免重复调用）
     */
    private void doSesameAlchemyNextDayAward() {
        if (Status.hasFlagToday("AntMember::sesameAlchemyNextDayAward")) return;
        try {
            randomSleep(1200, 2200);
            String awardRes = AntMemberRpcCall.alchemyClaimAward();
            JSONObject jo = new JSONObject(awardRes);
            if (!checkAlchemyRes(jo)) {
                Log.error("芝麻炼金⚗[次日奖励失败]#" + awardRes);
                Status.flagToday("AntMember::sesameAlchemyNextDayAward");
                return;
            }
            int gotNum = 0;
            JSONObject data = jo.optJSONObject("data");
            if (data != null) {
                JSONArray arr = data.optJSONArray("alchemyAwardSendResultVOS");
                if (arr != null && arr.length() > 0) {
                    JSONObject item = arr.optJSONObject(0);
                    if (item != null) {
                        gotNum = item.optInt("pointNum", 0);
                    }
                }
            }
            if (gotNum > 0) {
                Log.member("芝麻炼金⚗[次日奖励领取成功]#获得" + gotNum + "粒");
            }
            else {
                Log.record("芝麻炼金⚗[次日奖励无奖励] 已领取或无可领奖励");
            }
            Status.flagToday("AntMember::sesameAlchemyNextDayAward");
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
            Status.flagToday("AntMember::sesameAlchemyNextDayAward");
        }
    }

    /**
     * 限时任务（早/午/晚饭时段奖励）：每次 run 都查询，饭点窗口内可领取
     */
    private void doSesameAlchemyLimitedTasks() {
        try {
            randomSleep(800, 1800);
            JSONObject jo = new JSONObject(AntMemberRpcCall.alchemyQueryTimeLimitedTask());
            if (!checkAlchemyRes(jo)) {
                return;
            }
            JSONObject data = jo.optJSONObject("data");
            if (data == null) {
                Log.error("芝麻炼金⚗限时任务[data 缺失]#" + jo);
                return;
            }
            JSONObject vo = data.optJSONObject("timeLimitedTaskVO");
            if (vo == null) {
                Log.record("芝麻炼金⚗[当前没有时段奖励任务]");
                return;
            }
            String taskName = vo.optString("longTitle", "未知任务");
            String templateId = vo.optString("templateId");
            int state = vo.optInt("state", 0); // 1=可领取 2=未到时间
            boolean tomorrow = vo.optBoolean("tomorrow", false);
            if (templateId.isEmpty()) {
                Log.error("芝麻炼金⚗限时任务[templateId 缺失]#" + vo);
                return;
            }
            if (tomorrow) {
                Log.record("芝麻炼金⚗[任务跳过] " + taskName + " 是明天的奖励");
                return;
            }
            if (state == 1) {
                randomSleep(800, 1800);
                String res = AntMemberRpcCall.alchemyCompleteTimeLimitedTask(templateId);
                JSONObject resJo = new JSONObject(res);
                if (checkAlchemyRes(resJo) && resJo.optJSONObject("data") != null) {
                    JSONObject d = resJo.getJSONObject("data");
                    Log.member("芝麻炼金⚗[领取成功] " + taskName + "#获得" + d.optInt("zmlNum", 0) + "粒");
                }
                else {
                    Log.error("芝麻炼金⚗限时任务完成失败#" + res);
                }
            }
            else {
                Log.record("芝麻炼金⚗[当前不可领取] " + taskName);
            }
        }
        catch (Throwable t) {
            Log.printStackTrace(TAG, t);
        }
    }

    // 我的快递任务
    private void RecommendTask() {
        try {
            // 调用 AntMemberRpcCall.queryRecommendTask() 获取 JSON 数据
            String response = AntMemberRpcCall.queryRecommendTask();
            JSONObject jsonResponse = new JSONObject(response);
            // 获取 taskDetailList 数组
            JSONArray taskDetailList = jsonResponse.getJSONArray("taskDetailList");
            // 遍历 taskDetailList
            for (int i = 0; i < taskDetailList.length(); i++) {
                JSONObject taskDetail = taskDetailList.getJSONObject(i);
                // 检查 "canAccess" 的值是否为 true
                boolean canAccess = taskDetail.optBoolean("canAccess", false);
                if (!canAccess) {
                    // 如果 "canAccess" 不为 true，跳过
                    continue;
                }
                // 获取 taskMaterial 对象
                JSONObject taskMaterial = taskDetail.optJSONObject("taskMaterial");
                // 获取 taskBaseInfo 对象
                JSONObject taskBaseInfo = taskDetail.optJSONObject("taskBaseInfo");
                // 获取 taskCode
                String taskCode = taskMaterial.optString("taskCode", "");
                // 根据 taskCode 执行不同的操作
                if ("WELFARE_PLUS_ANT_FOREST".equals(taskCode) || "WELFARE_PLUS_ANT_OCEAN".equals(taskCode)) {
                    boolean isForest = "WELFARE_PLUS_ANT_FOREST".equals(taskCode);
                    String appletName = taskBaseInfo != null
                            ? taskBaseInfo.optString("appletName", "Unknown Applet") : "Unknown Applet";
                    // 原先这两个响应都被丢弃（赋值给局部变量后不看），失败也照打「完成」日志、
                    // 奖励实际没领到。现在两步都看结果：领到才记完成，失败按既有规则拉黑
                    JSONObject listJo = new JSONObject(
                            isForest ? AntMemberRpcCall.forestTask() : AntMemberRpcCall.oceanTask());
                    if (!MessageUtil.checkResultCode(TAG + " 快递活力值任务列表", listJo)) {
                        Log.i(TAG, "快递活力值任务列表查询失败: " + listJo);
                    }
                    TimeUtil.sleep(500);
                    String awardRes = isForest ? AntMemberRpcCall.forestreceiveTaskAward()
                            : AntMemberRpcCall.oceanreceiveTaskAward();
                    JSONObject awardJo = new JSONObject(awardRes);
                    if (MessageUtil.checkResultCode(TAG + " 快递活力值领奖", awardJo)) {
                        Log.member("我的快递💌完成[" + appletName + "]#活力值奖励已领取");
                    } else {
                        MessageUtil.checkResultCodeAndMarkTaskBlackList("AntMemberTaskList", appletName, awardJo);
                        Log.member("我的快递💌[" + appletName + "]#活力值奖励未领取#"
                                + awardJo.optString("resultView", awardRes));
                    }
                }
                if (taskMaterial == null || !taskMaterial.has("taskId")) {
                    // 如果 taskMaterial 为 null 或者不包含 taskId，跳过
                    continue;
                }
                // 获取 taskId
                String taskId = taskMaterial.getString("taskId");
                // 调用 trigger 方法
                String triggerResponse = AntMemberRpcCall.trigger(taskId);
                JSONObject triggerResult = new JSONObject(triggerResponse);
                // 检查 success 字段
                boolean success = triggerResult.getBoolean("success");
                if (success) {
                    // 从 triggerResponse 中获取 prizeSendInfo 数组
                    JSONArray prizeSendInfo = triggerResult.getJSONArray("prizeSendInfo");
                    if (prizeSendInfo.length() > 0) {
                        JSONObject prizeInfo = prizeSendInfo.getJSONObject(0);
                        JSONObject extInfo = prizeInfo.getJSONObject("extInfo");
                        // 获取 promoCampName
                        String promoCampName = extInfo.optString("promoCampName", "Unknown Promo Campaign");
                        // 输出日志信息
                        Log.member("我的快递💌完成[" + promoCampName + "]");
                    }
                }
            }
        }
        catch (Throwable th) {
            Log.i(TAG, "RecommendTask err:");
            Log.printStackTrace(TAG, th);
        }
    }
    
    private void OrdinaryTask() {
        try {
            // 调用 AntMemberRpcCall.queryOrdinaryTask() 获取 JSON 数据
            String response = AntMemberRpcCall.queryOrdinaryTask();
            JSONObject jsonResponse = new JSONObject(response);
            // 检查是否请求成功
            if (jsonResponse.getBoolean("success")) {
                // 获取任务详细列表
                JSONArray taskDetailList = jsonResponse.getJSONArray("taskDetailList");
                // 遍历任务详细列表
                for (int i = 0; i < taskDetailList.length(); i++) {
                    // 获取当前任务对象
                    JSONObject task = taskDetailList.getJSONObject(i);
                    // 提取任务 ID、处理状态和触发类型
                    String taskId = task.optString("taskId");
                    String taskProcessStatus = task.optString("taskProcessStatus");
                    String sendCampTriggerType = task.optString("sendCampTriggerType");
                    // 检查任务状态和触发类型，执行触发操作
                    if (!"RECEIVE_SUCCESS".equals(taskProcessStatus) && !"EVENT_TRIGGER".equals(sendCampTriggerType)) {
                        // 调用 signuptrigger 方法
                        String signuptriggerResponse = AntMemberRpcCall.signuptrigger(taskId);
                        // 调用 sendtrigger 方法
                        String sendtriggerResponse = AntMemberRpcCall.sendtrigger(taskId);
                        // 解析 sendtriggerResponse
                        JSONObject sendTriggerJson = new JSONObject(sendtriggerResponse);
                        // 判断任务是否成功
                        if (sendTriggerJson.getBoolean("success")) {
                            // 从 sendtriggerResponse 中获取 prizeSendInfo 数组
                            JSONArray prizeSendInfo = sendTriggerJson.getJSONArray("prizeSendInfo");
                            // 获取 prizeName
                            String prizeName = prizeSendInfo.getJSONObject(0).getString("prizeName");
                            Log.member("我的快递💌完成[" + prizeName + "]");
                        }
                        else {
                            Log.i(TAG, "sendtrigger failed for taskId: " + taskId);
                        }
                        TimeUtil.sleep(1000);
                    }
                }
            }
        }
        catch (Throwable th) {
            Log.i(TAG, "OrdinaryTask err:");
            Log.printStackTrace(TAG, th);
        }
    }
    
    // 消费金签到
    private void signinCalendar() {
        try {
            JSONObject jo = new JSONObject(AntMemberRpcCall.signinCalendar());
            if (!MessageUtil.checkSuccess(TAG, jo)) {
                return;
            }
            boolean signed = jo.optBoolean("isSignInToday");
            if (!signed) {
                jo = new JSONObject(AntMemberRpcCall.openBoxAward());
                if (MessageUtil.checkSuccess(TAG, jo)) {
                    int amount = jo.getInt("amount");
                    int consecutiveSignInDays = jo.getInt("consecutiveSignInDays");
                    Log.member("攒消费金💰签到[坚持" + consecutiveSignInDays + "天]#获得[" + amount + "消费金]");
                }
            }
        }
        catch (Throwable t) {
            Log.i(TAG, "signinCalendar err:");
            Log.printStackTrace(TAG, t);
        }
    }
}
