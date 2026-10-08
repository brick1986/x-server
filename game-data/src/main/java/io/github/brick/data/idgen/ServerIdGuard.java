package io.github.brick.data.idgen;

/**
 * 服号自检（发号 spec §5.2）：本库已存在其他服号的 boot 标记 = 配置漂移，fail-fast。
 * 幂等：同服号重复调用无副作用（多进程同服并发首号时各自 upsert 同一文档）。
 */
public interface ServerIdGuard {

    void checkAndMark(int serverId);
}
