package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest {

    /** 可编排返回序列的假调度器：不碰 Redis/Mongo。 */
    private static class ScriptedScheduler extends FlushScheduler {
        final AtomicInteger rounds = new AtomicInteger();
        private final int[] results;
        boolean stopAcceptingCalled = false;

        ScriptedScheduler(int... results) {
            super(new FlushOrchestrator(null, null, null, null, 500, 60L));
            this.results = results;
        }

        @Override
        public int flushBlocking() {
            int i = rounds.getAndIncrement();
            return i < results.length ? results[i] : 0;
        }

        @Override
        public void stopAccepting() {
            stopAcceptingCalled = true;
        }
    }

    private static GracefulShutdown shutdown(ScriptedScheduler s, long timeoutSeconds) {
        GracefulShutdown g = new GracefulShutdown(s, null, timeoutSeconds);
        g.start();
        return g;
    }

    @Test
    void phaseIsMaxSoItStopsBeforeConnectionsAreDestroyed() {
        // Spring 关闭时先按 phase 降序执行 Lifecycle.stop()，之后才销毁 singleton；
        // 取最大 phase 确保刷盘跑在 RedissonClient.shutdown()/MongoClient.close() 之前。
        assertThat(shutdown(new ScriptedScheduler(), 20L).getPhase()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void startMakesItRunningSoSpringWillCallStop() {
        // SmartLifecycle.stop() 只在 isRunning() 为真时被调用——忘了置位，停机刷盘就静默不执行
        ScriptedScheduler s = new ScriptedScheduler();
        GracefulShutdown g = new GracefulShutdown(s, null, 20L);
        assertThat(g.isRunning()).isFalse();
        g.start();
        assertThat(g.isRunning()).isTrue();
    }

    @Test
    void stopFlushesUntilDrainComesBackEmpty() {
        ScriptedScheduler s = new ScriptedScheduler(5, 3, 0);
        GracefulShutdown g = shutdown(s, 20L);
        g.stop();
        assertThat(s.rounds).hasValue(3);          // 5 → 3 → 0，收到 0 即收敛
        assertThat(s.stopAcceptingCalled).isTrue();
        assertThat(g.isRunning()).isFalse();
    }

    @Test
    void stopClosesTheSchedulerEntranceBeforeFlushing() {
        // 顺序要求：先关定时入口，否则停机循环与定时轮次会互相抢 Redis 锁、拖长停机
        ScriptedScheduler s = new ScriptedScheduler(0) {
            @Override
            public int flushBlocking() {
                assertThat(stopAcceptingCalled).isTrue();
                return super.flushBlocking();
            }
        };
        shutdown(s, 20L).stop();
        assertThat(s.rounds).hasValue(1);
    }

    @Test
    void stopGivesUpAtTheDeadlineInsteadOfHangingForever() {
        // dbserver 单独停机时 game-web 仍在 SADD dirty，「刷到空」可能永远达不到；
        // 超时必须放行退出——残留仍在 dirty，下次启动接着落，不是丢数据。
        ScriptedScheduler neverClean = new ScriptedScheduler() {
            @Override
            public int flushBlocking() {
                rounds.incrementAndGet();
                return 1;                          // 永远还有货
            }
        };
        GracefulShutdown g = shutdown(neverClean, 1L);
        long start = System.nanoTime();
        g.stop();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isBetween(900L, 5_000L);   // 大约 1s 后放弃
        assertThat(neverClean.rounds.get()).isPositive();
    }

    @Test
    void stopKeepsRetryingWhenRoundsReportInterrupted() {
        // INTERRUPTED 不能被当成「刷完了」——那样另一实例持锁时停机会立刻放弃
        ScriptedScheduler s = new ScriptedScheduler(
                FlushOrchestrator.INTERRUPTED, FlushOrchestrator.INTERRUPTED, 0);
        shutdown(s, 20L).stop();
        assertThat(s.rounds).hasValue(3);
    }
}
