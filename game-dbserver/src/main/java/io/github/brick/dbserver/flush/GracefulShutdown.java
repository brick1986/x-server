package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * 停机刷盘（落盘 spec §6）：关定时入口 → 等当前轮 → 循环刷到空 → 硬超时放行。
 *
 * <p><b>为什么不是「全量刷入」</b>：架构 §4.3 原文是「正常停服时 dirty 数据全量刷入 MongoDB」，
 * 但 dbserver 单独停机时 {@code game-web} 仍在运行并持续 {@code SADD dirty}——刷空一次，
 * 下一毫秒又有新的。真实语义只能是「尽力刷 + 有界超时」。配套的运维约定是
 * <b>先停 game-web、再停 game-dbserver</b>（见 DEVELOPMENT.md），否则这里刷的是持续注水的池子。
 *
 * <p><b>超时不等于丢数据</b>：残留仍在 dirty（或 in-flight，下轮合回），下次启动自然接着落。
 * 后果是「数据暂时只在 Redis」，而 Redis 有 AOF everysec 兜底。
 *
 * <p><b>为什么用 {@link SmartLifecycle} 而非 {@code @PreDestroy}</b>：Spring 关闭时先按 phase
 * 降序执行 {@code Lifecycle.stop()}，之后才销毁 singleton bean。{@code @PreDestroy} 相对其他 bean
 * 的销毁顺序不够可靠——{@code RedissonClient}（{@code destroyMethod="shutdown"}）或
 * {@code MongoClient}（{@code destroyMethod="close"}）可能已被关掉，刷盘就无连接可用。
 * 取 {@link Integer#MAX_VALUE} 作 phase 确保最先 {@code stop()}。
 */
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    /** 两轮之间的间歇。抢不到 Redis 锁时 flushBlocking 会立刻返回，没有它就是个烧 CPU 的紧循环。 */
    private static final long PAUSE_MILLIS = 200L;

    private final FlushScheduler scheduler;
    private final DirtyLedger dirty;
    private final long timeoutSeconds;

    private volatile boolean running = false;

    public GracefulShutdown(FlushScheduler scheduler, DirtyLedger dirty, long timeoutSeconds) {
        this.scheduler = scheduler;
        this.dirty = dirty;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 最大 phase：最先 stop()，抢在 Redisson/Mongo 的 destroyMethod 之前。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void start() {
        running = true;
    }

    /** SmartLifecycle 只在此返回 true 时才调 {@link #stop()}——忘了置位，停机刷盘会静默不执行。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
        // 先关定时入口：否则停机循环与定时轮次会互相抢 Redis 锁，把停机拖长
        scheduler.stopAccepting();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        boolean interruptedExit = false;
        while (System.nanoTime() < deadline) {
            if (flushOneRound() == 0) {
                log.info("停机刷盘完成，dirty 已排空");
                return;
            }
            // INTERRUPTED（抢不到锁 / 中途失锁）与抛异常的轮次（flushOneRound 已吞）都走这里
            // 继续重试到超时：另一实例正在刷同一份 dirty，超时后放行退出、残留由对方或下次启动落完
            if (!pause()) {
                interruptedExit = true;
                break;
            }
        }
        // 中断提前退出时 pause() 已记 WARN，这里不能冒充超时；带残留数的 ERROR 只在真超时发。
        // 残留必须 dirty 与 in-flight 一起报：最后一轮死在片中间（MongoException）时剩余 key
        // 全在 in-flight、dirty 恰为空——只报 dirty 会在最该知道还有多少货的时刻说出「0 个」。
        if (!interruptedExit) {
            log.error("停机刷盘超时（{}s），dirty 仍有 {} 个、in-flight 仍有 {} 个 key 未落盘；数据仍在 Redis，下次启动后会接着落",
                    timeoutSeconds,
                    dirty == null ? -1 : dirty.backlogSize(),
                    dirty == null ? -1 : dirty.inflightMembers().size());
        }
    }

    /**
     * 单轮停机刷盘调用，带异常护栏。{@link FlushScheduler#flushBlocking()} 透传
     * {@link FlushOrchestrator#flushOnce()} 的返回值，也会透传它抛的 {@code MongoException}
     * （连接级故障）。定时路径的 {@code tick()} 为此 catch 了 RuntimeException（「一次 Mongo
     * 抖动不该让定时轮次此后再也不跑」）——停机循环同理：抛了就当本轮没干成事，返回
     * {@link FlushOrchestrator#INTERRUPTED} 继续重试到硬超时，绝不中止循环。
     */
    private int flushOneRound() {
        try {
            return scheduler.flushBlocking();
        } catch (RuntimeException e) {
            // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
            log.error("停机刷盘轮次异常结束，继续重试到超时；剩余 key 留在 in-flight 待下轮恢复", e);
            return FlushOrchestrator.INTERRUPTED;
        }
    }

    /** @return false 表示被中断，应立即结束刷盘循环 */
    private boolean pause() {
        try {
            Thread.sleep(PAUSE_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("停机刷盘被中断，放弃剩余轮次");
            return false;
        }
    }
}
