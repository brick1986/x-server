package io.github.brick.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterSlotTest {

    @Test
    void crc16MatchesXmodemCheckValue() {
        // CRC-16/XMODEM 的标准校验值：123456789 → 0x31C3
        assertEquals(0x31C3, ClusterSlot.crc16("123456789"));
    }

    @Test
    void slotOfKnownKeyMatchesRedisClusterSpecExample() {
        // Redis Cluster 文档示例：无花括号的 key 整串参与计算，123456789 的 slot 是 12739
        assertEquals(12739, ClusterSlot.slot("123456789"));
    }

    @Test
    void effectiveTagExtractsFirstBracePair() {
        assertEquals("user1000", ClusterSlot.effectiveTag("{user1000}.following"));
        assertEquals("user1000", ClusterSlot.effectiveTag("{user1000}.followers"));
        assertEquals("bar", ClusterSlot.effectiveTag("foo{bar}{zap}"));
        assertEquals("{bar", ClusterSlot.effectiveTag("foo{{bar}}zap"));
    }

    @Test
    void effectiveTagFallsBackToWholeKey() {
        // 无花括号、未闭合、或 tag 为空 → 整串
        assertEquals("player:1:profile", ClusterSlot.effectiveTag("player:1:profile"));
        assertEquals("foo{}{bar}", ClusterSlot.effectiveTag("foo{}{bar}"));
        assertEquals("foo{bar", ClusterSlot.effectiveTag("foo{bar"));
    }
}
