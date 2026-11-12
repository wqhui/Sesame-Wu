package com.sesame.wu.util.idMap;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.JsonUtil;
import com.sesame.wu.util.Log;

public class AntMemberTaskListMap {

    private static final Map<String, String> idMap = new ConcurrentHashMap<>();

    private static final Map<String, String> readOnlyIdMap = Collections.unmodifiableMap(idMap);

    /**
     * 内存是否已与磁盘同步过。
     * <p>调用点大多只 add/remove 不 load，此时内存是空表；直接整份覆盖写会把另一个进程
     * （配置页进程）写入的任务名全部抹掉，所以保存前必须先与磁盘合并一次。
     */
    private static boolean loaded;

    /** 本次同步后被移除的键：从磁盘合并时不能把它们补回来，否则删除会被撤销 */
    private static final Set<String> removedKeys = new HashSet<>();

    public static Map<String, String> getMap() {
        return readOnlyIdMap;
    }

    public static String get(String key) {
        return idMap.get(key);
    }

    public synchronized static void add(String key, String value) {
        removedKeys.remove(key);
        idMap.put(key, value);
    }

    public synchronized static void remove(String key) {
        removedKeys.add(key);
        idMap.remove(key);
    }

    /** 先清空再整份载入；文件不存在或内容为空时保持为空，解析失败只记栈不抛 */
    public synchronized static void load() {
        idMap.clear();
        removedKeys.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getAntMemberTaskListMapFile());
            if (!body.isEmpty()) {
                Map<String, String> newMap = JsonUtil.parseObject(body, new TypeReference<Map<String, String>>() {
                });
                idMap.putAll(newMap);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        loaded = true;
    }

    /**
     * 整份覆盖写回。
     * <p>未同步过（只 save 没 load）时先把磁盘已有条目补进内存再写，
     * 否则一次保存就把另一个进程写进去的任务名整份抹掉；同名键以内存中的新值为准。
     */
    public synchronized static boolean save() {
        if (!loaded) {
            mergeFromDisk();
        }
        return FileUtil.write2File(JsonUtil.toJsonString(idMap), FileUtil.getAntMemberTaskListMapFile());
    }

    /** 把磁盘上内存没有的条目补进来；本次被 remove 的键跳过，避免删除被撤销 */
    private static void mergeFromDisk() {
        loaded = true;
        try {
            String body = FileUtil.readFromFile(FileUtil.getAntMemberTaskListMapFile());
            if (body.isEmpty()) {
                return;
            }
            Map<String, String> onDisk = JsonUtil.parseObject(body, new TypeReference<Map<String, String>>() {
            });
            for (Map.Entry<String, String> entry : onDisk.entrySet()) {
                if (!removedKeys.contains(entry.getKey())) {
                    idMap.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    /** 显式清空后内存即为权威状态，后续 save 不再从磁盘补回旧条目 */
    public synchronized static void clear() {
        idMap.clear();
        removedKeys.clear();
        loaded = true;
    }

}
