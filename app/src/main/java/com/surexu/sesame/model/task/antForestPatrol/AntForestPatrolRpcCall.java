package com.surexu.sesame.model.task.antForestPatrol;

import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.util.RandomUtil;

/**
 * 保护地巡护（新版动物伙伴）接口封装。
 * <p>
 * 仅包含「动物伙伴领取能量 / 自动派遣 / 保护证书兑换」这条独立能力链路所需接口，
 * 沿用仓库既有约定：source 放在请求体内，不额外附加 header。
 */
public class AntForestPatrolRpcCall {

    private static final String MONOPOLY_SOURCE = "monopoly_home_popup";
    private static final String FOREST_SOURCE = "chInfo_ch_appcenter__chsub_9patch";

    private static String uniqueId() {
        return System.currentTimeMillis() + RandomUtil.getRandomString(8);
    }

    /** 查询新版巡护入口（含动物候选列表 creatureList） */
    public static String queryMonopolyEntryInfo() {
        String args1 = "[{\"source\":\"" + MONOPOLY_SOURCE + "\",\"uniqueId\":\"" + uniqueId() + "\"}]";
        return ApplicationHook.requestString("alipay.antisle.monopoly.h5.queryMonopolyEntryInfo", args1);
    }

    /** 查询当前占用中的动物伙伴（含可领取能量状态） */
    public static String queryUsingCreatureInfo(String uid) {
        String args1 = "[{\"source\":\"" + FOREST_SOURCE + "\",\"targetUserId\":\"" + uid
                + "\",\"uniqueId\":\"" + uniqueId() + "\",\"version\":\"20260623\"}]";
        return ApplicationHook.requestString("alipay.antisle.monopoly.h5.queryUsingCreatureInfo", args1);
    }

    /**
     * 领取新版动物伙伴派遣能量
     *
     * @param creatureCode 动物标识
     * @param shortDay     服务端下发的日期标识（yesterdayShortDay）
     */
    public static String collectMonopolyCreatureEnergy(String creatureCode, String shortDay) {
        String args1 = "[{\"creatureCode\":\"" + creatureCode + "\",\"shortDay\":\"" + shortDay
                + "\",\"source\":\"" + FOREST_SOURCE + "\",\"uniqueId\":\"" + uniqueId() + "\"}]";
        return ApplicationHook.requestString("alipay.antisle.monopoly.h5.collectMonopolyCreatureEnergy", args1);
    }

    /**
     * 派遣新版动物伙伴
     *
     * @param creatureCode 动物标识
     */
    public static String assignMonopolyCreature(String creatureCode) {
        String args1 = "[{\"creatureCode\":\"" + creatureCode
                + "\",\"secondConfirm\":false,\"source\":\"" + MONOPOLY_SOURCE
                + "\",\"uniqueId\":\"" + uniqueId() + "\"}]";
        return ApplicationHook.requestString("alipay.antisle.monopoly.h5.assignMonopolyCreature", args1);
    }

    /**
     * 查询保护证书可兑换资格
     *
     * @param projectId 证书项目标识（来自巡护地图下发链接）
     */
    public static String queryCertificate(String projectId) {
        String args1 = "[{\"projectId\":\"" + projectId
                + "\",\"source\":\"monopoly_auto_exchange\",\"version\":\"20240704\"}]";
        return ApplicationHook.requestString("alipay.antforest.forest.h5.queryTreeForExchange", args1);
    }

    /**
     * 兑换保护证书
     *
     * @param projectId 证书项目标识
     */
    public static String exchangeCertificate(long projectId) {
        String args1 = "[{\"projectId\":" + projectId
                + ",\"source\":\"monopoly_auto_exchange\",\"sToken\":\"" + System.currentTimeMillis()
                + "\",\"userManualSelect\":false,\"version\":\"20230501\"}]";
        return ApplicationHook.requestString("alipay.antmember.forest.h5.exchangeTree", args1);
    }
}
