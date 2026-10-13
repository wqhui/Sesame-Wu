package com.surexu.sesame.util.idMap;

import com.fasterxml.jackson.core.type.TypeReference;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.JsonUtil;
import com.surexu.sesame.util.Log;

/**
 * 芝麻炼金「自动跳过」列表：{@code templateId -> 任务标题}。
 *
 * <p>与静态预置集（{@code AntMember.ALCHENY_BLACKLIST_TEMPLATE_IDS}）并列生效，区别是这里由运行时
 * 按**服务端错误码**判定后写入并落盘——服务端固定拒绝的任务（如需真实游戏内事件，
 * 返回 {@code ILLEGAL_ARGUMENT / promiseActivityExtCheck}）只会在首次遇到时试一次，
 * 之后跨启动都不再重试。
 *
 * <p><b>按 templateId 存，不按标题</b>：这类任务标题是「玩寻道大千」式的动态文案，
 * 按标题存会随活动改版失效，而 templateId 是服务端稳定的任务标识。
 *
 * <p>参照 Sesame-AG 的 {@code TaskBlacklist.autoAddToBlacklist}（DataStore 持久化）。
 */
public class AlchemyAutoBlacklistMap {

    private static final Map<String, String> idMap = new ConcurrentHashMap<>();

    private static final Map<String, String> readOnlyIdMap = Collections.unmodifiableMap(idMap);

    public static Map<String, String> getMap() {
        return readOnlyIdMap;
    }

    public static String get(String key) {
        return idMap.get(key);
    }

    public static boolean contains(String key) {
        return key != null && idMap.containsKey(key);
    }

    public synchronized static void add(String key, String value) {
        idMap.put(key, value);
    }

    public synchronized static void remove(String key) {
        idMap.remove(key);
    }

    public synchronized static void load() {
        idMap.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getAlchemyAutoBlacklistMapFile());
            if (!body.isEmpty()) {
                Map<String, String> newMap = JsonUtil.parseObject(body, new TypeReference<Map<String, String>>() {
                });
                idMap.putAll(newMap);
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }

    public synchronized static boolean save() {
        return FileUtil.write2File(JsonUtil.toJsonString(idMap), FileUtil.getAlchemyAutoBlacklistMapFile());
    }

    public synchronized static void clear() {
        idMap.clear();
    }

}
