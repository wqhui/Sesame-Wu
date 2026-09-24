package com.surexu.sesame.model.task.antGame;

import static com.surexu.sesame.hook.AlipayMiniMarkHelper.getAlipayMiniMark;

import com.surexu.sesame.hook.AlipayMiniMarkHelper;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.hook.AuthCodeHelper;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.idMap.UserIdMap;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 游戏任务上报工具类
 * 对应原Kotlin的GameTask枚举类
 */
public enum GameTask {

    Orchard_ncscc("农场上车车", "2060170000356601", "zfb_ncscc", "ncscc_game_kaiche_every_10", "nongchangleyuan", "1.0.2", 2),
    Farm_ddply("对对碰乐园", "2021004149679303", "zfb_ddply", "ddply_game_xiaochu_every_5", "zhuangyuan", "1.0.14", 2),
    Forest_slxcc("森林小车车", "2060170000363691", "zfb_slxcc", "slxcc_game_kaiche_every_10", "lianyun_senlin_leyuan", "1.0.1", 3),
    Forest_sljyd("森林救援队(能量雨)", "2021005113684028", "zfb_sljydx", "sljyd_game_xiaochu_every_10", "lianyun_senlin_leyuan", "1.0.1", 3);
    //Forest_sgbhsd("三国冰河时代", "2021004173661702", "zfb_sgbhsd", "cclyx_sgbhsd_3c_zm10c", "lianyun_senlin_leyuan", "0.94.1", 3);

    //Farm_lhs("灵画师", "2021005122634802", "lhs", "lhs", "lianyun_zhuangyuan_v2", "0.0.89", 3);


    private final String title;
    private final String appId;
    private final String gid;
    private final String action;
    private final String channel;
    private final String version;
    private final int requestsPerEgg; // 完成1个🥚要多少次 为了防止网络崩溃 多加1次
    private String cachedToken; // 缓存登录Token

    /**
     * 单次上报结果，保留失败原因，便于调用方记录与回查。
     */
    private static class SingleReportResult {
        private final boolean success;
        private final String message;

        SingleReportResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }
    }

    /**
     * 上报任务结果：区分「目标奖励数」「所需成功次数」「实际尝试次数」与「成功次数」，
     * 便于调用方判断是否真正完成，而不是只看是否有异常抛出。
     */
    public static class ReportResult {
        private final int requestedRewards;
        private final int requiredSuccesses;
        private final int attemptedReports;
        private final int successfulReports;
        private final String failureMessage;

        ReportResult(int requestedRewards, int requiredSuccesses, int attemptedReports,
                     int successfulReports, String failureMessage) {
            this.requestedRewards = requestedRewards;
            this.requiredSuccesses = requiredSuccesses;
            this.attemptedReports = attemptedReports;
            this.successfulReports = successfulReports;
            this.failureMessage = failureMessage == null ? "" : failureMessage;
        }

        public int getRequestedRewards() {
            return requestedRewards;
        }

        public int getRequiredSuccesses() {
            return requiredSuccesses;
        }

        public int getAttemptedReports() {
            return attemptedReports;
        }

        public int getSuccessfulReports() {
            return successfulReports;
        }

        public String getFailureMessage() {
            return failureMessage;
        }

        /** 是否达到完成任务所需的最小成功次数 */
        public boolean isCompleted() {
            return requiredSuccesses > 0 && successfulReports >= requiredSuccesses;
        }
    }

    /**
     * 根据小程序 appId 匹配游戏任务（金豆乐园游戏权益上报使用）
     */
    public static GameTask matchAppId(String appId) {
        if (appId == null || appId.isEmpty()) {
            return null;
        }
        for (GameTask task : values()) {
            if (appId.equals(task.appId)) {
                return task;
            }
        }
        return null;
    }

    public String getAppId() {
        return appId;
    }

    public String getTitle() {
        return title;
    }

    /**
     * 枚举构造方法
     */
    GameTask(String title, String appId, String gid, String action, String channel, String version, int requestsPerEgg) {
        this.title = title;
        this.appId = appId;
        this.gid = gid;
        this.action = action;
        this.channel = channel;
        this.version = version;
        this.requestsPerEgg = requestsPerEgg;
    }

    /**
     * 第一步：登录获取 Token 并缓存
     */
    private String login(String gameType) {
        try {
            String authCode = AuthCodeHelper.getAuthCode(appId);
            String mark = getAlipayMiniMark(appId, version);
            String reqId = System.currentTimeMillis() + "_" + new Random().nextInt(350) + 1;

            JSONObject bodyJson = new JSONObject();
            bodyJson.put("v", version);
            bodyJson.put("code", authCode);
            bodyJson.put("pf", "zfb");
            bodyJson.put("reqId", reqId);
            bodyJson.put("gid", gid);
            bodyJson.put("version", version);
            String body = bodyJson.toString();

            //Log.other("login 请求体 -> " + body);

            // 建立HTTP连接
            URL url = new URL("https://gamesapi2.aslk2018.com/v2/game/login");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("alipayMiniMark", mark);
            conn.setRequestProperty("User-Agent", getDynamicUA());
            conn.setRequestProperty("x-release-type", "ONLINE");

            // 写入请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body);
            }

            // 处理响应（包含错误流）
            int respCode = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    respCode >= 200 && respCode <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
            ));
            StringBuilder responseText = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseText.append(line);
            }
            reader.close();
            conn.disconnect();

            //Log.other("login 响应 -> HTTP " + respCode + " " + responseText);

            // 解析响应JSON
            JSONObject resJson = new JSONObject(responseText.toString());
            if (resJson.optInt("code") == 1) {
                JSONObject data = resJson.optJSONObject("data");
                if (data != null) {
                    this.cachedToken = data.optString("token");
                    Log.record("登录成功✅Token已获取");
                    return this.cachedToken;
                }
            } else {
                Log.error("登录接口❌报错(" + gameType + " Code" + respCode + "):" + responseText);
            }
        } catch (Exception e) {
            Log.error("登录过程🚨抛出异常(" + gameType + "):" + e.getMessage());
        }
        return null;
    }

    /**
     * 外部调用：执行上报任务（异步）
     * @param gameType 日志展示用的场景名
     * @param eggCount 目标蛋数量
     */
    public void report(String gameType, int eggCount) {
        new Thread(() -> {
            Log.record("开始执行🚀" + gameType + "游戏任务:目标" + eggCount + "个蛋");
            ReportResult result = reportDetailed(gameType, eggCount, this.channel, true);
            if (result.isCompleted()) {
                Log.record(gameType + "游戏任务🏁已完成[" + result.getSuccessfulReports()
                        + "/" + result.getRequiredSuccesses() + "]");
            } else {
                Log.error("⚠️ " + gameType + "游戏任务未完成: "
                        + (result.getFailureMessage().isEmpty() ? "上报次数不足" : result.getFailureMessage())
                        + "(成功" + result.getSuccessfulReports() + "/" + result.getRequiredSuccesses() + ")");
            }
        }).start();
    }

    /**
     * 同步执行上报任务，返回成功上报次数。
     * 用于需要等待结果并回查服务端状态的场景（如金豆乐园游戏权益）。
     *
     * @param gameType 日志展示用的场景名
     * @param eggCount 目标蛋数量
     * @return 成功上报的次数，失败返回已成功的次数
     */
    public int reportSync(String gameType, int eggCount) {
        return reportDetailed(gameType, eggCount, this.channel, true).getSuccessfulReports();
    }

    /** 同步执行上报任务并返回结构化结果 */
    public ReportResult reportDetailed(String gameType, int eggCount) {
        return reportDetailed(gameType, eggCount, this.channel, true);
    }

    /**
     * 同步执行上报任务，返回结构化结果。
     * <p>
     * 与旧实现相比：请求次数改为「所需成功次数 + 1 次兜底」，进度按 requestsPerEgg 汇报，
     * 失败时保留服务端原始响应，便于调用方决定是否回查。
     *
     * @param gameType             日志展示用的场景名
     * @param eggCount             目标蛋数量
     * @param actionFinishChannel  上报使用的 action_finish_channel
     * @param includeSafetyReport  是否额外多发一次作为网络兜底
     */
    public ReportResult reportDetailed(String gameType, int eggCount, String actionFinishChannel,
                                      boolean includeSafetyReport) {
        if (eggCount <= 0) {
            return new ReportResult(eggCount, 0, 0, 0, "");
        }
        if (actionFinishChannel == null || actionFinishChannel.isEmpty()) {
            return new ReportResult(eggCount, eggCount * this.requestsPerEgg, 0, 0, "action_finish_channel为空");
        }

        int requiredSuccesses = eggCount * this.requestsPerEgg;
        int totalReports = requiredSuccesses + (includeSafetyReport ? 1 : 0);
        this.cachedToken = login(gameType);
        if (this.cachedToken == null || this.cachedToken.isEmpty()) {
            Log.error("无法获取⚠️有效的Token，放弃上报任务");
            return new ReportResult(eggCount, requiredSuccesses, 0, 0, "无法获取有效Token");
        }

        int attemptedReports = 0;
        int successfulReports = 0;
        String failureMessage = "";
        for (int i = 1; i <= totalReports; i++) {
            attemptedReports++;
            SingleReportResult single = executeSingleReport(gameType, i, totalReports, actionFinishChannel);
            if (!single.success) {
                failureMessage = single.message;
                break;
            }
            successfulReports++;
            if (i % this.requestsPerEgg == 0) {
                Log.other("游戏进度📈" + gameType + "[" + i + "/" + requiredSuccesses
                        + "](达成" + (i / this.requestsPerEgg) + "个)");
            }
            if (i < totalReports) {
                try {
                    Thread.sleep(new Random().nextInt(2001) + 1000); // 1000-3000ms随机休眠
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failureMessage = "上报被中断";
                    break;
                }
            }
        }
        return new ReportResult(eggCount, requiredSuccesses, attemptedReports, successfulReports, failureMessage);
    }

    /**
     * 执行单次上报请求
     * @param current 当前请求次数
     * @param total 总请求次数
     * @param actionFinishChannel 上报使用的 action_finish_channel
     * @return 单次上报结果（含失败原因）
     */
    private SingleReportResult executeSingleReport(String gameType, int current, int total,
                                                   String actionFinishChannel) {
        try {
            String mark = getAlipayMiniMark(appId, version);
            String reqId = System.currentTimeMillis() + "_" + (new Random().nextInt(90) + 10); // 10-99随机数

            // 构建请求体
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("v", version);
            bodyJson.put("version", version);
            bodyJson.put("reqId", reqId);
            bodyJson.put("gid", gid);
            bodyJson.put("action_code", action);
            bodyJson.put("action_finish_channel", actionFinishChannel);
            String body = bodyJson.toString();

            //Log.other("taskReport 请求体 -> " + body);

            // 建立HTTP连接
            URL url = new URL("https://gamesapi2.aslk2018.com/v2/zfb/taskReport");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("authorization", this.cachedToken);
            conn.setRequestProperty("alipayMiniMark", mark);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", getDynamicUA());
            conn.setRequestProperty("x-release-type", "ONLINE");
            conn.setRequestProperty("referer", "https://" + appId + ".hybrid.alipay-eco.com/" + appId + "/" + version + "/index.html");

            // 写入请求体
            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(body);
            }

            // 处理响应
            int respCode = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    respCode >= 200 && respCode <= 299 ? conn.getInputStream() : conn.getErrorStream(),
                    StandardCharsets.UTF_8
            ));
            StringBuilder responseText = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseText.append(line);
            }
            reader.close();
            conn.disconnect();

            //Log.other("taskReport 响应 -> HTTP " + respCode + " " + responseText);

            // 解析响应
            JSONObject resJson = new JSONObject(responseText.toString());
            if (resJson.optInt("code") == 1) {
                return new SingleReportResult(true, "");
            } else {
                String message = "第 " + current + "/" + total + " 次上报业务失败 (HTTP " + respCode + "): " + responseText;
                Log.error("⚠️ " + message);
                return new SingleReportResult(false, message);
            }
        } catch (IOException e) {
            String message = "第 " + current + "/" + total + " 次请求发生网络崩溃:" + e;
            Log.error("🚨 " + message);
            return new SingleReportResult(false, message);
        } catch (Exception e) {
            String message = "第 " + current + "/" + total + " 次请求发生异常:" + e;
            Log.error("🚨 " + message);
            return new SingleReportResult(false, message);
        }
    }

    /**
     * 获取动态User-Agent
     * @return 拼接后的UA字符串
     */
    private String getDynamicUA() {
        String systemUa = System.getProperty("http.agent");
        if (systemUa == null || systemUa.isEmpty()) {
            systemUa = "Mozilla/5.0 (Linux; Android 11)";
        }
        String alipayVer = String.valueOf(ApplicationHook.getAlipayVersion());
        return systemUa + " NebulaSDK/1.8.100112 Nebula AliApp(AP/" + alipayVer + ") AlipayClient/" + alipayVer;
    }
}