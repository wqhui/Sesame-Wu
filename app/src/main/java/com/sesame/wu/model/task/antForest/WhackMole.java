package com.sesame.wu.model.task.antForest;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import com.sesame.wu.util.Status;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.MessageUtil;
import com.sesame.wu.util.Statistics;
import com.sesame.wu.model.task.antForest.AntForestRpcCall;

/**
 * 6秒拼手速打地鼠
 * 整合版本：适配最新 RPC 定义
 */
public class WhackMole {
    private static final String TAG = "WhackMole";
    private static final String SOURCE = "senlinguangchangdadishu";
    private static final String EXEC_FLAG = "forest::whackMole::executed";
    /** 今日失败次数（累计值）：失败不再直接锁全天，累计到 MAX_FAILURES 才停手 */
    private static final String FAIL_FLAG = EXEC_FLAG + "::fail";
    /** 今日失败次数上限：失败不锁全天，但也不能无限重发请求 */
    private static final int MAX_FAILURES = 3;
    
    private static volatile int totalGames = 5;
    private static volatile int moleCount = 15; // 单局击打数上限，<=0 表示不限制
    private static final long ROUND_DURATION_MS = 6000L; // 单局时长：「6秒拼手速」
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors() * 2,
            Runtime.getRuntime().availableProcessors() * 2,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> new Thread(r, "WhackMole-Worker")
    );
    private static volatile boolean isRunning = false;
    
    /**
     * 运行模式
     */
    public enum Mode {
        COMPATIBLE,  // 兼容模式 (对应 old系列 RPC)
        AGGRESSIVE   // 激进模式 (对应 标准系列 RPC)
    }
    
    /**
     * 游戏会话信息
     */
    public static class GameSession {
        private final String token;
        private final int roundNumber;
        /**
         * 本局服务端下发的地鼠 id。
         * 为什么要带着它：结算只认服务端认可的击打记录，只能用这里下发、并真正打过的 id，
         * 不能再像旧实现那样提交写死的 1..20。
         */
        private final List<Long> moleIds;
        
        public GameSession(String token, int roundNumber, List<Long> moleIds) {
            this.token = token;
            this.roundNumber = roundNumber;
            this.moleIds = moleIds;
        }
        
        public String getToken() {
            return token;
        }
        
        public int getRoundNumber() {
            return roundNumber;
        }
        
        public List<Long> getMoleIds() {
            return moleIds;
        }
    }
    public static Boolean closeWhackMole() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.closeWhackMole());
            return MessageUtil.checkSuccess(TAG, jo);
        }
        catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return false;
    }
    /**
     * 设置总游戏局数
     */
    public static void setTotalGames(int games) {
        totalGames = games;
    }
    
    /**
     * 设置击打地鼠数量
     */
    public static void setMoleCount(int count) {
        moleCount = count;
    }
    
    /**
     * 异步启动打地鼠
     */
    public static void start(Mode mode) {
        EXECUTOR.submit(() -> startSuspend(mode));
    }
    
    /**
     * 同步启动打地鼠（对应Kotlin suspend函数）
     */
    public static void startSuspend(Mode mode) {
        if (isRunning) {
            Log.record("正在运行⏭️打地鼠游戏中，跳过重复启动");
            return;
        }
        // 今日已完成，或失败次数已达上限
        // 为什么要有这一层：失败时不再无条件落当日标记，改成累计失败次数，
        // 但失败次数到上限也必须停手，否则每一轮巡查都会重发整批开局请求
        if (Status.hasFlagToday(EXEC_FLAG) || Status.getIntFlagToday(FAIL_FLAG) >= MAX_FAILURES) {
            return;
        }
        isRunning = true;
        
        boolean finished = false;
        try {
            switch (mode) {
                case COMPATIBLE:
                    finished = runCompatibleMode();
                    break;
                case AGGRESSIVE:
                    finished = runAggressiveMode();
                    break;
            }
            // 当日标记只在真正完成时落；失败只累加次数
            // 为什么不再无条件 flagToday：旧逻辑失败一次就把当天锁死，再也等不到重试
            if (finished) {
                Status.flagToday(EXEC_FLAG);
            } else {
                // 失败不直接锁全天，但累计到上限就停手，避免每轮重发整批请求
                int failures = Status.getIntFlagToday(FAIL_FLAG) + 1;
                Status.setIntFlagToday(FAIL_FLAG, failures);
                if (failures >= MAX_FAILURES) {
                    Log.forest("森林能量⏭️打地鼠今日已失败" + failures + "次，不再重试");
                } else {
                    Log.forest("森林能量⏭️打地鼠本次未完成(第" + failures + "次失败)，稍后重试");
                }
            }
        } catch (Exception e) {
            Log.printStackTrace("打地鼠异常:", e);
        } finally {
            isRunning = false;
            Log.record("运行状态🎮打地鼠已重置");
        }
    }
    
    // ================= [ 兼容模式：对应 old 系列 RPC ] =================
    /** @return 是否真正完成（成功走到结算）；失败返回 false，交给失败计数重试 */
    private static boolean runCompatibleMode() {
        try {
            long startTs = System.currentTimeMillis();
            
            // 1. 开始游戏 (使用 oldstartWhackMole)
            String startRespStr = AntForestRpcCall.oldstartWhackMole(SOURCE);
            JSONObject response = new JSONObject(startRespStr);
            if (!response.optBoolean("success")) {
                Log.forest(response.optString("resultDesc", "开始失败"));
                return false;
            }
            JSONArray moleInfoArray = response.optJSONArray("moleInfo");
            String token = response.optString("token");
            if (moleInfoArray == null || moleInfoArray.length() == 0 || token.isEmpty()) {
                return false;
            }
            
            List<Long> allMoleIds = new ArrayList<>();
            List<Long> bubbleMoleIds = new ArrayList<>();
            for (int i = 0; i < moleInfoArray.length(); i++) {
                JSONObject mole = moleInfoArray.getJSONObject(i);
                long moleId = mole.getLong("id");
                allMoleIds.add(moleId);
                if (mole.has("bubbleId")) {
                    bubbleMoleIds.add(moleId);
                }
            }
            
            // 2. 打有能量球的地鼠 (使用 oldwhackMole)
            int hitCount = 0;
            int hitEnergy = 0;
            Random random = new Random();
            for (Long moleId : bubbleMoleIds) {
                try {
                    String whackRespStr = AntForestRpcCall.oldwhackMole(moleId, token, SOURCE);
                    JSONObject whackResp = new JSONObject(whackRespStr);
                    if (whackResp.optBoolean("success")) {
                        int energy = whackResp.optInt("energyAmount", 0);
                        hitCount++;
                        hitEnergy += energy;
                        Log.forest("森林能量⚡️[兼容打地鼠:" + moleId + "+" + energy + "g]");
                        if (hitCount < bubbleMoleIds.size()) {
                            Thread.sleep(100 + random.nextInt(201)); // 100-300ms 随机延迟
                        }
                    }
                } catch (Throwable t) {
                    // 忽略单个击打异常
                }
            }
            
            // 3. 计算剩余 ID 并结算 (使用 oldsettlementWhackMole)
            List<String> remainingIds = new ArrayList<>();
            for (Long moleId : allMoleIds) {
                if (!bubbleMoleIds.contains(moleId)) {
                    remainingIds.add(String.valueOf(moleId));
                    if (remainingIds.size() >= moleCount) {
                        break; // 限制击打数量
                    }
                }
            }
            
            // 等待至接近6秒时长
            long elapsedTime = System.currentTimeMillis() - startTs;
            long sleepTime = Math.max(0L, 6000L - elapsedTime - 200L);
            if (sleepTime > 0) {
                Thread.sleep(sleepTime);
            }
            
            // 执行结算
            String settleRespStr = AntForestRpcCall.oldsettlementWhackMole(token, remainingIds, SOURCE);
            JSONObject settleResp = new JSONObject(settleRespStr);
            if (!MessageUtil.checkSuccess(TAG, settleResp)) {
                return false;
            }
            int total = settleResp.optInt("totalEnergy", 0);
            Log.forest("森林能量⚡️[兼容模式完成(打" + (remainingIds.size() + hitCount) + "个)总能量+" + total + "g]");
            // 结算的 totalEnergy 与逐次击打的 energyAmount 是同一笔能量，只记一次
            int gained = Math.max(total, hitEnergy);
            if (gained > 0) {
                Statistics.addData(Statistics.DataType.COLLECTED, gained);
            }
            return true;
        } catch (Throwable t) {
            Log.record("兼容模式出错:" + (t.getMessage() != null ? t.getMessage() : "未知错误"));
        }
        return false;
    }
    
    // ================= [ 激进模式：对应 标准系列 RPC ] =================
    /**
     * 逐局「开始 → 逐个 whackMole → 用真正打中的地鼠 id 结算」。
     * <p>为什么必须改成这样：旧实现只批量调用 startSingleRound 开局，整个方法没有一次 whackMole 调用，
     * 结算时又提交写死的 1..20 假 id，服务端不可能认可，所以激进模式从来没有真正击打过地鼠。
     */
    private static boolean runAggressiveMode() {
        int totalEnergy = 0;
        int playedRounds = 0;
        int hitTotal = 0;
        for (int roundNum = 1; roundNum <= totalGames; roundNum++) {
            GameSession session = startSingleRound(roundNum);
            if (session == null) {
                // 开不了下一局（次数用尽/接口失败）：剩余轮次不再尝试
                break;
            }
            long roundStart = System.currentTimeMillis();
            List<Long> hitIds = whackRound(session);
            hitTotal += hitIds.size();
            try {
                // 单局 6 秒：打得太快就补足剩余时长，否则结算时这局还没结束
                long sleepTime = ROUND_DURATION_MS - (System.currentTimeMillis() - roundStart);
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            int roundEnergy = settleStandardRound(session, hitIds);
            totalEnergy += roundEnergy;
            playedRounds++;
            Log.i(TAG + ".round", "第" + roundNum + "局：可打" + session.getMoleIds().size()
                    + "个，击中" + hitIds.size() + "个，结算+" + roundEnergy + "g");
            if (roundEnergy <= 0) {
                // 结算没收益说明这一轮有问题（并非次数用尽），继续开新局只会白耗当天次数
                Log.i(TAG + ".round", "第" + roundNum + "局无收益，剩余轮次不再尝试");
                break;
            }
        }
        if (playedRounds > 0) {
            Log.forest("森林能量⚡️[激进模式" + playedRounds + "局/打" + hitTotal + "个#总计" + totalEnergy + "g]");
            if (totalEnergy > 0) {
                Statistics.addData(Statistics.DataType.COLLECTED, totalEnergy);
                Statistics.save();
            }
        } else {
            Log.forest("森林能量⏭️打地鼠激进模式一局都没开成");
        }
        // 拿到能量、确实命中过、或今日次数已用完(canPlayToday=false)都算完成；
        // 一局都没开成且不是次数用尽，才返回 false 交给失败计数重试
        return totalEnergy > 0 || hitTotal > 0 || Status.hasFlagToday(EXEC_FLAG);
    }
    
    /**
     * 启动单局游戏（激进模式），同时解析本局地鼠列表供逐只击打
     */
    private static GameSession startSingleRound(int round) {
        try {
            String startRespStr = AntForestRpcCall.startWhackMole();
            JSONObject startResp = new JSONObject(startRespStr);
            if (!MessageUtil.checkSuccess(TAG, startResp)) {
                return null;
            }
            
            // 检查今日是否可玩
            if (!startResp.optBoolean("canPlayToday", true)) {
                // 次数确实用完了：这是完成态，直接落当日标记，不必再重试
                Status.flagToday(EXEC_FLAG);
                Log.forest("森林能量⏭️打地鼠今日次数已用完");
                return null;
            }
            
            String token = startResp.optString("token", "");
            if (token.isEmpty()) {
                Log.i(TAG + ".start", "第" + round + "局未返回 token");
                return null;
            }
            // 必须把服务端下发的地鼠 id 带在会话里：只有这些 id 才能用于后续击打与结算
            JSONArray moleInfo = startResp.optJSONArray("moleInfo");
            List<Long> moleIds = new ArrayList<>();
            if (moleInfo != null) {
                for (int i = 0; i < moleInfo.length(); i++) {
                    JSONObject mole = moleInfo.optJSONObject(i);
                    if (mole == null) {
                        continue;
                    }
                    long moleId = mole.optLong("id", 0L);
                    if (moleId > 0L) {
                        moleIds.add(moleId);
                    }
                }
            }
            if (moleIds.isEmpty()) {
                Log.i(TAG + ".start", "第" + round + "局未返回 moleInfo");
                return null;
            }
            return new GameSession(token, round, moleIds);
        } catch (Exception e) {
            Log.printStackTrace("启动第" + round + "局异常:", e);
            return null;
        }
    }
    
    /**
     * 逐只击打本局地鼠，返回击打回执确认成功的 id。
     * <p>为什么只返回成功的 id：结算只认服务端认可的击打记录，提交未打中的 id 会让整局结算作废。
     */
    private static List<Long> whackRound(GameSession session) {
        List<Long> moleIds = session.getMoleIds();
        int limit = moleCount > 0 ? Math.min(moleCount, moleIds.size()) : moleIds.size();
        List<Long> hitIds = new ArrayList<>(limit);
        String firstFailDesc = null;
        for (int i = 0; i < limit; i++) {
            long moleId = moleIds.get(i);
            try {
                String raw = AntForestRpcCall.whackMole(moleId, session.getToken(), SOURCE);
                JSONObject jo = new JSONObject(raw);
                if (jo.optBoolean("success") || jo.optBoolean("isSuccess")) {
                    hitIds.add(moleId);
                } else if (firstFailDesc == null) {
                    firstFailDesc = jo.optString("resultDesc", "success=false");
                }
            } catch (Throwable t) {
                // 单只失败不影响其余地鼠，但首个失败原因要留痕，否则整局 0 击中无从判断
                if (firstFailDesc == null) {
                    firstFailDesc = String.valueOf(t);
                }
            }
        }
        if (hitIds.isEmpty() && firstFailDesc != null) {
            Log.i(TAG + ".whack", "第" + session.getRoundNumber() + "局全部未命中，首个失败: " + firstFailDesc);
        }
        return hitIds;
    }
    
    /**
     * 结算单局游戏（激进模式）：只提交真正打中的 id
     */
    private static int settleStandardRound(GameSession session, List<Long> hitIds) {
        if (hitIds.isEmpty()) {
            // 一个都没打中就别结算了：提交空列表只会换来一次无效请求
            Log.i(TAG + ".settlement", "第" + session.getRoundNumber() + "局没有击中地鼠，跳过结算");
            return 0;
        }
        try {
            List<String> ids = new ArrayList<>(hitIds.size());
            for (Long id : hitIds) {
                ids.add(String.valueOf(id));
            }
            String respStr = AntForestRpcCall.settlementWhackMole(session.getToken(), ids, SOURCE);
            JSONObject resp = new JSONObject(respStr);
            if (MessageUtil.checkSuccess(TAG, resp)) {
                int energy = resp.optInt("totalEnergy", 0);
                Log.i(TAG + ".settlement", "第" + session.getRoundNumber() + "局结算：提交" + ids.size()
                        + "个地鼠，+" + energy + "g");
                return energy;
            }
            Log.i(TAG + ".settlement", "第" + session.getRoundNumber() + "局结算失败: "
                    + resp.optString("resultDesc", "resultCode=" + resp.optString("resultCode")));
        } catch (Exception e) {
            Log.printStackTrace("结算第" + session.getRoundNumber() + "局失败:", e);
        }
        return 0;
    }
    
    /**
     * 关闭线程池（建议在应用退出时调用）
     */
    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}