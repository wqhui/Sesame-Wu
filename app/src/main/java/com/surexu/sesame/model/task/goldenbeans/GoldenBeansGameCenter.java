package com.surexu.sesame.model.task.goldenbeans;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.surexu.sesame.model.task.antGame.GameCenterPlayRpcCall;
import com.surexu.sesame.model.task.antGame.GameTask;
import com.surexu.sesame.util.Log;
import com.surexu.sesame.util.Status;

/**
 * 金豆乐园奖励处理：有抽奖次数时优先抽奖，次数用尽后按游戏权益做上报。
 * <p>
 * 游戏权益候选的识别统一复用 {@link GameCenterPlayRpcCall#collectDeliveryBenefitCandidates}，
 * 使金豆、庄园、森林在「权益与抽奖配额独立收敛」上口径一致。
 * <p>
 * 每轮都重新拉取快照，只有服务端状态确实推进（抽奖次数变化或权益次数增加）才继续；
 * 若上报后状态没有变化，则记下当日跳过标记并结束，避免服务端不认账时反复请求。
 */
public final class GoldenBeansGameCenter {

    /** 收敛轮次上限，防止服务端状态回环导致死循环 */
    private static final int MAX_ROUND = 64;

    private GoldenBeansGameCenter() {
    }

    /**
     * @return 是否已无剩余可自动推进的乐园奖励
     */
    public static boolean run(int interval) {
        try {
            Set<String> attempted = new HashSet<>();
            for (int round = 0; round < MAX_ROUND; round++) {
                JSONObject snapshot = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                if (!GoldenBeansSupport.ok(snapshot)) {
                    Log.goldenBeans("金豆乐园⚠️列表查询失败[" + GoldenBeansSupport.describe(snapshot) + "]");
                    return false;
                }

                JSONObject drawRights = GoldenBeansSupport.findObject(snapshot, "gameCenterDrawRights");
                int quotaCanUse = drawRights != null ? Math.max(drawRights.optInt("quotaCanUse", 0), 0) : 0;
                int usedQuota = drawRights != null ? Math.max(drawRights.optInt("usedQuota", 0), 0) : 0;
                int quotaLimit = drawRights != null ? Math.max(drawRights.optInt("quotaLimit", 0), 0) : 0;

                // 有可用抽奖次数：优先抽奖
                if (quotaCanUse > 0) {
                    GoldenBeansSupport.pause(interval);
                    JSONObject drawResponse = GoldenBeansSupport.parse(goldenbeansRpcCall.drawLottery());
                    if (!GoldenBeansSupport.ok(drawResponse)) {
                        Log.goldenBeans("金豆乐园⚠️抽奖失败[" + GoldenBeansSupport.describe(drawResponse) + "]");
                        return false;
                    }
                    JSONObject after = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                    int afterQuota = quotaCanUse;
                    int afterUsed = usedQuota;
                    if (after != null) {
                        JSONObject afterRights = GoldenBeansSupport.findObject(after, "gameCenterDrawRights");
                        if (afterRights != null) {
                            afterQuota = Math.max(afterRights.optInt("quotaCanUse", quotaCanUse), 0);
                            afterUsed = Math.max(afterRights.optInt("usedQuota", usedQuota), 0);
                        }
                    }
                    if (afterQuota >= quotaCanUse && afterUsed <= usedQuota) {
                        Log.record("金豆乐园⚠️抽奖未确认#次数[" + usedQuota + "→" + afterUsed + "]");
                        return false;
                    }
                    Log.goldenBeans("金豆乐园🎰抽奖[第" + afterUsed + "/" + quotaLimit + "次]"
                            + GoldenBeansSupport.awardText(drawResponse));
                    continue;
                }

                // 无抽奖次数：尝试游戏权益上报（权益与抽奖配额独立收敛）
                int remainingDraws = Math.max(quotaLimit - usedQuota - quotaCanUse, 0);
                Map<String, GameCenterPlayRpcCall.DeliveryBenefitCandidate> candidates =
                        GameCenterPlayRpcCall.collectDeliveryBenefitCandidateMap(snapshot);
                GameCenterPlayRpcCall.DeliveryBenefitCandidate candidate = null;
                for (GameCenterPlayRpcCall.DeliveryBenefitCandidate item : candidates.values()) {
                    if (Status.hasFlagToday(skipFlag(item))) {
                        continue;
                    }
                    if ((item.hasPendingReward() || remainingDraws > 0)
                            && GameTask.matchAppId(item.getAppId()) != null
                            && !attempted.contains(item.key() + ":" + remainingDraws)) {
                        candidate = item;
                        break;
                    }
                }

                if (candidate == null) {
                    if (quotaLimit > 0 && usedQuota >= quotaLimit) {
                        Log.record("金豆乐园🎰今日次数已用尽[" + usedQuota + "/" + quotaLimit + "]");
                    } else {
                        Log.record("金豆乐园🎰无可自动推进项#次数[" + usedQuota + "/" + quotaLimit + "]");
                    }
                    return true;
                }

                attempted.add(candidate.key() + ":" + remainingDraws);
                GameTask gameTask = GameTask.matchAppId(candidate.getAppId());
                if (gameTask == null) {
                    continue;
                }
                int remaining = Math.max(candidate.remainingRewards(), remainingDraws);
                Log.goldenBeans("金豆乐园🎮游玩[" + gameTask.getTitle() + "]#目标[" + remaining + "]");
                GoldenBeansSupport.pause(interval);
                int successes = gameTask.reportSync("金豆乐园:" + gameTask.getTitle(), remaining);
                if (successes <= 0) {
                    Log.record("金豆乐园⚠️[" + gameTask.getTitle() + "]上报失败");
                    return false;
                }

                JSONObject after = GoldenBeansSupport.parse(goldenbeansRpcCall.fetchGameList());
                if (after == null) {
                    return false;
                }
                JSONObject afterRights = GoldenBeansSupport.findObject(after, "gameCenterDrawRights");
                int afterQuota = afterRights != null
                        ? Math.max(afterRights.optInt("quotaCanUse", quotaCanUse), 0) : quotaCanUse;
                int afterUsed = afterRights != null
                        ? Math.max(afterRights.optInt("usedQuota", usedQuota), 0) : usedQuota;
                GameCenterPlayRpcCall.DeliveryBenefitCandidate afterCandidate =
                        GameCenterPlayRpcCall.collectDeliveryBenefitCandidateMap(after).get(candidate.key());
                boolean candidateProgressed = afterCandidate != null
                        && afterCandidate.getRightTimes() > candidate.getRightTimes();
                boolean rightsProgressed = afterQuota > quotaCanUse || afterUsed > usedQuota;
                if (candidateProgressed || rightsProgressed) {
                    Log.goldenBeans("金豆乐园🎮[" + gameTask.getTitle() + "]权益["
                            + candidate.getRightTimes() + "→"
                            + (afterCandidate != null ? afterCandidate.getRightTimes() : candidate.getRightTimes())
                            + "]#抽奖次数[" + quotaCanUse + "→" + afterQuota + "]");
                    continue;
                }
                Status.flagToday(skipFlag(candidate));
                Log.record("金豆乐园⚠️[" + gameTask.getTitle() + "]状态未推进#今日不再尝试");
                return false;
            }
            Log.record("金豆乐园⚠️达到收敛轮次上限[" + MAX_ROUND + "]");
            return false;
        } catch (Throwable th) {
            Log.i(GoldenBeansSupport.TAG, "runGameCenterFlow err:");
            Log.printStackTrace(GoldenBeansSupport.TAG, th);
            return false;
        }
    }

    /** 上报后服务端未推进时的当日跳过标记，避免重复尝试同一个游戏权益 */
    private static String skipFlag(GameCenterPlayRpcCall.DeliveryBenefitCandidate candidate) {
        return "goldenBeans::gameSkip::" + candidate.getAppId() + ":" + candidate.getTaskId();
    }
}
