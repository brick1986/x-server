package io.github.brick.dbserver.flush;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 定时触发落盘轮次，并保证**进程内**串行（落盘 spec §6.1 的内层互斥）。
 *
 * <p>两层互斥合起来才完整：<ul>
 *   <li><b>进程内</b>——本类的 {@link ReentrantLock}：定时轮次与停机刷盘不重叠；</li>
 *   <li><b>跨进程</b>——{@link FlushOrchestrator#FLUSH_LOCK}：多实例不重叠。</li></ul>
 *
 * <p>为什么进程内还要单独一把锁：停机路径**不能靠「抢不到 Redis 锁就跳过」**——那正是要刷盘的
 * 时候，跳过就刷不成了。它必须阻塞等当前轮真正结束，故需要一个可阻塞等待的进程内闩。
 *
 * <p>{@code @Scheduled} 的 {@code initialDelayString} 与 {@code fixedDelayString} 取同一个配置：
 * 首轮延后一个间隔而非上下文就绪即刻触发——避免与启动过程抢资源，也让不连外部服务的
 * 上下文测试（把间隔调大即可）不被打扰。
 */
public class FlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    private final FlushOrchestrator orchestrator;

    /** 进程内串行闩。定时轮次用 tryLock（抢不到就跳过），停机刷盘用 lock（真等）。 */
    private final ReentrantLock gate = new ReentrantLock();

    private volatile boolean accepting = true;

    public FlushScheduler(FlushOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(
            initialDelayString = "${game.dbserver.flush-interval-millis:2000}",
            fixedDelayString = "${game.dbserver.flush-interval-millis:2000}")
    public void tick() {
        if (!accepting) {
            return;
        }
        if (!gate.tryLock()) {
            log.debug("上一轮落盘仍在进行，本次触发跳过");
            return;
        }
        try {
            orchestrator.flushOnce();
        } catch (RuntimeException e) {
            // 吞掉并记日志：一次 Mongo 抖动不该让定时轮次此后再也不跑。
            // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
            log.error("落盘轮次异常结束，剩余 key 留在 in-flight 待下轮恢复", e);
        } finally {
            gate.unlock();
        }
    }

    /**
     * 停机路径专用：**阻塞**等当前轮跑完，再自己跑一轮。
     *
     * @return {@link FlushOrchestrator#flushOnce()} 的原值，不做任何包装——停机循环靠它区分
     *         「真刷空」（{@code 0}）与「没干成事」（{@link FlushOrchestrator#INTERRUPTED}）
     */
    public int flushBlocking() {
        gate.lock();
        try {
            return orchestrator.flushOnce();
        } finally {
            gate.unlock();
        }
    }

    /** 关掉定时轮次入口。**不影响 {@link #flushBlocking()}**——那是停机自己要用的。 */
    public void stopAccepting() {
        accepting = false;
    }
}
