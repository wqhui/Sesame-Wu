package com.sesame.wu.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sesame.wu.util.Log;
import com.sesame.wu.util.idMap.AntMemberTaskListMap;
import com.sesame.wu.util.idMap.ForestHuntIdMap;
import com.sesame.wu.util.idMap.MemberCreditSesameTaskListMap;

public class AlipayAntMemberTaskList extends IdAndName {
    private static List<AlipayAntMemberTaskList> list;

    public AlipayAntMemberTaskList(String i, String n) {
        id = i;
        name = n;
    }

    public static List<AlipayAntMemberTaskList> getList() {
        if (list == null) {
            list = new ArrayList<>();
            for (Map.Entry<String, String> entry : AntMemberTaskListMap.getMap().entrySet()) {
                list.add(new AlipayAntMemberTaskList(entry.getKey(), entry.getValue()));
            }
        }
        return list;
    }

    /**
     * 清掉静态缓存，下次 {@link #getList()} 重新从映射构建。
     * <p>候选列表是进程内存态：配置进程先 getList() 建出一份空表、随后才 load 映射时，
     * 不清理就会一直显示空候选，必须由载入方显式清一次。
     */
    public static void clear() {
        list = null;
    }

    public static void remove(String id) {
        getList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
    }

}
