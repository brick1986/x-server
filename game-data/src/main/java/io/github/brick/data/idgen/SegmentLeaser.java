package io.github.brick.data.idgen;

/**
 * 账本租段接口（发号 spec §4）。实现负责原子性：并发 {@code lease} 必然返回不重叠的段。
 */
public interface SegmentLeaser {

    /**
     * counter 记账 {@code seq += count}，返回新 seq（段末位）。counter 不存在时 upsert，
     * 新 seq = count，即首段 = [1, count]——流水从 1 起（发号 spec §3）。
     */
    long lease(String counterName, int count);
}
