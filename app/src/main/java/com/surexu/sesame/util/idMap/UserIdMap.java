package com.surexu.sesame.util.idMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.surexu.sesame.util.XHelpers;
import lombok.Getter;

import com.surexu.sesame.entity.UserEntity;
import com.surexu.sesame.hook.ApplicationHook;
import com.surexu.sesame.util.FileUtil;
import com.surexu.sesame.util.JsonUtil;
import com.surexu.sesame.util.Log;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class UserIdMap {
    
    private static final Map<String, UserEntity> userMap = new ConcurrentHashMap<>();
    
    private static final Map<String, UserEntity> readOnlyUserMap = Collections.unmodifiableMap(userMap);
    
    @Getter
    private static String currentUid = null;
    
    public static Map<String, UserEntity> getUserMap() {
        return readOnlyUserMap;
    }
    
    public static Set<String> getUserIdSet() {
        return userMap.keySet();
    }
    
    public static Collection<UserEntity> getUserEntityCollection() {
        return userMap.values();
    }
    
    public synchronized static void initUser(String currentUserId) {
        setCurrentUserId(currentUserId);
        ApplicationHook.getMainHandler().post(() -> {
            ClassLoader loader;
            try {
                loader = ApplicationHook.getClassLoader();
            } catch (Exception e) {
                Log.i("Error getting classloader");
                return;
            }
            try {
                UserIdMap.unload();
                String selfId = ApplicationHook.getUserId();
                Class<?> clsUserIndependentCache = loader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.UserIndependentCache");
                Class<?> clsAliAccountDaoOp = loader.loadClass("com.alipay.mobile.socialcommonsdk.bizdata.contact.data.AliAccountDaoOp");
                Object aliAccountDaoOp = XHelpers.callStaticMethod(clsUserIndependentCache, "getCacheObj", clsAliAccountDaoOp);
                List<?> allFriends = (List<?>) XHelpers.callMethod(aliAccountDaoOp, "getAllFriends", new Object[0]);
                if (!allFriends.isEmpty()) {
                    Class<?> friendClass = allFriends.get(0).getClass();
                    Field userIdField = XHelpers.findField(friendClass, "userId");
                    Field accountField = XHelpers.findField(friendClass, "account");
                    Field nameField = XHelpers.findField(friendClass, "name");
                    Field nickNameField = XHelpers.findField(friendClass, "nickName");
                    Field remarkNameField = XHelpers.findField(friendClass, "remarkName");
                    Field friendStatusField = XHelpers.findField(friendClass, "friendStatus");
                    UserEntity selfEntity = null;
                    for (Object userObject : allFriends) {
                        try {
                            String userId = (String) userIdField.get(userObject);
                            String account = (String) accountField.get(userObject);
                            String name = (String) nameField.get(userObject);
                            String nickName = (String) nickNameField.get(userObject);
                            String remarkName = (String) remarkNameField.get(userObject);
                            Integer friendStatus = (Integer) friendStatusField.get(userObject);
                            UserEntity userEntity = new UserEntity(userId, account, friendStatus, name, nickName, remarkName);
                            if (Objects.equals(selfId, userId)) {
                                selfEntity = userEntity;
                            }
                            UserIdMap.add(userEntity);
                        } catch (Throwable t) {
                            Log.i("addUserObject err:");
                            Log.printStackTrace(t);
                        }
                    }
                    UserIdMap.saveSelf(selfEntity);
                }
                UserIdMap.save(selfId);
            } catch (Throwable t) {
                Log.i("checkUnknownId.run err:");
                Log.printStackTrace(t);
            }
        });
    }
    
    public synchronized static void setCurrentUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            currentUid = null;
            return;
        }
        currentUid = userId;
    }
    
    public static String getCurrentMaskName() {
        return getMaskName(currentUid);
    }
    
    public static String getMaskName(String userId) {
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return null;
        }
        return userEntity.getMaskName();
    }
    public static String getShowName(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "未知用户";
        }
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return userId; // 返回用户ID作为默认值
        }
        return userEntity.getShowName();
    }
    public static String getFullName(String userId) {
        UserEntity userEntity = userMap.get(userId);
        if (userEntity == null) {
            return null;
        }
        return userEntity.getFullName();
    }
    
    public static UserEntity get(String userId) {
        return userMap.get(userId);
    }
    
    public synchronized static void add(UserEntity userEntity) {
        String userId = userEntity.getUserId();
        if (userId == null || userId.isEmpty()) {
            return;
        }
        userMap.put(userId, userEntity);
    }
    
    public synchronized static void remove(String userId) {
        userMap.remove(userId);
    }
    
    public synchronized static void load(String userId) {
        userMap.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getFriendIdMapFile(userId));
            if (!body.isEmpty()) {
                Map<String, UserEntity.UserDto> dtoMap = JsonUtil.parseObject(body, new TypeReference<Map<String, UserEntity.UserDto>>() {
                });
                for (UserEntity.UserDto dto : dtoMap.values()) {
                    userMap.put(dto.getUserId(), dto.toEntity());
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
    
    public synchronized static void unload() {
        userMap.clear();
    }
    
    public synchronized static boolean save(String userId) {
        return FileUtil.write2File(JsonUtil.toJsonString(userMap), FileUtil.getFriendIdMapFile(userId));
    }
    
    public synchronized static void loadSelf(String userId) {
        userMap.clear();
        try {
            String body = FileUtil.readFromFile(FileUtil.getSelfIdFile(userId));
            if (!body.isEmpty()) {
                UserEntity.UserDto dto = JsonUtil.parseObject(body, new TypeReference<UserEntity.UserDto>() {
                });
                userMap.put(dto.getUserId(), dto.toEntity());
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
    }
    
    public synchronized static boolean saveSelf(UserEntity userEntity) {
        return FileUtil.write2File(JsonUtil.toJsonString(userEntity), FileUtil.getSelfIdFile(userEntity.getUserId()));
    }

    /**
     * 取账号的展示名（备注名优先，其次昵称）—— 只返回名称本身，不带账号/手机号。
     * <p>典型用途是导出文件名：{@code [小明]-config_v2.json}。
     * <p>直接读该账号的 self.json，<b>不动全局 userMap</b> ——
     * {@link #loadSelf(String)} 会先 {@code userMap.clear()} 且只装入一个账号，
     * 在导出这类场景调用它会把别的账号挤掉，导致取到 null 而退回 UID。
     * <p>取不到名称时退回 {@code userId}；{@code userId} 也为空时返回「默认」。
     */
    public static String getDisplayName(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return "默认";
        }
        try {
            String body = FileUtil.readFromFile(FileUtil.getSelfIdFile(userId));
            if (!body.isEmpty()) {
                UserEntity.UserDto dto = JsonUtil.parseObject(body, new TypeReference<UserEntity.UserDto>() {
                });
                UserEntity entity = dto == null ? null : dto.toEntity();
                if (entity != null) {
                    String name = entity.getShowName();
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return userId;
    }

}
