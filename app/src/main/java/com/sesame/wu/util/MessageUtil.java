package com.sesame.wu.util;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sesame.wu.data.ConfigV2;
import com.sesame.wu.data.ModelFields;
import com.sesame.wu.data.modelFieldExt.SelectModelField;
import com.sesame.wu.model.task.antMember.AntMember;
import com.sesame.wu.util.idMap.UserIdMap;

public class MessageUtil {
    private static final String TAG = MessageUtil.class.getSimpleName();
    private static final String UNKNOWN_TAG = "Unknown TAG";

    public static JSONObject newJSONObject(String str) {
        try {
            return new JSONObject(str);
        } catch (Throwable t) {
            Log.i(TAG, "newJSONObject err:");
            Log.printStackTrace(TAG, t);
        }
        return null;
    }

    public static void printErrorMessage(String tag, JSONObject jo, String errorMessageField) {
        try {
            if (isUnsupportedRpc(jo)) {
                // 400000040「不支持rpc调用」不代表任务做不了（调用方会转另一种实现方案）：
                // 只写运行日志、不记模块错误，避免把预期失败当成错误刷屏
                Log.i(tag, jo.toString());
                return;
            }
            if (isServerBusy(jo)) {
                // 102 高频错误：只打一行 JSON，避免 memo + JSON 刷两条
                Log.i(tag, jo.toString());
                return;
            }
            String errMsg = tag + " error:";
            Log.record(errMsg + jo.getString(errorMessageField));
            Log.i(jo.getString(errorMessageField), jo.toString());
        } catch (Throwable t) {
            Log.i(TAG, "printErrorMessage err:");
            Log.printStackTrace(TAG, t);
        }
    }

    public static Boolean checkMemo(JSONObject jo) {
        return checkMemo(UNKNOWN_TAG, jo);
    }

    public static Boolean checkMemo(String tag, JSONObject jo) {
        try {
            if (!"SUCCESS".equals(jo.optString("memo"))) {
                if (jo.has("memo")) {
                    printErrorMessage(tag, jo, "memo");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "checkMemo err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static Boolean checkResultCode(JSONObject jo) {
        return checkResultCode(UNKNOWN_TAG, jo);
    }

    public static Boolean checkResultCode(String tag, JSONObject jo) {
        try {
            /// 添加空值检查
            if (jo == null) {
                Log.i(tag, "JSON对象为空");
                return false;
            }

            if (jo.optBoolean("success") && jo.optString("desc").equals("处理成功")) {
                return true;
            }

            Object resultCode = jo.opt("resultCode");
            if (resultCode == null) {
                Log.i(tag, jo.toString());
                return false;
            }
            if (resultCode instanceof Integer) {
                return checkResultCodeInteger(tag, jo);
            } else if (resultCode instanceof String) {
                return checkResultCodeString(tag, jo);
            }
            Log.i(tag, jo.toString());
            return false;
        } catch (Throwable t) {
            Log.i(TAG, "checkResultCode err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static Boolean checkResultCodeString(String tag, JSONObject jo) {
        try {
            String resultCode = jo.optString("resultCode");
            if (!resultCode.equalsIgnoreCase("SUCCESS") && !resultCode.equals("100")) {
                if (jo.has("resultDesc")) {
                    printErrorMessage(tag, jo, "resultDesc");
                } else if (jo.has("resultView")) {
                    printErrorMessage(tag, jo, "resultView");
                } else {
                    // 服务端繁忙（102）已有 printErrorMessage 统一降噪，这里跳过避免重复刷行
                    if (!isServerBusy(jo)) {
                        Log.i(tag, jo.toString());
                    }
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "checkResultCodeString err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static Boolean checkResultCodeInteger(String tag, JSONObject jo) {
        try {
            int resultCode = jo.optInt("resultCode");
            if (resultCode != 200) {
                if (jo.has("resultMsg")) {
                    printErrorMessage(tag, jo, "resultMsg");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "checkResultCodeInteger err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static Boolean checkSuccess(JSONObject jo) {
        return checkSuccess(UNKNOWN_TAG, jo);
    }

    public static Boolean checkSuccess(String tag, JSONObject jo) {
        try {
            if (!jo.optBoolean("success") && !jo.optBoolean("isSuccess")) {
                if (jo.has("errorMsg")) {
                    printErrorMessage(tag, jo, "errorMsg");
                } else if (jo.has("errorMessage")) {
                    printErrorMessage(tag, jo, "errorMessage");
                } else if (jo.has("desc")) {
                    printErrorMessage(tag, jo, "desc");
                } else if (jo.has("resultDesc")) {
                    printErrorMessage(tag, jo, "resultDesc");
                } else if (jo.has("resultView")) {
                    printErrorMessage(tag, jo, "resultView");
                } else {
                    Log.i(tag, jo.toString());
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.i(TAG, "checkSuccess err:");
            Log.printStackTrace(TAG, t);
        }
        return false;
    }

    public static void checkResultCodeAndMarkTaskBlackList(String listTitle, String taskTitle, JSONObject jo) {
        try {
            if (jo == null) {
                Log.i(listTitle, "JSON对象为空");
                return;
            }
            // 统一分类守卫：只有「稳定不可完成」才允许拉黑。
            // 可重试（限流 / 远端异常 / 网络抖动）、服务端繁忙、风控硬阻塞一律不拉黑——
            // 否则一次偶发就会把任务永久停掉。这里是纯守卫，不放宽任何原有判据。
            TaskBlacklist.Kind kind = TaskBlacklist.classify(jo);
            if (kind == TaskBlacklist.Kind.HARD_BLOCK) {
                Log.record("命中风控(1009/访问受限)，不拉黑任务，交由离线模式处理");
                return;
            }
            if (kind == TaskBlacklist.Kind.RETRYABLE || kind == TaskBlacklist.Kind.BUSY) {
                return;
            }
            //标记是否加黑
            boolean canAddBlackList = false;

            //共性返回失败关键字
            if (jo.has("desc")) {
                String desc = jo.optString("desc");
                if (desc.contains("不支持rpc调用") || desc.contains("不支持RPC调用")) {
                    canAddBlackList = true;
                }
            }

            //这里根据对应任务返回异常的值精准设置拉黑条件
            switch (listTitle) {
                //蚂蚁森林活力值任务AntForestV2
                case "AntForestVitalityTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntForestV2", listTitle, "蚂蚁森林活力值任务", taskTitle);
                    }
                    break;

                //蚂蚁森林抽抽乐任务AntForestV2
                case "AntForestHuntTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntForestV2", listTitle, "蚂蚁森林抽抽乐任务", taskTitle);
                    }
                    break;

                //庄园饲料任务AntFarm
                case "AntFarmDoFarmTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntFarm", listTitle, "庄园饲料任务", taskTitle);
                    }
                    break;

                //庄园装扮抽抽乐任务AntFarm
                case "AntFarmDrawMachineTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntFarm", listTitle, "庄园装扮抽抽乐任务", taskTitle);
                    }
                    break;

                //神奇物种任务AntDodo
                case "AntDodoTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntDodo", listTitle, "神奇物种任务", taskTitle);
                    }
                    break;

                //神奇海洋普通任务AntOcean
                case "AntOceanAntiepTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntOcean", listTitle, "神奇海洋普通任务", taskTitle);
                    }
                    break;

                //神奇海洋去摸鱼任务AntOcean
                case "AntOceanFishBlackList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntOcean", listTitle, "神奇海洋去摸鱼任务", taskTitle);
                    }
                    break;

                //农场肥料任务AntOrchard
                case "AntOrchardTaskList":
                    if (jo.has("desc")) {
                        String desc = jo.optString("desc");
                        if (desc.contains("任务全局配置不存在")) {
                            canAddBlackList = true;
                        }
                    }
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntOrchard", listTitle, "农场肥料任务", taskTitle);
                    }
                    break;

                //农场抽抽乐任务AntOrchard
                case "OrchardChouChouLeTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntOrchard", listTitle, "农场抽抽乐任务", taskTitle);
                    }
                    break;

                //金豆夺宝任务goldenbeans
                case "GoldenBeansTaskList": {
                    // 错误码依次取 code / resultCode / errorCode（不同接口字段不统一）
                    String code = jo.optString("code", "").trim();
                    if (code.isEmpty()) {
                        code = jo.optString("resultCode", "").trim();
                    }
                    if (code.isEmpty()) {
                        code = jo.optString("errorCode", "").trim();
                    }
                    // 错误文案依次取 desc / resultDesc / memo
                    String message = jo.optString("desc", "");
                    if (message.isEmpty()) {
                        message = jo.optString("resultDesc", "");
                    }
                    if (message.isEmpty()) {
                        message = jo.optString("memo", "");
                    }
                    // 可重试错误（限流、远端异常、网络抖动）不拉黑，避免临时故障把任务永久跳过
                    boolean retryable = jo.optBoolean("retryable", false)
                            || jo.optBoolean("retriable", false)
                            || "3000".equals(code)
                            || "REMOTE_INVOKE_EXCEPTION".equals(code);
                    // 仅在“重试也不会有不同结果”时才拉黑：
                    // 1) 任务Id非法、入参非法等不可恢复错误码；
                    // 2) 服务端明确不支持 rpc 调用或任务全局配置不存在。
                    boolean unsupported = code.contains("400000040");
                    boolean invalid = code.contains("20020012")
                            || code.contains("TASK_ID_INVALID")
                            || code.contains("ILLEGAL_ARGUMENT");
                    if (!retryable && (unsupported || invalid
                            || message.contains("不支持rpc调用") || message.contains("不支持RPC调用")
                            || message.contains("任务全局配置不存在"))) {
                        canAddBlackList = true;
                    }
                    if (canAddBlackList) {
                        MarkTaskBlackList("goldenbeans", listTitle, "金豆夺宝任务", taskTitle);
                    }
                    break;
                }

                //新村任务AntStall
                case "AntStallTaskList":
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntStall", listTitle, "新村任务", taskTitle);
                    }
                    break;

                //运动任务AntSports
                case "AntSportsTaskList":
                    if (jo.has("errorCode")) {
                        String errorCode = jo.optString("errorCode");
                        // {"ariverRpcTraceId":"21a4804717677001946607240e1734","errorCode":"TASK_ID_INVALID","errorMsg":"海豚任务id非法","retryable":false,"success":false}
                        if (errorCode.contains("TASK_ID_INVALID")) {
                            canAddBlackList = true;
                        }

                    }
                    if (jo.has("errorMsg")) {
                        String errorMsg = jo.optString("errorMsg");
                        if (errorMsg.contains("海豚活动触发不可重试错误")) {
                            canAddBlackList = true;
                        }
                    }
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntSports", listTitle, "运动任务", taskTitle);
                    }
                    break;

                //会员任务AntMember
                case "AntMemberTaskList":
                    if (jo.has("resultView")) {
                        String resultView = jo.optString("resultView");
                        if (resultView.contains("不是有效的入参")
                                || resultView.contains("存在进行中的生活记录")
                                || resultView.contains("生活记录模板不存在")) {
                            canAddBlackList = true;
                        }
                    }
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntMember", listTitle, "会员任务", taskTitle);

                    }
                    break;

                //会员芝麻信用任务芝麻粒AntMember
                case "MemberCreditSesameTaskList":
                    if (jo.has("resultView")) {
                        String resultView = jo.optString("resultView");
                        if (resultView.contains("不是有效的入参") || resultView.contains("存在进行中的生活记录")|| resultView.contains("生活记录模板不存在")) {
                            canAddBlackList = true;
                        }
                    }
                    if (canAddBlackList) {
                        MarkTaskBlackList("AntMember", listTitle, "会员芝麻信用任务芝麻粒", taskTitle);
                    }
                    break;

            }
        } catch (Throwable t) {
            Log.i(TAG, "checkSuccess err:");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 各任务列表的白名单：键为 listTitle（字段名，与 {@link #MarkTaskBlackList} 的 listTitle 一致），
     * 值为不应被「自动拉黑」机制添加的任务标题集合。
     * <p>只用于拦截自动拉黑入口（{@link #MarkTaskBlackList}），
     * <b>绝不删除用户手动加入黑名单的条目</b>——原先各模块的初始化代码会遍历白名单
     * 从列表里 remove，把用户手动加的黑名单条目一起删掉。
     * <p>由各模块初始化时通过 {@link #registerTaskWhiteList} 登记。
     */
    private static final Map<String, Set<String>> TASK_WHITE_LIST = new HashMap<>();

    /**
     * 登记某任务列表的白名单（仅阻止自动拉黑添加，不删除任何已有条目）。
     */
    public static void registerTaskWhiteList(String listTitle, Set<String> whiteList) {
        if (listTitle == null || whiteList == null || whiteList.isEmpty()) {
            return;
        }
        TASK_WHITE_LIST.put(listTitle, new HashSet<>(whiteList));
    }

    public static void MarkTaskBlackList(String ModelFieldsType, String listTitle, String TaskListName, String taskTitle) {
        // 延迟拉黑：处于捕获模式时先入缓冲，由调用方在列表核对后决定落盘/丢弃
        if (deferBlackList(() -> doMarkTaskBlackList(ModelFieldsType, listTitle, TaskListName, taskTitle, false))) {
            return;
        }
        doMarkTaskBlackList(ModelFieldsType, listTitle, TaskListName, taskTitle, false);
    }

    /**
     * 【底线】交易/支付类任务：拉黑时打「永久」标记，日志上与普通自动拉黑区分。
     *
     * <p>本项目没有「满 N 天自动解禁」机制，所有拉黑本来就不会自动解禁，
     * 故这里与 {@link #MarkTaskBlackList} 行为一致，只改日志文案。
     *
     * <p><b>不走延迟拉黑缓冲</b>：缓冲的用途是「等任务列表核对后再决定是否拉黑」，
     * 而交易/支付类没有可核对的成功路径（绝不允许被伪申报做成）。
     */
    public static void MarkTaskBlackListPermanent(String ModelFieldsType, String listTitle,
                                                  String TaskListName, String taskTitle) {
        doMarkTaskBlackList(ModelFieldsType, listTitle, TaskListName, taskTitle, true);
    }

    private static void doMarkTaskBlackList(String ModelFieldsType, String listTitle,
                                            String TaskListName, String taskTitle, boolean permanent) {
        // 白名单拦截：白名单任务只由「用户手动」决定是否拉黑，自动拉黑机制不插手
        Set<String> white = TASK_WHITE_LIST.get(listTitle);
        if (white != null && white.contains(taskTitle)) {
            Log.record("[" + TaskListName + "]任务[" + taskTitle + "]在白名单中，跳过自动拉黑");
            return;
        }
        ConfigV2 config = ConfigV2.INSTANCE;
        ModelFields TaskModelFields = config.getModelFieldsMap().get(ModelFieldsType);
        if (TaskModelFields == null) {
            // BLACKLIST_LIST_TARGETS 的第一项必须是模型类名，写错时这里要能看到原因而不是 NPE
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle + "#未找到模块[" + ModelFieldsType + "]");
            return;
        }
        SelectModelField TaskSelectModelField = (SelectModelField) TaskModelFields.get(listTitle);
        if (TaskSelectModelField == null) {
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle);
            return;
        }
        if (!TaskSelectModelField.contains(taskTitle)) {
            TaskSelectModelField.add(taskTitle, 0); // 数组类型忽略count，传0
        }
        if (ConfigV2.save(UserIdMap.getCurrentUid(), false)) {
            Log.record((permanent ? "自动拉黑🔒永久" : "自动拉黑🏴") + "在[" + TaskListName + "]中添加["
                    + taskTitle + "]黑名单:" + TaskSelectModelField.getValue());
        } else {
            Log.record("添加" + TaskListName + "黑名单失败：" + taskTitle);
        }
    }

    // ==================== 自动拉黑：目标反查与延迟提交 ====================

    /**
     * 按列表字段名反查拉黑目标 {@code {模块名, 列表中文名}}；未登记的列表返回 null。
     *
     * <p>供模块外的通用逻辑（如 {@code TaskAttemptPolicy}）在判定「该任务做不了」时按规则记账。
     */
    private static final Map<String, String[]> BLACKLIST_LIST_TARGETS = new LinkedHashMap<>();

    static {
        BLACKLIST_LIST_TARGETS.put("AntForestVitalityTaskList", new String[]{"AntForestV2", "蚂蚁森林活力值任务"});
        BLACKLIST_LIST_TARGETS.put("AntForestHuntTaskList", new String[]{"AntForestV2", "蚂蚁森林抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("AntFarmDoFarmTaskList", new String[]{"AntFarm", "庄园饲料任务"});
        BLACKLIST_LIST_TARGETS.put("AntFarmDrawMachineTaskList", new String[]{"AntFarm", "庄园装扮抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("AntDodoTaskList", new String[]{"AntDodo", "神奇物种任务"});
        BLACKLIST_LIST_TARGETS.put("AntOceanAntiepTaskList", new String[]{"AntOcean", "神奇海洋普通任务"});
        BLACKLIST_LIST_TARGETS.put("AntOceanFishBlackList", new String[]{"AntOcean", "神奇海洋去摸鱼任务"});
        BLACKLIST_LIST_TARGETS.put("AntOrchardTaskList", new String[]{"AntOrchard", "农场肥料任务"});
        BLACKLIST_LIST_TARGETS.put("OrchardChouChouLeTaskList", new String[]{"AntOrchard", "农场抽抽乐任务"});
        BLACKLIST_LIST_TARGETS.put("GoldenBeansTaskList", new String[]{"goldenbeans", "金豆夺宝任务"});
        BLACKLIST_LIST_TARGETS.put("AntStallTaskList", new String[]{"AntStall", "新村任务"});
        BLACKLIST_LIST_TARGETS.put("AntSportsTaskList", new String[]{"AntSports", "运动任务"});
        BLACKLIST_LIST_TARGETS.put("AntMemberTaskList", new String[]{"AntMember", "会员任务"});
        BLACKLIST_LIST_TARGETS.put("MemberCreditSesameTaskList", new String[]{"AntMember", "会员芝麻信用任务芝麻粒"});
    }

    /** 按列表字段名反查拉黑目标；未登记返回 null。 */
    public static String[] autoBlackListTarget(String listField) {
        return BLACKLIST_LIST_TARGETS.get(listField);
    }

    /** 延迟拉黑缓冲：处于捕获模式时，拉黑动作先入缓冲，由调用方在列表核对后决定落盘/丢弃。 */
    private static final ThreadLocal<List<Runnable>> DEFER_BLACKLIST = new ThreadLocal<>();

    /** 进入延迟拉黑捕获模式。 */
    public static void beginDeferBlackList() {
        if (DEFER_BLACKLIST.get() == null) {
            DEFER_BLACKLIST.set(new ArrayList<>());
        }
    }

    /**
     * 退出捕获模式。{@code commit=true} 时把缓冲的拉黑动作全部执行，否则丢弃。
     */
    public static void endDeferBlackList(boolean commit) {
        List<Runnable> pending = DEFER_BLACKLIST.get();
        DEFER_BLACKLIST.remove();
        if (pending == null || !commit) {
            return;
        }
        for (Runnable action : pending) {
            try {
                action.run();
            } catch (Throwable t) {
                Log.error("endDeferBlackList err:");
                Log.printStackTrace(TAG, t);
            }
        }
    }

    /** 处于捕获模式时把动作入缓冲并返回 true；否则返回 false（调用方直接执行）。 */
    private static boolean deferBlackList(Runnable action) {
        List<Runnable> pending = DEFER_BLACKLIST.get();
        if (pending == null) {
            return false;
        }
        pending.add(action);
        return true;
    }

    /**
     * 判断响应是否为「可重试的临时故障」，用于决定是否留到下一轮再试（而非当场拉黑）。
     * <p>注意：不含 102（服务端繁忙），调用方若两者都要判，需写成
     * {@code isRetryable(jo) || isServerBusy(jo)}。
     */
    public static boolean isRetryable(JSONObject jo) {
        if (jo == null) {
            return false;
        }
        String code = jo.optString("code", "").trim();
        if (code.isEmpty()) {
            code = jo.optString("resultCode", "").trim();
        }
        if (code.isEmpty()) {
            code = jo.optString("errorCode", "").trim();
        }
        return jo.optBoolean("retryable", false)
                || jo.optBoolean("retriable", false)
                || "3000".equals(code)
                || "REMOTE_INVOKE_EXCEPTION".equals(code);
    }

    /**
     * 判断响应是否为「服务端繁忙」（102），用于日志降噪。
     */
    public static boolean isServerBusy(JSONObject jo) {
        if (jo == null) return false;
        String code = jo.optString("resultCode", jo.optString("code", ""));
        return code.contains("102");
    }

    /**
     * 判断响应是否为「不支持rpc调用」（400000040），用于决定是否走备用接口。
     */
    public static boolean isUnsupportedRpc(JSONObject jo) {
        if (jo == null) return false;
        String code = jo.optString("resultCode", jo.optString("code", ""));
        return code.contains("400000040");
    }

    /**
     * 判断响应是否为**硬阻塞**（风控拦截 / 访问被拒），用于让调用方停止对后续对象继续发请求。
     *
     * <p>判据已统一收敛到 {@link TaskBlacklist#isHardBlocked}，这里只做转发，避免两处逻辑漂移。
     * 注意：硬阻塞<b>不等于</b>要拉黑——1009 是「整个账号被限流」而非「这个任务做不了」，
     * 正确做法是全局停机（见 {@link TaskBlacklist.Kind#HARD_BLOCK}）。
     */
    public static boolean isHardBlocked(JSONObject jo) {
        return TaskBlacklist.isHardBlocked(jo);
    }

}
