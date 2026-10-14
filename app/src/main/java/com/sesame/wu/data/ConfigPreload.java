package com.sesame.wu.data;

import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.StringUtil;
import com.sesame.wu.util.idMap.AlchemyAutoBlacklistMap;
import com.sesame.wu.util.idMap.AnimalIdMap;
import com.sesame.wu.util.idMap.AntDodoTaskListMap;
import com.sesame.wu.util.idMap.AntFarmDoFarmTaskListMap;
import com.sesame.wu.util.idMap.AntFarmDrawMachineTaskListMap;
import com.sesame.wu.util.idMap.AntForestHuntTaskListMap;
import com.sesame.wu.util.idMap.AntForestVitalityTaskListMap;
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
