package io.github.brick.data.idgen;

/**
 * 发号原语（发号 spec §5）：无爹实体（player/account/guild/全服邮件…）的 id 来源。
 * {@code name} 由业务侧传——{@code player}/{@code guild} 属业务词汇，game-data 不认识
 * 业务实体（primitives §6，同 {@code EntityPriorities} 的边界思路）。
 *
 * <p>发号不依赖 Redis 实体锁：号段原子性由 Mongo 账本保证，{@code next} 锁内锁外皆可
 * 调用（创角口径：锁外拿号 → lockAll → 锁内写，发号 spec §6）。
 */
public interface IdGenerator {

    /** 发一个 id。同 name 进程内严格递增、进程间趋势递增（发号 spec §4.2）。 */
    long next(String name);

    /**
     * 批量租号段 [start, start+count)，返回 start。绕过本地段直租账本，全服邮件用
     * （发号 spec §4.2）；lease 与本地段互不干扰：本地段未发完的剩余照常由 {@code next}
     * 发出，账本只增，两个区间天然不重叠。
     */
    long lease(String name, int count);
}
