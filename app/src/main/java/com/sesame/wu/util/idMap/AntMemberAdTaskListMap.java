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

/**
 * 会员<b>广告</b>任务的任务名映射。
 *
 * <p>与 {@link AntMemberTaskListMap} 分开存一份文件：两个下拉框各显示自己的候选。
 * 原先两个字段共用同一份映射，只要一方登记/保存，另一方的候选就被整份覆盖，
 * 打开「会员广告任务 | 黑名单列表」看到的是会员任务的候选，选不中真正的广告任务。
 *
 * <p>保存语义与 {@link AntMemberTaskListMap} 一致：未 load 过就先与磁盘合并，避免把另一个
 * 进程写入的条目抹掉。
 */
public class AntMemberAdTaskListMap {

    private static final Map<String, String> idMap = new ConcurrentHashMap<>();

    private static final Map<String, String> readOnlyIdMap = Collections.unmodifiableMap(idMap);

    /** 内存是否已与磁盘同步过：只 add 不 load 时内存为空，覆盖写会抹掉另一进程写入的条目 */
    private static boolean loaded;

    /** 本次同步后被移除的键：从磁盘合并时不能把它们补回来 */
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
            String body = FileUtil.readFromFile(FileUtil.getAntMemberAdTaskListMapFile());
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

    /** 整份覆盖写回；未同步过时先合并磁盘条目，同名键以内存新值为准 */
    public synchronized static boolean save() {
        if (!loaded) {
            mergeFromDisk();
        }
        return FileUtil.write2File(JsonUtil.toJsonString(idMap), FileUtil.getAntMemberAdTaskListMapFile());
    }

    /** 把磁盘上内存没有的条目补进来；本次被 remove 的键跳过，避免删除被撤销 */
    private static void mergeFromDisk() {
        loaded = true;
        try {
            String body = FileUtil.readFromFile(FileUtil.getAntMemberAdTaskListMapFile());
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
