package com.sesame.wu.util;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 任务黑名单的**统一登记与判定**。
 *
 * <p>两条设计主线：
 * <ul>
 *   <li><b>集中登记</b>：把各模块 init 里散落的 {@code blackList.add(...)} 收敛到一处，形成
 *       「单一真相」。这不只是整洁——配置页跑在<b>独立进程</b>，只有落盘或代码里的东西才读得到，
 *       散在模块局部变量里就标注不出「系统默认」。</li>
 *   <li><b>智能匹配 + 错误码分类</b>：{@link #isMatch} 按 taskId / {@code id|title} /
 *       中文包含 / 前缀边界四层判定；{@link #classify} 按错误码决定能否拉黑。</li>
 * </ul>
 *
 * <h3>预置黑名单的取舍原则</h3>
 * 只保留「<b>不做真实行为就完不成、伪申报有风控风险</b>」的条目：真实交易/履约、真实捐赠、
 * 需真实下载、需真实桌面组件。凡是服务端<b>不校验真参与</b>（未报名一发即 success）的
 * 浏览/游戏/签到类，一律<b>不预置</b>——交给任务循环尝试，失败后再由 {@link #classify} 判定。
 *
 * <h3>为什么不拉黑 1009 / 访问受限</h3>
 * 这两者不是「这个任务做不了」，而是「<b>整个账号此刻被限流</b>」——被拦后官方连只读查询
 * 都不放行，拉黑单个任务没有意义（别的任务照样被拦），且它是<b>临时</b>的，拉黑会变成永久跳过。
 * 正确做法是全局停机（见 {@link Kind#HARD_BLOCK}）。
 */
public final class TaskBlacklist {

    private TaskBlacklist() {
    }

    // ══════════════════════════════════════════════════════════════════════
    // 一、集中预置黑名单
    // ══════════════════════════════════════════════════════════════════════

    /** 键为 {@code 模块名|列表字段名}，值为该系统默认拉黑的条目。 */
    private static final Map<String, Set<String>> PRESET_BLACKLIST = new LinkedHashMap<>();

    /**
     * 需要「展示名兜底」的列表：键同样为 {@code 模块名|列表字段名}，值为 {@code taskId → 展示名}。
     *
     * <p>与 {@link #PRESET_BLACKLIST} 的区别：这些列表以 <b>taskId</b> 为黑名单键（而非中文标题），
     * 但服务端尚未返回该任务时没有标题可用，所以额外带一个中文名做兜底展示。
     * 同一个列表只登记在其中一处，{@link #presetBlackList} 会两处都查。
     */
    private static final Map<String, Map<String, String>> PRESET_BLACKLIST_NAMED = new LinkedHashMap<>();

    private static void preset(String module, String listTitle, String... items) {
        PRESET_BLACKLIST.put(module + "|" + listTitle,
                Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(items))));
    }

    /** 登记「taskId → 展示名」形式的预置黑名单，参数成对传入（id, name, id, name…）。 */
    private static void presetNamed(String module, String listTitle, String... idNamePairs) {
        if (idNamePairs.length % 2 != 0) {
            throw new IllegalArgumentException("presetNamed 需要成对的 id/name，实收 " + idNamePairs.length + " 个");
        }
        Map<String, String> named = new LinkedHashMap<>();
        for (int i = 0; i < idNamePairs.length; i += 2) {
            named.put(idNamePairs[i], idNamePairs[i + 1]);
        }
        PRESET_BLACKLIST_NAMED.put(module + "|" + listTitle, Collections.unmodifiableMap(named));
    }

    static {
        // ───────────────────────── 蚂蚁庄园 ─────────────────────────

        // 饲料任务：真实支付类。没真付钱就申报"完成"属虚假履约，服务端回 1009 风控。
        // 注：「到店付款」同为真实支付，一并保留；「逛闪购外卖1元起吃」是纯浏览类，已释放。
        preset("AntFarm", "AntFarmDoFarmTaskList",
                "线上支付",
                "到店付款",
                "用花呗完成一笔支付");

        // 装扮抽抽乐：只保留需真实捐赠的。
        // 已释放（服务端对 taskFeedback 不校验是否真参与过，未报名任务一发即 success）：
        //   「消耗饲料换机会」（消耗的是用户自己的饲料，接口能正常完成；
        //     若不想消耗饲料，可在配置页手动加回黑名单）
        //   「【限时】玩游戏得新机会」「【限时】玩游戏得3次机会」「限时玩游戏得新机会」
        //   「【限时】开宝箱得2次机会」「【限时】开宝箱得3次机会」
        preset("AntFarm", "AntFarmDrawMachineTaskList",
                "伸出援手，点亮希望");

        // ───────────────────────── 蚂蚁森林 ─────────────────────────

        // 活力值：保留需真实好友/真实桌面组件/真实淘宝行为的条目。
        // 已释放：「三国大冒险过1关征战」「到店支付得50g能量」（游戏/浏览类）
        preset("AntForestV2", "AntForestVitalityTaskList",
                "去蚂蚁阿福健康问答",
                "邀请1位好友助力",
                "添加组件及时收能量",
                "去淘宝花花乐领红包",
                "践行绿色行为",
                "连续7天收自己能量");

        // 森林抽抽乐：原有两条都是"玩游戏/开宝箱得机会"，服务端不校验真参与 → 全部释放。
        // 这里显式登记空集合，让配置页知道"该列表无系统默认项"（而非"未登记"）。
        preset("AntForestV2", "AntForestHuntTaskList");

        // ───────────────────────── 神奇海洋 ─────────────────────────

        // 两条都是"玩一玩"小游戏，服务端不校验真参与 → 全部释放
        preset("AntOcean", "AntOceanAntiepTaskList");
        preset("AntOcean", "AntOceanFishBlackList");

        // ───────────────────────── 芭芭农场 ─────────────────────────

        // 保留真实回收 + 需真实下载/签到的条目。
        // 已释放：「逛助农好货得肥料」「钓鱼1次」「逛一逛闪购外卖」「逛好物最高得1500肥料」
        preset("AntOrchard", "AntOrchardTaskList",
                "完成1笔旧衣回收",
                "完成1单手机数码回收",
                "逛一逛快手",
                "下载蚂蚁阿福看健康攻略",
                "逛一逛签到领现金");

        // ───────────────────────── 会员 · 芝麻信用 ─────────────────────────

        // 全部是真实交易/履约类：下单 / 租赁 / 订酒店 / 回收 / 雇佣 / 付钱 / 查车。
        // 没真做就申报"完成"属虚假履约，有风控风险，必须预置拦死。
        preset("AntMember", "MemberCreditSesameTaskList",
                "用额度免押金下单",
                "去租赁下单",
                "芝麻租赁下单得芝麻粒",
                "去飞猪订酒店",
                "0.1元起租会员攒粒",
                "9.9元抢租3天大疆",
                "1分起囤神券茶咖美食",
                "完成旧衣回收得现金",
                "去雇佣芝麻大表鸽",
                "送你10.6元支付红包",
                "一键查询爱车估值");

        // ───────────────────────── 其他模块 ─────────────────────────

        // 运动：需真实下载安装
        preset("AntSports", "AntSportsTaskList",
                "下载登录AI健康管家");

        // 神奇物种：需真实桌面组件 / 需连续真实抽卡 / 需真实好友
        preset("AntDodo", "AntDodoTaskList",
                "惊喜任务：添加森林组件",
                "连续访问并主动抽卡7天",
                "每日任务：帮好友抽卡");

        // 金豆夺宝：真实支付 + 芝麻炼金入口的外部游戏（缺少可验证的完成闭环）。
        // 该列表以 taskId 为键，另带中文展示名做兜底（服务端未返回该任务时用）
        presetNamed("goldenbeans", "GoldenBeansTaskList",
                "GOLDEN_BEAN_TASK_XIANSHANGZHIFU", "线上支付",
                "GOLDEN_BEAN_TASK_XIANXIAZHIFU", "到店/线下支付",
                "GOLDEN_BEAN_TASK_YUEBAO", "余额宝真实业务动作",
                "TEST_PUSH_SUBSCRIBE", "订阅消息需真实确认",
                "ZHIMA_youxi_dageluosi", "芝麻炼金游戏·大格罗斯",
                "ZHIMA_youxi_zheguanwohenxing", "芝麻炼金游戏·这关我很行",
                "ZHIMA_youxi_hebuguowoba", "芝麻炼金游戏·喝不过我吧",
                "ZHIMA_youxi_wodehuayuanshijie", "芝麻炼金游戏·我的花园世界",
                "ZHIMA_youxi_qingyunjuezhifumo", "芝麻炼金游戏·青云诀之伏魔");
    }

    /**
     * 取某列表的预置黑名单（<b>可变副本</b>）。模块 init 可在此基础上追加自己的条目。
     * 未登记的列表返回空集。两种登记形式（Set / Named）都会查。
     */
    public static Set<String> presetBlackList(String module, String listTitle) {
        String key = module + "|" + listTitle;
        Set<String> preset = PRESET_BLACKLIST.get(key);
        if (preset != null) {
            return new LinkedHashSet<>(preset);
        }
        Map<String, String> named = PRESET_BLACKLIST_NAMED.get(key);
        return named == null ? new LinkedHashSet<>() : new LinkedHashSet<>(named.keySet());
    }

    /**
     * 取某列表的预置黑名单（{@code taskId → 展示名} 形式，<b>可变副本</b>）。
     * 未以该形式登记时返回空 Map。
     */
    public static Map<String, String> presetBlackListNamed(String module, String listTitle) {
        Map<String, String> named = PRESET_BLACKLIST_NAMED.get(module + "|" + listTitle);
        return named == null ? new LinkedHashMap<>() : new LinkedHashMap<>(named);
    }

    /** 该条目是否来自系统预置黑名单（配置页据此标注「系统默认」）。 */
    public static boolean isPreset(String module, String listTitle, String item) {
        if (item == null) {
            return false;
        }
        String key = module + "|" + listTitle;
        Set<String> preset = PRESET_BLACKLIST.get(key);
        if (preset != null) {
            for (String candidate : preset) {
                if (isMatch(item, candidate)) {
                    return true;
                }
            }
        }
        Map<String, String> named = PRESET_BLACKLIST_NAMED.get(key);
        if (named != null) {
            for (String candidate : named.keySet()) {
                if (isMatch(item, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 二、智能匹配
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 判断待检字符串是否命中黑名单条目。
     *
     * <p>四层判定，逐层放宽：
     * <ol>
     *   <li><b>完全相等</b>——常规路径。</li>
     *   <li><b>{@code id|title} 分隔符</b>——黑名单项同时写了 taskId 和标题，任一部分命中即可。</li>
     *   <li><b>中文双向包含</b>——标题常带前后缀（如「【限时】」），完全相等会失配。</li>
     *   <li><b>前缀边界</b>——待检串是纯 ASCII（taskId）时，要求黑名单项以它开头且<b>紧跟中文</b>，
     *       避免短 id 被长串夹带而误命中。</li>
     * </ol>
     *
     * @param input         待检字符串，通常是 taskId 或任务标题
     * @param blacklistItem 黑名单条目，通常是 taskId、标题，或 {@code id|title}
     */
    public static boolean isMatch(String input, String blacklistItem) {
        if (input == null || blacklistItem == null || blacklistItem.isEmpty()) {
            return false;
        }
        // 1) 完全匹配
        if (input.equals(blacklistItem)) {
            return true;
        }
        // 2) "id|title" 分隔符格式
        if (blacklistItem.indexOf('|') >= 0) {
            for (String part : blacklistItem.split("\\|")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (input.equals(part)) {
                    return true;
                }
                if (hasChinese(input) && (input.contains(part) || part.contains(input))) {
                    return true;
                }
            }
            return false;
        }

        boolean itemHasChinese = hasChinese(blacklistItem);
        boolean inputHasChinese = hasChinese(input);
        if (itemHasChinese) {
            if (!inputHasChinese) {
                // 待检串是纯 ASCII（taskId）：只有"黑名单项以它开头且后面紧跟中文"才算命中
                if (blacklistItem.startsWith(input)) {
                    int next = input.length();
                    if (next < blacklistItem.length() && isChinese(blacklistItem.charAt(next))) {
                        return true;
                    }
                }
                return false;
            }
            return blacklistItem.contains(input) || input.contains(blacklistItem);
        }
        return input.contains(blacklistItem);
    }

    /** 是否含中日韩统一表意文字（用于区分 taskId 与中文标题）。 */
    private static boolean hasChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isChinese(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    // ══════════════════════════════════════════════════════════════════════
    // 三、错误分类
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 失败类别。只有 {@link #PERMANENT} 可以拉黑。
     *
     * <p>分类原则：「业务受限、重复领取、系统繁忙、稍后再试、操作频繁」等
     * <b>临时 / 业务终态</b>一律不拉黑，否则一次偶发就把任务永久停掉。
     */
    public enum Kind {
        /** 稳定不可完成（无稳定完成闭环 / 稳定非重试参数错误）——可安全拉黑。 */
        PERMANENT,
        /** 可重试的临时故障（限流、远端异常、网络抖动）——留到下一轮。 */
        RETRYABLE,
        /** 服务端繁忙（102 / "服务器正在开小差"）——临时。 */
        BUSY,
        /** 风控硬阻塞（1009 / 访问受限）——<b>全局停机</b>，绝不拉黑。 */
        HARD_BLOCK,
        /** 成功或未识别。 */
        UNKNOWN
    }

    /** 稳定不可完成的错误码 → 中文原因。 */
    private static final Map<String, String> PERMANENT_CODE_REASONS = new LinkedHashMap<>();

    /** 文案型判据（各接口字段不统一，按文案兜底）。 */
    private static final Map<String, String> PERMANENT_TEXT_REASONS = new LinkedHashMap<>();

    static {
        PERMANENT_CODE_REASONS.put("400000040", "不支持rpc调用");
        PERMANENT_CODE_REASONS.put("20020012", "任务ID为空或无效");
        PERMANENT_CODE_REASONS.put("TASK_ID_INVALID", "任务ID非法");
        PERMANENT_CODE_REASONS.put("ILLEGAL_ARGUMENT", "参数错误");
        PERMANENT_CODE_REASONS.put("PROMISE_TEMPLATE_NOT_EXIST", "模板不存在");
        PERMANENT_CODE_REASONS.put("FAKE_SUCCESS", "检测到伪成功");
        PERMANENT_CODE_REASONS.put("AD_TRAFFIC_RISK", "广告流量风控");
        PERMANENT_CODE_REASONS.put("217", "广告流量风控");
        PERMANENT_CODE_REASONS.put("61002", "广告流量风控");

        PERMANENT_TEXT_REASONS.put("不支持rpc调用", "不支持rpc调用");
        PERMANENT_TEXT_REASONS.put("不支持RPC调用", "不支持rpc调用");
        PERMANENT_TEXT_REASONS.put("任务全局配置不存在", "任务全局配置不存在");
        PERMANENT_TEXT_REASONS.put("生活记录模板不存在", "模板不存在");
    }

    /** 从响应里按优先级取错误码（各接口字段不统一）。 */
    public static String extractCode(JSONObject jo) {
        if (jo == null) {
            return "";
        }
        for (String field : new String[]{"resultCode", "errorCode", "error", "errorTip",
                "code", "retCode", "sspErrorCode", "errCode"}) {
            String value = jo.optString(field, "").trim();
            if (!value.isEmpty() && !"null".equals(value)) {
                return value;
            }
        }
        return "";
    }

    /** 从响应里按优先级取错误文案（各接口字段不统一，多字段拼接）。 */
    public static String extractMessage(JSONObject jo) {
        if (jo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String field : new String[]{"resultDesc", "resultView", "memo", "desc",
                "errorMessage", "errorMsg", "sspErrorMsg", "message"}) {
            String value = jo.optString(field, "").trim();
            if (!value.isEmpty() && !"null".equals(value)) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private static boolean isSuccess(JSONObject jo) {
        return jo.optBoolean("success", false) || jo.optBoolean("isSuccess", false);
    }

    /**
     * 对一次失败响应分类。调用方据此决定「拉黑 / 留到下轮 / 全局停机」。
     *
     * <p>判定顺序（从"最该停"到"最该放"）：
     * <ol>
     *   <li>成功响应 → {@link Kind#UNKNOWN}</li>
     *   <li>风控硬阻塞 → {@link Kind#HARD_BLOCK}（全局停机，不拉黑）</li>
     *   <li>可重试 → {@link Kind#RETRYABLE}</li>
     *   <li>服务端繁忙 → {@link Kind#BUSY}</li>
     *   <li>稳定不可完成 → {@link Kind#PERMANENT}（唯一可拉黑的类别）</li>
     * </ol>
     */
    public static Kind classify(JSONObject jo) {
        if (jo == null || isSuccess(jo)) {
            return Kind.UNKNOWN;
        }
        if (isHardBlocked(jo)) {
            return Kind.HARD_BLOCK;
        }
        String code = extractCode(jo);
        String message = extractMessage(jo);

        // 可重试：响应自带标记，或已知的限流/远端异常码
        if (jo.optBoolean("retryable", false) || jo.optBoolean("retriable", false)
                || "3000".equals(code) || "REMOTE_INVOKE_EXCEPTION".equals(code)) {
            return Kind.RETRYABLE;
        }
        // 服务端繁忙：临时故障
        if ("102".equals(code) || message.contains("服务器正在开小差")) {
            return Kind.BUSY;
        }
        // 稳定不可完成
        if (PERMANENT_CODE_REASONS.containsKey(code)) {
            return Kind.PERMANENT;
        }
        for (String keyword : PERMANENT_TEXT_REASONS.keySet()) {
            if (message.contains(keyword)) {
                return Kind.PERMANENT;
            }
        }
        return Kind.UNKNOWN;
    }

    /**
     * 取该响应被判为 {@link Kind#PERMANENT} 的中文原因；非 PERMANENT 返回 {@code null}。
     */
    public static String permanentReason(JSONObject jo) {
        if (jo == null || classify(jo) != Kind.PERMANENT) {
            return null;
        }
        String code = extractCode(jo);
        String byCode = PERMANENT_CODE_REASONS.get(code);
        if (byCode != null) {
            return byCode;
        }
        String message = extractMessage(jo);
        for (Map.Entry<String, String> entry : PERMANENT_TEXT_REASONS.entrySet()) {
            if (message.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "未知的稳定错误";
    }

    /**
     * 是否为**风控硬阻塞**（1009 / 访问受限），用于让调用方停止对后续对象继续发请求。
     *
     * <p>只保留「确定该停」的判据：
     * <ul>
     *   <li>结果码 {@code 1009}（直接风控）</li>
     *   <li>文案含「访问受限 / 访问被拒绝」</li>
     * </ul>
     * <p>以下情况<b>不算</b>硬阻塞，避免误停：
     * <ul>
     *   <li>滑块 / 验证码 / captcha——可恢复，用户操作后即可继续</li>
     *   <li>离线模式 / 登录超时（{@code I07}、{@code 2000}）——由登录流程处理，不是风控</li>
     *   <li>响应本身是成功的</li>
     * </ul>
     */
    public static boolean isHardBlocked(JSONObject jo) {
        if (jo == null || isSuccess(jo)) {
            return false;
        }
        String code = extractCode(jo);
        String message = extractMessage(jo);
        // 可恢复的验证类：不算硬阻塞
        for (String keyword : new String[]{"滑块", "滑塊", "验证码", "驗證碼", "captcha"}) {
            if (message.toLowerCase().contains(keyword.toLowerCase())) {
                return false;
            }
        }
        // 离线 / 登录超时：交给登录流程，不在这里停
        if ("I07".equals(code) || "2000".equals(code)
                || message.contains("离线模式") || message.contains("登录超时")) {
            return false;
        }
        if ("1009".equals(code)) {
            return true;
        }
        for (String keyword : new String[]{"访问受限", "訪問受限", "访问被拒绝", "訪問被拒絕"}) {
            if (message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
