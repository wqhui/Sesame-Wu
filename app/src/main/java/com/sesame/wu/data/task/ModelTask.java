package com.sesame.wu.data.task;

import static com.sesame.wu.model.normal.base.BaseModel.taskRpcRequest;

import android.os.Build;

import com.sesame.wu.util.AccountSession;
import com.sesame.wu.util.FileUtil;
import com.sesame.wu.util.Status;
import com.sesame.wu.util.idMap.UserIdMap;
import lombok.Getter;
import com.sesame.wu.data.Model;
import com.sesame.wu.data.ModelFields;
import com.sesame.wu.data.ModelType;
import com.sesame.wu.model.normal.base.BaseModel;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.StringUtil;
import com.sesame.wu.data.RuntimeInfo;
import com.sesame.wu.util.TimeUtil;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public abstract class ModelTask extends Model {

    private static final String TAG = ModelTask.class.getSimpleName();

    private static final Map<ModelTask, Thread> MAIN_TASK_MAP = new ConcurrentHashMap<>();

    private static final Object TASK_PAUSE_LOCK = new Object();

    private static final ThreadPoolExecutor MAIN_THREAD_POOL = new ThreadPoolExecutor(getModelArray().length, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());

    private final Map<String, ChildModelTask> childTaskMap = new ConcurrentHashMap<>();

    /** 连续执行失败计数（内存，进程重启清零），达到阈值后自动挂起本任务 */
    private int consecutiveFailures = 0;

    private ChildTaskExecutor childTaskExecutor;

    @Getter
    private final Runnable mainRunnable = new Runnable() {

        private final ModelTask task = ModelTask.this;

        @Override
        public void run() {
            if (MAIN_TASK_MAP.get(task) != null) {
                return;
            }
            MAIN_TASK_MAP.put(task, Thread.currentThread());
            // 执行前拍下会话快照并绑定到本线程：
            // 之后本线程内的每一次 RPC 都会校验它，一旦账号切换就拦下请求
            AccountSession.Snapshot snapshot = AccountSession.snapshot();
            AccountSession.bindCurrentThread(snapshot);
            try {
                // 排队期间账号可能已经切走，此时这轮任务属于旧账号，必须整轮跳过，
                // 否则结果会写到新账号上
                if (!AccountSession.isValid(snapshot)) {
                    Log.i(TAG, "账号会话已切换，跳过本次执行");
                    return;
                }
                task.run();
                consecutiveFailures = 0;
            } catch (Exception e) {
                Log.printStackTrace(e);
                onTaskRunFailure();
            } finally {
                // 线程池会复用线程，不清理会让下一个复用该线程的逻辑被误判为旧任务
                AccountSession.unbindCurrentThread();
                MAIN_TASK_MAP.remove(task);
            }
        }

    };

    public ModelTask() {
    }

    @Override
    public final void prepare() {
        childTaskExecutor = newTimedTaskExecutor();
    }

    public String getId() {
        return toString();
    }

    public ModelType getType() {
        return ModelType.TASK;
    }

    public abstract String getName();

    public abstract ModelFields getFields();

    public abstract Boolean check();

    public Boolean isSync() {
        return false;
    }

    public abstract void run();

    public Boolean hasChildTask(String childId) {
        return childTaskMap.containsKey(childId);
    }

    public ChildModelTask getChildTask(String childId) {
        return childTaskMap.get(childId);
    }

    public Boolean addChildTask(ChildModelTask childTask) {
        String childId = childTask.getId();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return childTask == childTaskMap.compute(childId, (key, value) -> {
                if (value != null) {
                    value.cancel();
                }
                childTask.modelTask = this;
                if (childTaskExecutor.addChildTask(childTask)) {
                    return childTask;
                }
                return null;
            });
        } else {
            synchronized (childTaskMap) {
                ChildModelTask oldTask = childTaskMap.get(childId);
                if (oldTask != null) {
                    oldTask.cancel();
                }
                childTask.modelTask = this;
                if (childTaskExecutor.addChildTask(childTask)) {
                    childTaskMap.put(childId, childTask);
                    return true;
                }
                return false;
            }
        }
    }

    public void removeChildTask(String childId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            childTaskMap.compute(childId, (key, value) -> {
                if (value != null) {
                    childTaskExecutor.removeChildTask(value);
                }
                return null;
            });
        } else {
            synchronized (childTaskMap) {
                ChildModelTask childTask = childTaskMap.get(childId);
                if (childTask != null) {
                    childTaskExecutor.removeChildTask(childTask);
                }
                childTaskMap.remove(childId);
            }
        }
    }

    public Integer countChildTask() {
        return childTaskMap.size();
    }

    public Boolean startTask() {
        return startTask(false);
    }

    public synchronized Boolean startTask(Boolean force) {
        if (MAIN_TASK_MAP.containsKey(this)) {
            if (!force) {
                return false;
            }
            stopTask();
        }
        try {
            if (isEnable() && check()) {
                String pausedMsg = getPausedMessage();
                if (pausedMsg != null) {
                    Log.record(pausedMsg);
                    return false;
                }
                if (isSync()) {
                    mainRunnable.run();
                } else {
                    MAIN_THREAD_POOL.execute(mainRunnable);
                }
                return true;
            }
        } catch (Exception e) {
            Log.printStackTrace(e);
        }
        return false;
    }

    public synchronized void stopTask() {
        for (ChildModelTask childModelTask : childTaskMap.values()) {
            try {
                childModelTask.cancel();
            } catch (Exception e) {
                Log.printStackTrace(e);
            }
        }
        if (childTaskExecutor != null) {
            childTaskExecutor.clearAllChildTask();
        }
        childTaskMap.clear();
        MAIN_THREAD_POOL.remove(mainRunnable);
        MAIN_TASK_MAP.remove(this);
    }

    public static void startAllTask() {
        startAllTask(false);
    }

    public static void startAllTask(Boolean force) {
        //自动触发备份配置文件
        if (!Status.hasFlagToday("Config::backup")) {
            FileUtil.backupConfigV2WithRolling(UserIdMap.getCurrentUid());
            Status.flagToday("Config::backup");
        }
        //执行BaseModel中自定义执行请求
        taskRpcRequest();
        for (Model model : getModelArray()) {
            if (model != null) {
                if (ModelType.TASK == model.getType()) {
                    if (((ModelTask) model).startTask(force)) {
                        try {
                            Thread.sleep(750);
                        } catch (InterruptedException e) {
                            Log.printStackTrace(e);
                        }
                    }
                }
            }
        }
    }

    public static void stopAllTask() {
        for (Model model : getModelArray()) {
            if (model != null) {
                try {
                    if (ModelType.TASK == model.getType()) {
                        ((ModelTask) model).stopTask();
                    }
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }
    }

    private ChildTaskExecutor newTimedTaskExecutor() {
        ChildTaskExecutor childTaskExecutor;
        Integer timedTaskModel = BaseModel.getTimedTaskModel().getValue();
        if (timedTaskModel == BaseModel.TimedTaskModel.SYSTEM) {
            childTaskExecutor = new SystemChildTaskExecutor();
        } else if (timedTaskModel == BaseModel.TimedTaskModel.PROGRAM) {
            childTaskExecutor = new ProgramChildTaskExecutor();
        } else {
            throw new RuntimeException("not found childTaskExecutor");
        }
        return childTaskExecutor;
    }

    /** 任务级异常暂停：写入持久化暂停表（按用户隔离） */
    protected void pauseSelfUntil(long untilMs) {
        String name = getName();
        synchronized (TASK_PAUSE_LOCK) {
            RuntimeInfo runtimeInfo = RuntimeInfo.getInstance();
            JSONObject jo;
            try {
                String raw = runtimeInfo.getString(RuntimeInfo.RuntimeInfoKey.TaskPauseMap.name());
                jo = new JSONObject(raw);
            } catch (JSONException e) {
                jo = new JSONObject();
            }
            try {
                jo.put(name, untilMs);
            } catch (JSONException ignored) {
            }
            runtimeInfo.put(RuntimeInfo.RuntimeInfoKey.TaskPauseMap.name(), jo.toString());
        }
    }

    /** 连续执行失败达到阈值后自动挂起本任务，避免反复异常空转 */
    private void onTaskRunFailure() {
        String name = getName();
        consecutiveFailures++;
        int threshold = BaseModel.getExceptionPauseThreshold().getValue() != null
                ? BaseModel.getExceptionPauseThreshold().getValue() : 0;
        Integer waitVal = BaseModel.getWaitWhenException().getValue();
        long waitMs = waitVal != null ? waitVal.longValue() : 0L;
        if (threshold >= 1 && consecutiveFailures >= threshold && waitMs > 0) {
            long until = System.currentTimeMillis() + waitMs;
            pauseSelfUntil(until);
            Log.record("「" + name + "」连续失败 " + consecutiveFailures + " 次，已自动挂起至 " + TimeUtil.getCommonDate(until));
            consecutiveFailures = 0;
        }
    }

    /** 读取未过期的任务级异常暂停表（任务名 → 恢复时间），顺手移除已过期项 */
    public static Map<String, Long> activeTaskPauseMap() {
        RuntimeInfo runtimeInfo = RuntimeInfo.getInstance();
        synchronized (TASK_PAUSE_LOCK) {
            String raw = runtimeInfo.getString(RuntimeInfo.RuntimeInfoKey.TaskPauseMap.name());
            if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
            JSONObject jo;
            try {
                jo = new JSONObject(raw);
            } catch (JSONException e) {
                return new LinkedHashMap<>();
            }
            long now = System.currentTimeMillis();
            Map<String, Long> active = new LinkedHashMap<>();
            boolean expired = false;
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long until = jo.optLong(key, 0L);
                if (until > now) {
                    active.put(key, until);
                } else {
                    expired = true;
                }
            }
            if (expired) {
                try {
                    runtimeInfo.put(RuntimeInfo.RuntimeInfoKey.TaskPauseMap.name(), new JSONObject(active).toString());
                } catch (Exception ignored) {
                }
            }
            return active;
        }
    }

    private String getPausedMessage() {
        Map<String, Long> pausedMap = activeTaskPauseMap();
        Long until = pausedMap.get(getName());
        if (until != null) {
            return "⏸ 异常暂停中，恢复时间 " + TimeUtil.getCommonDate(until) + "，暂不执行检测！";
        }
        return null;
    }

    public static class ChildModelTask implements Runnable {

        @Getter
        private ModelTask modelTask;

        @Getter
        private final String id;

        @Getter
        private final String group;

        private final Runnable runnable;

        @Getter
        private final Long execTime;

        private CancelTask cancelTask;

        @Getter
        private Boolean isCancel = false;

        /** 父任务执行时绑定的会话快照；子任务执行时用它判断账号是否已经切走。 */
        private final AccountSession.Snapshot ownerSnapshot = AccountSession.currentBoundSnapshot();

        public ChildModelTask() {
            this(null, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id) {
            this(id, null, () -> {
            }, 0L);
        }

        public ChildModelTask(String id, String group) {
            this(id, group, () -> {
            }, 0L);
        }

        protected ChildModelTask(String id, long execTime) {
            this(id, null, null, execTime);
        }

        /*protected ChildModelTask(String id, String group, Long time) {
            this(id, group, null, time);
        }*/

        public ChildModelTask(String id, Runnable runnable) {
            this(id, null, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable) {
            this(id, group, runnable, 0L);
        }

        public ChildModelTask(String id, String group, Runnable runnable, Long execTime) {
            if (StringUtil.isEmpty(id)) {
                id = toString();
            }
            if (StringUtil.isEmpty(group)) {
                group = "DEFAULT";
            }
            if (runnable == null) {
                runnable = setRunnable();
            }
            this.id = id;
            this.group = group;
            this.runnable = runnable;
            this.execTime = execTime;
        }

        public Runnable setRunnable() {
            return null;
        }

        public final void run() {
            // 子任务跑在 executor 的线程池里，不是父任务线程，所以要把父任务的会话快照带过来，
            // 否则子任务发起的 RPC 不会被切号拦截（父任务线程的 ThreadLocal 已经失效了）。
            AccountSession.Snapshot snap = ownerSnapshot;
            AccountSession.bindCurrentThread(snap);
            try {
                if (snap != null && !AccountSession.isValid(snap)) {
                    Log.i("ChildModelTask", "账号会话已切换，跳过子任务[" + id + "]");
                    return;
                }
                runnable.run();
            } finally {
                AccountSession.unbindCurrentThread();
            }
        }

        protected void setCancelTask(CancelTask cancelTask) {
            this.cancelTask = cancelTask;
        }

        public final void cancel() {
            if (cancelTask != null) {
                try {
                    cancelTask.cancel();
                    isCancel = true;
                } catch (Exception e) {
                    Log.printStackTrace(e);
                }
            }
        }

    }

    public interface CancelTask {

        void cancel();

    }

}
