package io.github.brick.data.codec;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    public record Sample(String name, int level, List<String> tags) {}

    private final JsonCodec codec = new JsonCodec();

    @Test
    void encodeDecodeRoundTrip() {
        Sample s = new Sample("alice", 7, List.of("a", "b"));
        String json = codec.encode(s);
        assertTrue(json.contains("\"name\":\"alice\""));
        assertTrue(json.contains("\"level\":7"));
        assertEquals(s, codec.decode(json, Sample.class));
    }

    @Test
    void encodeNullProducesNullJson() {
        assertEquals("null", codec.encode(null));
        assertNull(codec.decode("null", Sample.class));
    }

    @Test
    void decodeBadJsonThrows() {
        assertThrows(RuntimeException.class, () -> codec.decode("{not json", Sample.class));
    }
}
