package io.github.brick.data.idgen;

/**
 * id 位型与 counter 命名（协议常量，发号 spec §3）。位型一次性钉死：
 * {@code [符号位][15 位恒 0][16 位服号][32 位流水]}，id = {@code (serverId << 32) | seq}。
 * 改位型 = 全量数据重写，与 {@code DataKeys.BUCKETS} 同性质。
 *
 * <p>15 位恒 0 是刻意的：数字短——1 亿角色 × 每人 5 个数据 key，id 段每多 1 字节就是
 * GB 级 Redis 内存。占满的对比：流水扩到 47 位，1 服首号 = 2⁴⁷+1 = 140737488355329
 * （15 位数字），换来的容量永远用不到（发号 spec §3）。
 */
public final class IdScheme {

    /** 服号上限：16 位 = 65536 个服（发号 spec §3）。 */
    public static final int SERVER_ID_MAX = 65535;

    /** 流水上限：32 位 = 42.9 亿/服。达到即抛——实际到不了，防御性校验（发号 spec §3）。 */
    public static final long SEQ_MAX = 0xFFFFFFFFL;

    /** counter 保留字：服号身份文档（boot，spec §5.2）不走实体 counter 通道。 */
    private static final String RESERVED = "boot";

    private IdScheme() {
    }

    /** 拼装：id = (serverId << 32) | seq。serverId ∈ [1,65535]、seq ∈ [1,SEQ_MAX]。 */
    public static long compose(int serverId, long seq) {
        checkServerId(serverId);
        if (seq < 1 || seq > SEQ_MAX) {
            throw new IllegalArgumentException("seq 越界 [1," + SEQ_MAX + "]: " + seq);
        }
        return ((long) serverId << 32) | seq;
    }

    /** 拆出服号（高 16 位，符号位外）。 */
    public static int serverIdOf(long id) {
        return (int) (id >>> 32);
    }

    /** 拆出流水（低 32 位）。 */
    public static long seqOf(long id) {
        return id & 0xFFFFFFFFL;
    }

    /**
     * counter 文档 _id：{@code idgen:{serverId}:{name}}。name 非空、不含 {@code :}
     * （防拼名歧义）、不为保留字 {@code boot}（身份文档与实体 counter 同居 counters
     * 集合，保留字防撞名——发号 spec §4.1/§5）。
     */
    public static String counterName(int serverId, String name) {
        checkServerId(serverId);
        if (name == null || name.isEmpty() || name.indexOf(':') >= 0 || RESERVED.equals(name)) {
            throw new IllegalArgumentException(
                    "非法 name（非空、不含 ':'、非保留字 '" + RESERVED + "'）: " + name);
        }
        return "idgen:" + serverId + ":" + name;
    }

    /** 服号身份文档 _id：{@code idgen:{serverId}:boot}（发号 spec §5.2）。 */
    public static String bootName(int serverId) {
        checkServerId(serverId);
        return "idgen:" + serverId + ":boot";
    }

    private static void checkServerId(int serverId) {
        if (serverId < 1 || serverId > SERVER_ID_MAX) {
            throw new IllegalArgumentException("serverId 越界 [1," + SERVER_ID_MAX + "]: " + serverId);
        }
    }
}
