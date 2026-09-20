package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单测：用一个计数的 FlushOrchestrator 子类替掉真实落盘，只验调度门控逻辑，不连 Redis/Mongo。
 */
class FlushSchedulerTest {

    /** 计数用的假编排器：不碰 Redis/Mongo，构造参数全传 null 也不会被解引用。 */
    private static class CountingOrchestrator extends FlushOrchestrator {
        final AtomicInteger calls = new AtomicInteger();
        int result = 0;

        CountingOrchestrator() {
            super(null, null, null, null, 500, 60L, null);
        }

        @Override
        public int flushOnce() {
            calls.incrementAndGet();
            return result;
        }
    }

    @Test
    void tickRunsARound() {
        CountingOrchestrator o = new CountingOrchestrator();
        new FlushScheduler(o).tick();
        assertThat(o.calls).hasValue(1);
    }

    @Test
    void tickIsANoOpAfterStopAccepting() {
        // 停机时必须立刻停止起新轮，否则停机循环与定时轮次会互相抢 Redis 锁、拖长停机
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        s.stopAccepting();
        s.tick();
        assertThat(o.calls).hasValue(0);
    }

    @Test
    void flushBlockingStillRunsAfterStopAccepting() {
        // 关键：stopAccepting 只关定时入口，不能把停机自己的刷盘也关掉
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        s.stopAccepting();
        assertThat(s.flushBlocking()).isZero();
        assertThat(o.calls).hasValue(1);
    }

    @Test
    void flushBlockingPassesThroughTheReturnValue() {
        // 停机循环靠返回值区分「真刷空」与「没干成事」，不能被包装掉
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        o.result = 7;
        assertThat(s.flushBlocking()).isEqualTo(7);
        o.result = FlushOrchestrator.INTERRUPTED;
        assertThat(s.flushBlocking()).isEqualTo(FlushOrchestrator.INTERRUPTED);
    }

    @Test
    void tickSwallowsExceptionsSoTheScheduleSurvives() {
        // 一次 Mongo 抖动不该让定时轮次此后再也不跑
        FlushOrchestrator boom = new FlushOrchestrator(null, null, null, null, 500, 60L, null) {
            @Override
            public int flushOnce() {
                throw new IllegalStateException("mongo 抖了");
            }
        };
        FlushScheduler s = new FlushScheduler(boom);
        s.tick();       // 不抛出即通过
        s.tick();
    }

    @Test
    void concurrentTickDoesNotOverlapWithARunningRound() throws Exception {
        // 进程内串行（spec §6.1 内层）：一轮在跑时，另一个触发必须跳过而不是并发进去
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        FlushOrchestrator slow = new FlushOrchestrator(null, null, null, null, 500, 60L, null) {
            @Override
            public int flushOnce() {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return 1;
            }
        };
        FlushScheduler s = new FlushScheduler(slow);

        Thread first = new Thread(s::tick);
        first.start();
        inside.await();          // 确认第一轮已进到 flushOnce 内部
        s.tick();                // 第二次触发：应当直接跳过
        release.countDown();
        first.join();

        assertThat(maxConcurrent).hasValue(1);
    }
}
