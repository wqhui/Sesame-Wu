package com.sesame.wu.model.base;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.sesame.wu.hook.ApplicationHook;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.MessageUtil;
import com.sesame.wu.util.TimeUtil;

/**
 * 任务完成的备用接口：finishTask 被 {@code 400000040}「不支持rpc调用」拒绝时，
 * 换 {@code com.alipay.antfarm.doFarmTask} 再试。
 *
 * <p>该接口的响应不可信（常回 102「服务器正在开小差」而任务其实已生效），
 * 所以只发不判，成败一律以任务列表为准（{@link #verify} 或下一轮列表）。
 *
 * <p>参考 Sesame-M 2bb8290f。
 */
public final class TaskAlternative {

    /** 默认 version，各模块可沿用自己那份。 */
    public static final String DEFAULT_VERSION = "1.8.2302070202.46";

    /**
     * 默认场景码 / 来源：<b>仅当调用方没给本家值时兜底</b>。
     *
     * <p>这两个值原本被写死在 {@link #request} 里，导致森林、会员、金豆等模块的伪申报
     * 全部冒用庄园场景码——服务端判「场景不合法」，兜底形同虚设。各模块应通过带
     * {@code sceneCode}/{@code source} 参数的重载传自己的值。
     */
    public static final String DEFAULT_SCENE_CODE = "ANTFARM";

    public static final String DEFAULT_SOURCE = "H5";

    /**
     * 交易/履约类任务的 bizKey 长拼音词（≥6 字母）。这类任务只能靠真实交易完成，
     * 用 doFarmTask 伪申报会被判风险操作（服务端回 1009 风控），一律不发。
     * <p>长度足够区分，用子串匹配。
     */
    private static final String[] TRANSACTION_LONG_KEYWORDS = {
            // 支付 / 下单 / 缴费 / 充值（拼音连写）
            "xiadan", "zhifu", "goumai", "jiaofei", "huankuan", "chongzhi",
            // 履约：寄件 / 回收
            "kuaidi", "huishou",
            // 酒店
            "jiudian",
            // 捐款 / 筹款：需真实付款，伪申报会被判风险操作（1009）。
            // choukuan 覆盖抽抽乐筹款类（bizKey 形如 IP_chouchoule_choukuan，
            // 对应「伸出援手，点亮希望」这类任务）。
            "juankuan", "choukuan",
    };

    /**
     * 英文短词（≤5 字母）：两侧不得紧邻字母，否则会被任意编码串夹带而误命中。
     * <p>实测「逛羊舍喂小羊」等良民任务曾被短词夹带误判为交易类并永久拉黑。
     */
    private static final String[] TRANSACTION_SHORT_KEYWORDS = {
            "pay", "charge",
            // 租赁 / 出行 / 机票 / 余额宝
            "zulin", "zuche", "dache", "jipiao", "yuebao",
    };

    /** {@code _cz} 后跟数字（充值 N 元）的判据，如 {@code 2026cc_cz6ylyb_fz}。 */
    private static final Pattern CZ_AMOUNT = Pattern.compile("_cz[0-9]");

    /** bizKey 是否属于交易/履约类（下单、支付、购买、缴费、还款、充值、淘宝）。 */
    public static boolean isTransactionTask(String bizKey) {
        if (bizKey == null || bizKey.isEmpty()) {
            return false;
        }
        String key = bizKey.toLowerCase();
        for (String keyword : TRANSACTION_LONG_KEYWORDS) {
            if (key.contains(keyword)) {
                return true;
            }
        }
        for (String keyword : TRANSACTION_SHORT_KEYWORDS) {
            // 两侧不得紧邻字母：避免 pay/charge/dache 等短词被任意编码串夹带误命中
            if (Pattern.compile("(?<![a-z])" + keyword + "(?![a-z])").matcher(key).find()) {
                return true;
            }
        }
        // 游戏内充值：game_cz 明确，或 _cz 后跟数字（裸 _cz 结尾不算，避免误伤含 cz 的普通任务）
        return key.contains("game_cz") || CZ_AMOUNT.matcher(key).find();
    }

    /** 日志出口——委托给调用方，便于区分模块日志前缀。 */
    public interface LogSink {
        void log(String message);
    }

    private TaskAlternative() {
    }

    // ==================== 请求 ====================

    /** doFarmTask payload，返回原始响应字符串。 */
    public static String request(String bizKey, String taskSceneCode, String version) {
        return request(bizKey, taskSceneCode, version, DEFAULT_SCENE_CODE, DEFAULT_SOURCE);
    }

    /** 只指定场景码，来源用默认值。 */
    public static String request(String bizKey, String taskSceneCode, String version,
                                 String sceneCode) {
        return request(bizKey, taskSceneCode, version, sceneCode, DEFAULT_SOURCE);
    }

    /**
     * 完整形态：{@code sceneCode} 与 {@code source} 都由调用方按自己的模组给。
     *
     * <p>混用会被服务端判「场景不合法」，所以模块必须传本家值，例如
     * 森林 {@code sceneCode + ANTFOREST}、抽抽乐 {@code taskSceneCode + ADBASICLIB}。
     * 传空则回落到 {@link #DEFAULT_SCENE_CODE} / {@link #DEFAULT_SOURCE}。
     */
    public static String request(String bizKey, String taskSceneCode, String version,
                                 String sceneCode, String source) {
        if (isTransactionTask(bizKey)) {
            Log.i("doFarmTask⏭️跳过交易/履约类任务#bizKey=" + bizKey + "，不自动申报");
            return "{}";
        }
        String scene = (sceneCode == null || sceneCode.isEmpty()) ? DEFAULT_SCENE_CODE : sceneCode;
        String src = (source == null || source.isEmpty()) ? DEFAULT_SOURCE : source;
        String args = "[{\"bizKey\":\"" + bizKey + "\",\"requestType\":\"RPC\","
                + "\"sceneCode\":\"" + scene + "\",\"source\":\"" + src + "\","
                + "\"taskSceneCode\":\"" + taskSceneCode + "\","
                + "\"version\":\"" + version + "\"}]";
        return ApplicationHook.requestString("com.alipay.antfarm.doFarmTask", args);
    }

    /** 同 request，返回值包装为 JSONObject。 */
    public static JSONObject doFarmTask(String bizKey, String taskSceneCode, String version)
            throws JSONException {
        return doFarmTask(bizKey, taskSceneCode, version, DEFAULT_SCENE_CODE, DEFAULT_SOURCE);
    }

    /** 只指定场景码，来源用默认值。 */
    public static JSONObject doFarmTask(String bizKey, String taskSceneCode, String version,
                                        String sceneCode) throws JSONException {
        return doFarmTask(bizKey, taskSceneCode, version, sceneCode, DEFAULT_SOURCE);
    }

    /** 完整形态，见 {@link #request(String, String, String, String, String)}。 */
    public static JSONObject doFarmTask(String bizKey, String taskSceneCode, String version,
                                        String sceneCode, String source) throws JSONException {
        return new JSONObject(request(bizKey, taskSceneCode, version, sceneCode, source));
    }

    // ==================== 工具方法 ====================

    /** 日志片段 {@code resultCode/memo}（{@code desc}、{@code resultDesc} 兜底）。 */
    public static String describe(JSONObject jo) {
        if (jo == null) {
            return "无响应";
        }
        String code = jo.optString("resultCode", jo.optString("code", ""));
        String memo = jo.optString("memo",
                jo.optString("desc", jo.optString("resultDesc", "")));
        if (code.isEmpty() && memo.isEmpty()) {
            return "无响应";
        }
        return code + "/" + memo;
    }

    /** 是否该走备用接口：400000040 且 taskSceneCode 非空。 */
    public static boolean hit(JSONObject failJo, String taskSceneCode) {
        return taskSceneCode != null && !taskSceneCode.trim().isEmpty()
                && MessageUtil.isUnsupportedRpc(failJo);
    }

    // ==================== 触发 ====================

    /** version 取 {@link #DEFAULT_VERSION}，场景码/来源取默认值。 */
    public static JSONObject trigger(Map<String, String> pending, String taskId,
                                      String taskTitle, String bizKey, String taskSceneCode,
                                      String logPrefix, LogSink sink) {
        return trigger(pending, taskId, taskTitle, bizKey, taskSceneCode,
                DEFAULT_VERSION, DEFAULT_SCENE_CODE, DEFAULT_SOURCE, logPrefix, sink);
    }

    /** 场景码/来源取默认值。 */
    public static JSONObject trigger(Map<String, String> pending, String taskId,
                                      String taskTitle, String bizKey, String taskSceneCode,
                                      String version, String logPrefix, LogSink sink) {
        return trigger(pending, taskId, taskTitle, bizKey, taskSceneCode,
                version, DEFAULT_SCENE_CODE, DEFAULT_SOURCE, logPrefix, sink);
    }

    /** 只指定场景码，来源用默认值。 */
    public static JSONObject trigger(Map<String, String> pending, String taskId,
                                      String taskTitle, String bizKey, String taskSceneCode,
                                      String version, String sceneCode,
                                      String logPrefix, LogSink sink) {
        return trigger(pending, taskId, taskTitle, bizKey, taskSceneCode,
                version, sceneCode, DEFAULT_SOURCE, logPrefix, sink);
    }

    /**
     * 完整形态：{@code sceneCode} 与 {@code source} 都由调用方按自己的模组给。
     *
     * <p>发请求 + 打日志 + 登记 {@code taskId -> taskTitle}（{@code pending} 可为 null）；
     * 不做成败判定。模块必须传本家值（见
     * {@link #request(String, String, String, String, String)}），否则会被服务端判「场景不合法」。
     *
     * @return doFarmTask 的响应；调用异常返回 null（按未触发处理）
     */
    public static JSONObject trigger(Map<String, String> pending, String taskId,
                                      String taskTitle, String bizKey, String taskSceneCode,
                                      String version, String sceneCode, String source,
                                      String logPrefix, LogSink sink) {
        try {
            if (isTransactionTask(bizKey)) {
                Log.i(logPrefix + "⏭️跳过[" + taskTitle + "]#bizKey=" + bizKey + "，交易/履约类不自动申报");
                return null;
            }
            JSONObject doFarmJo = doFarmTask(bizKey, taskSceneCode, version, sceneCode, source);
            if (pending != null && taskId != null && !taskId.isEmpty()) {
                pending.put(taskId, taskTitle);
            }
            String message = logPrefix + "🕓已触发[" + taskTitle
                    + "]#doFarmTask=" + describe(doFarmJo) + "，结果以任务列表为准";
            if (sink != null) {
                sink.log(message);
            } else {
                Log.record(message);
            }
            return doFarmJo;
        } catch (Throwable t) {
            Log.record("TaskAlternative trigger err: " + t.getMessage());
            Log.printStackTrace(t);
            return null;
        }
    }

    // ==================== 同轮核对 ====================

    /** 同轮核对配置。{@code blacklistByTitle} true=拉黑写任务标题，false=写 taskId。 */
    public static final class VerifyConfig {
        private final String moduleName;
        private final String taskListField;
        private final String listDisplay;
        private final String logPrefix;
        private final String doneTag;
        private final boolean blacklistByTitle;
        private final LogSink sink;

        public VerifyConfig(String moduleName, String taskListField, String listDisplay,
                            String logPrefix, String doneTag, boolean blacklistByTitle,
                            LogSink sink) {
            this.moduleName = moduleName;
            this.taskListField = taskListField;
            this.listDisplay = listDisplay;
            this.logPrefix = logPrefix;
            this.doneTag = doneTag;
            this.blacklistByTitle = blacklistByTitle;
            this.sink = sink;
        }
    }

    /** 重拉任务列表并返回仍未完成的 id；拉取失败返回 null。 */
    public interface TaskListSnapshot {
        Set<String> stillTodo() throws Exception;
    }

    /**
     * 同轮核对：等 3 秒 → 重拉列表 → 仍未完成才拉黑，否则记「完成（已按任务列表核对）」。
     *
     * @return 是否有任务确认完成
     */
    public static boolean verify(Map<String, String> pending, VerifyConfig cfg,
                                  TaskListSnapshot snapshot) {
        if (pending == null || pending.isEmpty()) {
            return false;
        }
        Map<String, String> batch = new LinkedHashMap<>(pending);
        pending.clear();
        try {
            TimeUtil.sleep(3000);
            Set<String> stillTodo = snapshot.stillTodo();
            if (stillTodo == null) {
                return false;
            }
            boolean changed = false;
            for (Map.Entry<String, String> item : batch.entrySet()) {
                String key = item.getKey();
                String title = item.getValue();
                if (stillTodo.contains(key)) {
                    cfg.sink.log(cfg.logPrefix + "⚠️未完成[" + title
                            + "]#doFarmTask 未生效，已交给自动拉黑机制");
                    MessageUtil.MarkTaskBlackList(cfg.moduleName, cfg.taskListField,
                            cfg.listDisplay, cfg.blacklistByTitle ? title : key);
                } else {
                    cfg.sink.log(cfg.logPrefix + cfg.doneTag + "[" + title
                            + "]#doFarmTask（已按任务列表核对）");
                    changed = true;
                }
            }
            return changed;
        } catch (Throwable t) {
            Log.record("TaskAlternative verify err: " + t.getMessage());
            Log.printStackTrace(t);
            return false;
        }
    }
}