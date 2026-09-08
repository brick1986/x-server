package io.github.brick.dbserver.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 落盘进程运行时配置（落盘 spec §7）。
 *
 * <p>刻意**不放进 {@code game-data} 的 {@code DataProperties}**：落盘节奏是本进程的策略，
 * 而 {@code game-data} 是被 {@code game-web} 与本进程共用的库模块——把落盘参数塞进去，
 * {@code game-web} 会凭空多出一堆对它毫无意义的配置项。
 *
 * <p>与 {@code DataProperties} 同规格：{@link Min}/{@link Max} 在启动期强制范围。
 * 「合理区间」若只写在注释里，一行越界的 yml 就能让它失守——越界值应当使上下文启动失败，
 * 而不是留到线上以一个荒谬的落盘周期运行。
 */
@ConfigurationProperties(prefix = "game.dbserver")
@Validated
public class DbServerProperties {

    /**
     * 落盘轮次间隔（架构 §4.3 钉定 1~3s）。上限放到 60s 容许排障时临时调慢；
     * 下限 500ms——再快只是空转扫 dirty，往返开销大于收益。
     */
    @Min(500) @Max(60_000)
    private long flushIntervalMillis = 2000L;

    /**
     * 单片 key 数。内存峰值与单次往返量只与它有关、与积压总量无关（落盘 spec §3）。
     * 上限 5000：再大则单次 MGET 回包与一批 Document 的堆占用开始显著。
     */
    @Min(1) @Max(5_000)
    private int chunkSize = 500;

    /**
     * 落盘锁租约（秒，落盘 spec §5）。固定租约、无看门狗（并发修订 §4.1）。
     * 下限 10s：短于此，稍大的一片就可能在落盘中途失锁而白跑一轮。
     */
    @Min(10) @Max(600)
    private long lockLeaseSeconds = 60L;

    /**
     * 停机刷盘硬超时（秒，落盘 spec §6）。默认 20s，留在 Spring 默认 30s 关闭窗口内。
     * 超时不代表丢数据——残留仍在 dirty，下次启动接着落。
     */
    @Min(1) @Max(120)
    private long shutdownTimeoutSeconds = 20L;

    public long getFlushIntervalMillis() { return flushIntervalMillis; }
    public void setFlushIntervalMillis(long v) { this.flushIntervalMillis = v; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int v) { this.chunkSize = v; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long v) { this.lockLeaseSeconds = v; }
    public long getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
    public void setShutdownTimeoutSeconds(long v) { this.shutdownTimeoutSeconds = v; }
}
