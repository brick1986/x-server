package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DirtyLedgerIT extends LocalRedisMongo {

    @Test
    void markAndMembers() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        assertThat(d.members()).containsExactlyInAnyOrder("player:1:profile", "player:2:bag");
    }

    @Test
    void removeDropsMember() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        d.remove("player:1:profile");
        assertThat(d.members()).containsExactly("player:2:bag");
    }

    @Test
    void removeAllDropsAll() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("guild:7:fund");
        d.removeAll(Set.of("player:1:profile", "guild:7:fund"));
        assertThat(d.members()).isEmpty();
    }

    @Test
    void membersStoresRawStringNotQuotedJson() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        assertThat(d.members()).first().isEqualTo("player:1:profile");
    }
}
