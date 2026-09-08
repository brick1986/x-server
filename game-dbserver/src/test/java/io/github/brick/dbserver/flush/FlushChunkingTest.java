package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlushChunkingTest {

    @Test
    void emptyInputYieldsNoChunks() {
        assertThat(FlushOrchestrator.chunks(Set.of(), 500)).isEmpty();
    }

    @Test
    void exactMultipleSplitsEvenly() {
        assertThat(FlushOrchestrator.chunks(List.of("a", "b", "c", "d"), 2))
                .containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void remainderGoesIntoAShorterLastChunk() {
        assertThat(FlushOrchestrator.chunks(List.of("a", "b", "c"), 2))
                .containsExactly(List.of("a", "b"), List.of("c"));
    }

    @Test
    void chunkLargerThanInputYieldsSingleChunk() {
        assertThat(FlushOrchestrator.chunks(List.of("a"), 500))
                .containsExactly(List.of("a"));
    }

    @Test
    void everyKeyAppearsExactlyOnceAcrossChunks() {
        List<String> keys = java.util.stream.IntStream.range(0, 1200)
                .mapToObj(i -> "player:" + i + ":profile").toList();
        assertThat(FlushOrchestrator.chunks(keys, 500))
                .hasSize(3)
                .flatExtracting(c -> c)
                .containsExactlyElementsOf(keys);
    }
}
