package com.sesame.wu.util.idMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sesame.wu.util.XHelpers;
import lombok.Getter;

import com.sesame.wu.entity.UserEntity;
import com.sesame.wu.hook.ApplicationHook;
import com.sesame.wu.util.AccountSession;
import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.JsonUtil;
import com.sesame.wu.util.Log;

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
    
    /**
     * 设置当前账号，并同步推进账号会话（sessionEpoch）。
     *
     * <p>这是整个「账号会话隔离」的唯一入口：切号时先 {@link AccountSession#beginSwitch()}
     * 让在途任务手里的快照全部失效，再更新 uid，最后 {@link AccountSession#apply} 应用新会话。
     * 顺序不能反——先改 uid 的话，中间会有一小段「旧任务仍被判为有效但 uid 已变」的窗口。
     *
     * <p>本方法由 UI 线程 / 初始化流程调用，所以这里只做内存操作，
     * 绝不 sleep、不等待、不 interrupt 任务线程（会 ANR）。
     */
    public synchronized static void setCurrentUserId(String userId) {
        if (userId == null || userId.isEmpty()) {
            // 登出：会话直接作废。activeUid 置 null 后，所有旧任务的写入和请求都会被拦下，
            // 避免它们落到「下一个登录的账号」上。
            long e = AccountSession.beginSwitch();
            currentUid = null;
            AccountSession.apply(null, e);
            return;
        }
        if (userId.equals(currentUid) && !AccountSession.isSwitching()) {
            // 同账号且不在切换中：不做无谓的 epoch 递增。
            // 否则每次 Activity onResume 都会把在途任务的快照打废，任务被大面积跳过。
            return;
        }
        long epoch = AccountSession.beginSwitch();   // ① 在途任务快照立刻失效
        currentUid = userId;                          // ② 立即切换（不阻塞！）
        AccountSession.apply(userId, epoch);          // ③ 应用新会话
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
