package com.sesame.wu.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 账号会话隔离：用一个单调递增的 {@code sessionEpoch} + {@code switching} 标志，
 * 给每个任务线程绑定它「开始执行时」的 (uid, epoch) 快照，
 * 从而在切号的瞬间判定「哪些任务已经属于旧账号」。
 *
 * <p><b>为什么需要它</b>：支付宝切号时，旧账号的任务线程可能还在跑，
 * 它后续的 RPC 会打到新账号、算出来的结果会写进新账号的 status.json / config_v2.json，
 * 也就是俗称的「串号」。仅凭 uid 字符串比较无法识别这种情况——
 * 同一个 uid 可能被重复登录，而旧任务看到的 uid 一直没变。
 * 用 epoch 是因为它每次切号都会 +1：旧任务持有的 epoch 一定小于当前 epoch，
 * 即使切回了同一个账号，旧快照也依然失效。
 *
 * <p><b>线程模型</b>：所有状态字段都是 volatile / AtomicLong，写方法用 synchronized，
 * 读方法不加锁（读到的组合最多让快照「失效」，绝不会让旧快照「有效」），
 * 因此不会在 UI 线程上产生阻塞，也就不会 ANR。
 */
public final class AccountSession {

    /**
     * 任务执行前拍的会话快照：记录「这个任务属于哪个账号的哪一代会话」。
     * 字段不可变，任务线程可以安全地跨线程持有。
     */
    public static final class Snapshot {

        public final String uid;

        public final long epoch;

        public Snapshot(String uid, long epoch) {
            this.uid = uid;
            this.epoch = epoch;
        }
    }

    /** 全局递增的会话代数：每次切号 +1，用来让旧任务手里的快照一次性全部失效 */
    private static final AtomicLong EPOCH = new AtomicLong(0);

    /** 当前生效的账号 uid（登出时为 null） */
    private static volatile String activeUid = null;

    /** 当前生效的会话代数，与 activeUid 成对更新 */
    private static volatile long activeEpoch = 0;

    /**
     * 切号进行中标志：beginSwitch() 置 true、apply() 置 false。
     * 处于该窗口期内时 isValid() 一律返回 false——
     * 因为此时 activeUid 还是旧值、新的 uid 已经在 UserIdMap 里生效，
     * 任何基于 uid 的判断都不可靠，索性全部拦下。
     */
    private static volatile boolean switching = false;

    /**
     * 任务线程绑定自己的快照（非任务线程不绑）。
     * 用 ThreadLocal 而不是全局表，是因为任务线程池里的线程会被复用，
     * 全局表需要在 finally 里按线程 id 清理，容易漏删造成内存泄漏和误拦。
     */
    private static final ThreadLocal<Snapshot> TASK_BOUND = new ThreadLocal<>();

    private AccountSession() {
    }

    /**
     * 取当前 (activeUid, activeEpoch) 作为快照。
     *
     * <p>注意：这里刻意不加锁。与 {@link #apply(String, long)} 并发时，
     * 可能读到「新 uid + 旧 epoch」这样的错位组合，导致后续 isValid() 返回 false，
     * 结果是该次任务被跳过（安全的方向），而不会出现旧任务被误判为有效的危险情况。
     */
    public static Snapshot snapshot() {
        return new Snapshot(activeUid, activeEpoch);
    }

    /**
     * 把快照绑定到当前线程，表示「本线程正在替这个会话执行任务」。
     * 绑定之后 {@link #isCurrentThreadAllowed()} 就会按快照校验，
     * 而不是无条件放行。
     */
    public static void bindCurrentThread(Snapshot s) {
        if (s == null) {
            TASK_BOUND.remove();
            return;
        }
        TASK_BOUND.set(s);
    }

    /**
     * 解除绑定。必须放在 finally 里调用：
     * 任务线程池的线程会被复用，不清理会让下一个复用该线程的非任务逻辑被误拦。
     */
    /**
     * 取当前线程**已绑定**的快照；没绑定（非任务线程）返回 null。
     *
     * <p>子任务要继承**父任务**的快照，而不是用 {@link #snapshot()} 现取一份新的——
     * 子任务可能在切号之后才被调度，现取会拿到新 epoch，等于把旧任务"洗白"成新会话。
     */
    public static Snapshot currentBoundSnapshot() {
        return TASK_BOUND.get();
    }

    public static void unbindCurrentThread() {
        TASK_BOUND.remove();
    }

    /**
     * 当前线程是否允许继续发请求。
     *
     * <p>非任务线程（没有绑定快照）返回 true 放行——UI、初始化流程不属于任何账号任务；
     * 任务线程则要求它手里的快照仍然有效，否则说明它属于旧账号，请求需要被拦截。
     */
    public static boolean isCurrentThreadAllowed() {
        Snapshot s = TASK_BOUND.get();
        if (s == null) {
            return true;
        }
        return isValid(s);
    }

    /**
     * 切号开始：epoch+1、switching=true，在途任务手里的快照立刻全部失效。
     *
     * <p>必须先让旧快照失效、再改 UserIdMap 里的 uid：顺序反过来的话，
     * 中间会存在一小段「uid 已是新账号、旧快照仍被判为有效」的窗口，
     * 旧任务的写入就会落到新账号上。
     *
     * <p>本方法只做内存操作，不 sleep、不等待、不 interrupt——
     * 它的调用方跑在 UI 线程，任何阻塞都会 ANR。
     *
     * @return 新的会话代数，调用方需要在切换完成后原样传给 {@link #apply(String, long)}
     */
    public static synchronized long beginSwitch() {
        long epoch = EPOCH.incrementAndGet();
        // 刻意不更新 activeEpoch：让它和旧快照的 epoch 错开，旧快照自然失效
        switching = true;
        return epoch;
    }

    /**
     * 切号完成：设置 activeUid / activeEpoch 并解除 switching。
     *
     * <p>{@code uid} 允许为 null（登出场景）：此时 activeUid=null、activeEpoch=epoch，
     * 所有已绑定的快照同样失效，旧任务无法再写入或发请求。
     */
    public static synchronized void apply(String uid, long epoch) {
        activeUid = uid;
        activeEpoch = epoch;
        switching = false;
    }

    /**
     * 快照是否仍然有效（切换中一律 false）。
     *
     * <p>同时比较 uid 和 epoch：
     * 只比 uid 会漏掉「切走又切回同一个账号」的情况，只比 epoch 又无法拦住登出后的空账号。
     */
    public static boolean isValid(Snapshot s) {
        return !switching
                && s != null
                && s.uid != null
                && s.uid.equals(activeUid)
                && s.epoch == activeEpoch;
    }

    /**
     * 某次写入是否属于当前账号。
     *
     * <p>{@code uid} 为 null 时放行：这类写入与账号无关（如默认配置、全局日志），
     * 不应该被账号切换误伤；空字符串 {@code ""} 不是合法 uid，按「不属于当前账号」处理。
     */
    public static boolean isUidCurrent(String uid) {
        if (uid == null) {
            return true;
        }
        return uid.equals(activeUid);
    }

    public static String getActiveUid() {
        return activeUid;
    }

    public static long getActiveEpoch() {
        return activeEpoch;
    }

    public static boolean isSwitching() {
        return switching;
    }
}
