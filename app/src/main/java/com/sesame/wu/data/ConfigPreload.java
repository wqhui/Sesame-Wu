package com.sesame.wu.data;

import com.sesame.wu.entity.AlipayAntMemberAdTaskList;
import com.sesame.wu.entity.AlipayAntMemberTaskList;
import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.StringUtil;
import com.sesame.wu.util.idMap.AlchemyAutoBlacklistMap;
import com.sesame.wu.util.idMap.AnimalIdMap;
import com.sesame.wu.util.idMap.AntDodoTaskListMap;
import com.sesame.wu.util.idMap.AntFarmDoFarmTaskListMap;
import com.sesame.wu.util.idMap.AntFarmDrawMachineTaskListMap;
import com.sesame.wu.util.idMap.AntForestHuntTaskListMap;
import com.sesame.wu.util.idMap.AntForestVitalityTaskListMap;
import com.sesame.wu.util.idMap.AntMemberAdTaskListMap;
import com.sesame.wu.util.idMap.AntMemberTaskListMap;
import com.sesame.wu.util.idMap.AntOceanAntiepTaskListMap;
import com.sesame.wu.util.idMap.AntOceanFishBlackListMap;
import com.sesame.wu.util.idMap.AntOrchardTaskListMap;
import com.sesame.wu.util.idMap.OrchardChouChouLeTaskListMap;
import com.sesame.wu.util.idMap.AntSportsTaskListMap;
import com.sesame.wu.util.idMap.AntStallTaskListMap;
import com.sesame.wu.util.idMap.BeachIdMap;
import com.sesame.wu.util.idMap.CooperationIdMap;
import com.sesame.wu.util.idMap.FarmOrnamentsIdMap;
import com.sesame.wu.util.idMap.GameCenterMallItemMap;
import com.sesame.wu.util.idMap.GoldenBeansTaskListMap;
import com.sesame.wu.util.idMap.MarathonIdMap;
import com.sesame.wu.util.idMap.MemberBenefitIdMap;
import com.sesame.wu.util.idMap.MemberCreditSesameTaskListMap;
import com.sesame.wu.util.idMap.NewAncientTreeIdMap;
import com.sesame.wu.util.idMap.PathThemeMapListMap;
import com.sesame.wu.util.idMap.PlantSceneIdMap;
import com.sesame.wu.util.idMap.PromiseSimpleTemplateIdMap;
import com.sesame.wu.util.idMap.ReserveIdMap;
import com.sesame.wu.util.idMap.TreeIdMap;
import com.sesame.wu.util.idMap.UserIdMap;
import com.sesame.wu.util.idMap.VitalityBenefitIdMap;
import com.sesame.wu.util.idMap.ForestHuntIdMap;
import com.sesame.wu.util.idMap.rpcRequestMap;

/**
 * 配置相关的预加载逻辑（原 SettingsActivity / NewSettingsActivity 中的初始化）。
 * miuix Compose 界面复用同一套数据，必须在这里完成 IdMap 加载与 ConfigV2.load，
 * 否则 SELECT_ONE / SELECT 等字段的选项列表为空。
 */
public final class ConfigPreload {

    private ConfigPreload() {
    }

    public static void prepare(String userId) {
        UserIdMap.setCurrentUserId(userId);
        UserIdMap.load(userId);
        CooperationIdMap.load(userId);
        VitalityBenefitIdMap.load(userId);
        GameCenterMallItemMap.load(userId);
        FarmOrnamentsIdMap.load(userId);
        MemberBenefitIdMap.load(userId);
        PromiseSimpleTemplateIdMap.load(userId);
        TreeIdMap.load();
        ReserveIdMap.load();
        AnimalIdMap.load();
        MarathonIdMap.load();
        NewAncientTreeIdMap.load();
        BeachIdMap.load();
        PlantSceneIdMap.load();
        rpcRequestMap.load();
        ForestHuntIdMap.load();
        MemberCreditSesameTaskListMap.load();
        AntForestVitalityTaskListMap.load();
        AntForestHuntTaskListMap.load();
        AntFarmDoFarmTaskListMap.load();
        AntFarmDrawMachineTaskListMap.load();
        AntDodoTaskListMap.load();
        AntOceanAntiepTaskListMap.load();
        AntOceanFishBlackListMap.load();
        AntOrchardTaskListMap.load();
        OrchardChouChouLeTaskListMap.load();
        AlchemyAutoBlacklistMap.load();
        AntStallTaskListMap.load();
        AntSportsTaskListMap.load();
        PathThemeMapListMap.load();
        AntMemberTaskListMap.load();
        // 会员任务与会员广告任务的候选各一份映射：分别载入，别让一方覆盖另一方。
        // 载入后清一次候选实体缓存——候选是静态列表，若在 load 之前被 getList() 建过一份，
        // 里面会是空表，清掉才会用新载入的映射重建。
        AlipayAntMemberTaskList.clear();
        AntMemberAdTaskListMap.load();
        AlipayAntMemberAdTaskList.clear();
        GoldenBeansTaskListMap.load();
        ConfigV2.load(userId);
    }

    public static boolean isEmpty(String userId) {
        return StringUtil.isEmpty(userId);
    }

    public static java.io.File getConfigFile(String userId) {
        if (StringUtil.isEmpty(userId)) {
            return FileUtil.getDefaultConfigV2File();
        }
        return FileUtil.getConfigV2File(userId);
    }
}
