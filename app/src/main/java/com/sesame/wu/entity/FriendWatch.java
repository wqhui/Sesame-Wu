package com.sesame.wu.entity;

import org.json.JSONException;
import org.json.JSONObject;
import com.sesame.wu.util.*;
import com.sesame.wu.util.idMap.UserIdMap;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

/**
 * @author Constanline
 * @since 2023/08/08
 */
public class FriendWatch extends IdAndName {

    private static final String TAG = FriendWatch.class.getSimpleName();

    private static JSONObject joFriendWatch;

    /** 本次加载是否解析失败：内存是空对象时不得落盘，否则历史统计被抹成 {} */
    private static volatile boolean loadFailed;

    private String startTime;

    private int allGet;

    private int weekGet;

    public FriendWatch(String id, String name) {
        this.id = id;
        this.name = name;
    }

    @Override
    public int compareTo(IdAndName o) {
        FriendWatch another = (FriendWatch) o;
        if (this.weekGet > another.weekGet) {
            return -1;
        } else if (this.weekGet < another.weekGet) {
            return 1;
        }
        return super.compareTo(o);
    }

    /**
     * 记录一个好友本次被收取的能量。
     * <p>调用方是最多 16 条并行收取线程，而 {@link #joFriendWatch} 是单个共享 JSONObject：
     * 这里的「取-判-建-改」必须整体原子，否则并发首次写入会互相覆盖，或与
     * {@link #updateDay()} 的遍历撞成 ConcurrentModificationException。
     */
    public static synchronized void friendWatch(String id, int collectedEnergy) {
        if (joFriendWatch == null) {
            // 已 unload 或尚未 load：静默丢弃这一次统计，不在空表上继续累加
            return;
        }
        try {
            JSONObject joSingle = joFriendWatch.optJSONObject(id);
            if (joSingle == null) {
                joSingle = new JSONObject();
                joSingle.put("name", UserIdMap.getMaskName(id));
                joSingle.put("allGet", 0);
                joSingle.put("startTime", TimeUtil.getDateStr());
                joFriendWatch.put(id, joSingle);
            }
            joSingle.put("weekGet", joSingle.optInt("weekGet", 0) + collectedEnergy);
        } catch (Throwable th) {
            Log.i(TAG, "friendWatch err:");
            Log.printStackTrace(TAG, th);
        }
    }

    public static synchronized void save() {
        if (loadFailed) {
            // 内存是空对象：写盘会把历史统计抹成 {}
            Log.debug(TAG + ", 好友统计加载失败，跳过保存");
            return;
        }
        if (joFriendWatch == null) {
            // unload 之后又触发保存：没有可写的内容，直接跳过而不是抛 NPE
            return;
        }
        try {
            FileUtil.write2File(joFriendWatch.toString(), FileUtil.getFriendWatchFile());
        } catch (Exception e){
            Log.i(TAG, "friendWatch save err:");
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 跨周结算：把各好友的 weekGet 累加进 allGet 并清零。
     * <p>与 {@link #friendWatch} 共用同一把锁——一边遍历 keys() 一边被收取线程 put 新好友，
     * 会抛 ConcurrentModificationException 并让本次结算半途而废。
     */
    public static synchronized void updateDay() {
        if (loadFailed || joFriendWatch == null) {
            return;
        }
        if (!needUpdateAll(FileUtil.getFriendWatchFile().lastModified())) {
            return;
        }
        JSONObject joSingle;
        try {
            String dateStr = TimeUtil.getDateStr();
            Iterator<String> ids = joFriendWatch.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                joSingle = joFriendWatch.getJSONObject(id);
                joSingle.put("name", joSingle.optString("name"));
                joSingle.put("allGet", joSingle.optInt("allGet", 0) + joSingle.optInt("weekGet", 0));
                joSingle.put("weekGet", 0);
                if (!joSingle.has("startTime")) {
                    joSingle.put("startTime", dateStr);
                }
                joFriendWatch.put(id, joSingle);
            }
            FileUtil.write2File(joFriendWatch.toString(), FileUtil.getFriendWatchFile());
        } catch (Throwable th) {
            Log.i(TAG, "friendWatchNewWeek err:");
            Log.printStackTrace(TAG, th);
        }
    }

    public static synchronized Boolean load() {
        loadFailed = false;
        try {
            String strFriendWatch = FileUtil.readFromFile(FileUtil.getFriendWatchFile());
            if (!strFriendWatch.isEmpty()) {
                joFriendWatch = new JSONObject(strFriendWatch);
            } else {
                joFriendWatch = new JSONObject();
            }
            return true;
        } catch (JSONException e) {
            Log.printStackTrace(e);
            // 不写盘：空对象落盘就是历史统计全丢
            loadFailed = true;
            Log.record("好友统计解析失败，本次使用空统计（不写盘，原文件保留）");
            joFriendWatch = new JSONObject();
        }
        return false;
    }

    public static synchronized void unload() {
        joFriendWatch = new JSONObject();
    }

    public static boolean needUpdateAll(long last) {
        if (last == 0L) {
            return true;
        }
        Calendar cLast = Calendar.getInstance();
        cLast.setTimeInMillis(last);
        Calendar cNow = Calendar.getInstance();
        if (cLast.get(Calendar.DAY_OF_YEAR) == cNow.get(Calendar.DAY_OF_YEAR)) {
            return false;
        }
        return cNow.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY;
    }

    public static List<FriendWatch> getList() {
        ArrayList<FriendWatch> list = new ArrayList<>();
        String strFriendWatch = FileUtil.readFromFile(FileUtil.getFriendWatchFile());
        try {
            JSONObject joFriendWatch;
            if (StringUtil.isEmpty(strFriendWatch)) {
                joFriendWatch = new JSONObject();
            } else {
                joFriendWatch = new JSONObject(strFriendWatch);
            }
            Iterator<String> ids = joFriendWatch.keys();
            while (ids.hasNext()) {
                String id = ids.next();
                JSONObject friend = joFriendWatch.optJSONObject(id);
                if (friend == null) {
                    friend = new JSONObject();
                }
                String name = friend.optString("name");
                FriendWatch friendWatch = new FriendWatch(id, name);
                friendWatch.startTime = friend.optString("startTime", "无");
                friendWatch.weekGet = friend.optInt("weekGet", 0);
                friendWatch.allGet = friend.optInt("allGet", 0) + friendWatch.weekGet;
                String showText = name + "(开始统计时间:" + friendWatch.startTime + ")\n\n";
                showText = showText + "周收:" + friendWatch.weekGet + " 总收:" + friendWatch.allGet;
                friendWatch.name = showText;
                list.add(friendWatch);
            }
        } catch (Throwable t) {
            Log.i(TAG, "FriendWatch getList: ");
            Log.printStackTrace(TAG, t);
            // 只跳过本次读取：覆写成 {} 会把历史统计抹掉
        }
        return list;
    }
}