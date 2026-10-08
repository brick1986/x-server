package io.github.brick.data.idgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdSchemeTest {

    @Test
    void composeRoundTripsAndMatchesSpecExamples() {
        // 发号 spec §3 的两个例子数字直接进测试
        assertThat(IdScheme.compose(1, 1)).isEqualTo(4294967297L);
        assertThat(IdScheme.compose(3, 587)).isEqualTo(12884902475L);
        long id = IdScheme.compose(12345, 987654321L);
        assertThat(IdScheme.serverIdOf(id)).isEqualTo(12345);
        assertThat(IdScheme.seqOf(id)).isEqualTo(987654321L);
    }

    @Test
    void top16BitsStayZeroSoIdsStayShort() {
        // 15 位恒 0 是刻意的（spec §3）：最大合法 id 是 48 位正数，不会把数字撑长
        long id = IdScheme.compose(65535, 0xFFFFFFFFL);
        assertThat(id).isEqualTo(281474976710655L);   // 0xFFFFFFFFFFFF
        assertThat(id).isPositive();
    }

    @Test
    void rejectsServerIdOutOfRange() {
        assertThatThrownBy(() -> IdScheme.compose(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.compose(65536, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSeqOutOfRange() {
        assertThatThrownBy(() -> IdScheme.compose(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.compose(1, 0x100000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterNameFollowsSpecFormat() {
        assertThat(IdScheme.counterName(1, "player")).isEqualTo("idgen:1:player");
        assertThat(IdScheme.bootName(1)).isEqualTo("idgen:1:boot");
    }

    @Test
    void rejectsBadCounterNames() {
        assertThatThrownBy(() -> IdScheme.counterName(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.counterName(1, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.counterName(1, "a:b"))
                .isInstanceOf(IllegalArgumentException.class);
        // 保留字：业务实体名叫 boot 会撞服号身份文档（与 bootName 同串）
        assertThatThrownBy(() -> IdScheme.counterName(1, "boot"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boot");
    }
}
