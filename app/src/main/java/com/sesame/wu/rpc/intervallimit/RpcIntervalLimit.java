package com.sesame.wu.rpc.intervallimit;

import com.sesame.wu.model.normal.base.BaseModel;
import com.sesame.wu.util.Log;
import com.sesame.wu.util.RandomUtil;
import com.sesame.wu.util.TimeUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RpcIntervalLimit {

    /**
     * 全局默认间隔：每次取值都从配置里读，改配置即时生效（无需重启）。
     *
     * <p>原来是写死的 {@code new DefaultIntervalLimit(50)}——50ms 太短，多模块并发时
     * 请求几乎同一瞬间发出，直接触发服务端限流（13 / 46 / 48「当前网络不可用」）。
     * 改成可配的区间后，用户能按自己开了多少模块去调：
     * 只开森林 100~300ms 够，全开建议 300~800ms。
     */
    private static final IntervalLimit defaultIntervalLimit = new IntervalLimit() {
        private volatile long time = 0L;

        @Override
        public Integer getInterval() {
            int min = BaseModel.getGlobalRpcIntervalMin();
            int max = BaseModel.getGlobalRpcIntervalMax();
            if (min < 0) {
                min = 0;
            }
            if (max < min) {
                max = min;
            }
            return max == min ? min : RandomUtil.nextInt(min, max + 1);
        }

        @Override
        public Long getTime() {
            return time;
        }

        @Override
        public void setTime(Long time) {
            this.time = time == null ? 0L : time;
        }
    };

    private static final Map<String, IntervalLimit> intervalLimitMap = new ConcurrentHashMap<>();

    public static synchronized void addIntervalLimit(String method, Integer interval) {
        addIntervalLimit(method, new DefaultIntervalLimit(interval));
    }

    public static synchronized void addIntervalLimit(String method, IntervalLimit intervalLimit) {
        if (intervalLimitMap.containsKey(method)) {
            Log.i("方法：" + method + " 间隔限制已存在");
            throw new IllegalArgumentException("方法：" + method + " 间隔限制已存在");
        }
        intervalLimitMap.put(method, intervalLimit);
    }

    public static synchronized void updateIntervalLimit(String method, Integer interval) {
        updateIntervalLimit(method, new DefaultIntervalLimit(interval));
    }

    public static synchronized void updateIntervalLimit(String method, IntervalLimit intervalLimit) {
        intervalLimitMap.put(method, intervalLimit);
    }

    public static void enterIntervalLimit(String method) {
        IntervalLimit intervalLimit = intervalLimitMap.get(method);
        if (intervalLimit == null) {
            intervalLimit = defaultIntervalLimit;
        }
        synchronized (intervalLimit) {
            long sleep = intervalLimit.getInterval() - System.currentTimeMillis() + intervalLimit.getTime();
            if (sleep > 0) {
                TimeUtil.sleep(sleep);
            }
            intervalLimit.setTime(System.currentTimeMillis());
        }
    }

    public static synchronized void clearIntervalLimit() {
        intervalLimitMap.clear();
    }

}
