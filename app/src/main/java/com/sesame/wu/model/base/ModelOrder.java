package com.sesame.wu.model.base;

import java.util.ArrayList;
import java.util.List;

import com.sesame.wu.data.Model;
import com.sesame.wu.model.extensions.ExtensionsHandle;
import com.sesame.wu.model.normal.answerAI.AnswerAI;
import com.sesame.wu.model.normal.base.BaseModel;
import com.sesame.wu.model.task.antDodo.AntDodo;
import com.sesame.wu.model.task.antFarm.AntFarm;
import com.sesame.wu.model.task.antForest.AntForestV2;
import com.sesame.wu.model.task.antForestPatrol.AntForestPatrol;
import com.sesame.wu.model.task.antMember.AntMember;
import com.sesame.wu.model.task.antOcean.AntOcean;
import com.sesame.wu.model.task.antOrchard.AntOrchard;
import com.sesame.wu.model.task.antSports.AntSports;
import com.sesame.wu.model.task.antStall.AntStall;
import com.sesame.wu.model.task.goldenbeans.goldenbeans;
import com.sesame.wu.model.task.greenFinance.GreenFinance;
import com.sesame.wu.model.task.kbOrchard.KBOrchard;
import com.sesame.wu.model.task.protectEcology.ProtectEcology;
import com.sesame.wu.model.task.youthPrivilege.YouthPrivilege;
import lombok.Getter;

public class ModelOrder {

    @Getter
    private static final List<Class<? extends Model>> clazzList = new ArrayList<>();

    static {
        clazzList.add(BaseModel.class);
        clazzList.add(AntForestV2.class);
        clazzList.add(AntForestPatrol.class);
        clazzList.add(AntFarm.class);
        clazzList.add(AntStall.class);
        clazzList.add(AntOrchard.class);
        clazzList.add(KBOrchard.class);
        clazzList.add(ProtectEcology.class);
        clazzList.add(AntDodo.class);
        clazzList.add(AntOcean.class);
        clazzList.add(AntSports.class);
        clazzList.add(AntMember.class);
        clazzList.add(YouthPrivilege.class);
        clazzList.add(GreenFinance.class);
        clazzList.add(goldenbeans.class);
        clazzList.add(AnswerAI.class);

        ExtensionsHandle.handleAlphaRequest("ModelOrder", "addExtensionsClass", clazzList);
    }
}