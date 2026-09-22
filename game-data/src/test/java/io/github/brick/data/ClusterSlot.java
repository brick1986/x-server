package io.github.brick.data;

import java.nio.charset.StandardCharsets;

/**
 * Redis Cluster slot 算法的 Java 复刻，纯测试用（设计 §8）。
 * 同槽断言（同桶的数据 key 与账本集合键同 slot）靠它在不连集群的情况下验证。
 */
public final class ClusterSlot {

    private static final int POLY = 0x1021;    // CRC-16/XMODEM

    private ClusterSlot() {
    }

    /** Redis 规则：CRC16(有效子串) mod 16384。 */
    public static int slot(String key) {
        return crc16(effectiveTag(key)) % 16384;
    }

    /**
     * hash tag 规则（Redis 官方）：取首个左花括号与其后首个右花括号之间的子串；
     * 无花括号、无闭合、或子串为空，整串参与计算。
     */
    public static String effectiveTag(String key) {
        int start = key.indexOf('{');
        if (start < 0) {
            return key;
        }
        int end = key.indexOf('}', start + 1);
        if (end < 0 || end == start + 1) {
            return key;
        }
        return key.substring(start + 1, end);
    }

    public static int crc16(String s) {
        int crc = 0;
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ POLY : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
