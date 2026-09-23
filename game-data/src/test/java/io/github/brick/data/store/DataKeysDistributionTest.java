package io.github.brick.data.store;

import io.github.brick.data.ClusterSlot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataKeysDistributionTest {

    private static final int SAMPLES = 100_000;

    @Test
    void bucketSpreadSurvivesSequentialBlockAndSnowflakeIds() {
        // 三种 id 生成形态都不许把数据偏到少数桶（设计 §3.2/§8.3）
        assertSpread(i -> i);                            // 顺序 id
        assertSpread(i -> 10_000_000_000L + i * 4096L);  // 块状分配：低 12 位恒定（恒 1024）
        assertSpread(i -> i << 22);                      // snowflake 型：低位全 0、高位变化
    }

    private interface IdGen {
        long at(long i);
    }

    private void assertSpread(IdGen gen) {
        long[] counts = new long[DataKeys.BUCKETS];
        for (long i = 0; i < SAMPLES; i++) {
            counts[DataKeys.bucket("player", gen.at(i))]++;
        }
        double mean = SAMPLES / (double) DataKeys.BUCKETS;    // ≈ 24.4
        double chi2 = 0;
        long max = 0;
        for (long c : counts) {
            chi2 += (c - mean) * (c - mean) / mean;
            max = Math.max(max, c);
        }
        // 均匀散列的 χ² 期望 ≈ 桶数（自由度 K-1，标准差 ≈ 90）；坏散列大好几个数量级。
        // 2K 是宽到不会误伤好散列、又必然抓住坏散列的界。
        assertThat(chi2).isLessThan(2.0 * DataKeys.BUCKETS);
        // 好散列的 max ≈ 1.8×mean；3× 留余量
        assertThat(max).isLessThanOrEqualTo((long) (3.0 * mean));
    }

    @Test
    void bucketTagsMapToDistinctSlotsEvenlyEnough() {
        // tag→slot 视作随机放置：4096 个 tag 去重后 slot 数 ≥ 3600（生日碰撞打 ~12% 折扣）
        Set<Integer> slots = new HashSet<>();
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            slots.add(ClusterSlot.slot(String.format("{b%04d}", b)));
        }
        assertThat(slots.size()).isGreaterThanOrEqualTo(3600);

        // 16 节点、每节点连续 1024 个槽（redis-cli --cluster 的默认分法）的失衡 ≤ 12%（期望 ~6.6%）
        int[] perNode = new int[16];
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            perNode[ClusterSlot.slot(String.format("{b%04d}", b)) / 1024]++;
        }
        double expected = slots.size() / 16.0;
        int max = 0;
        for (int n : perNode) {
            max = Math.max(max, n);
        }
        assertThat(max).isLessThanOrEqualTo((int) Math.ceil(expected * 1.12));
    }
}
