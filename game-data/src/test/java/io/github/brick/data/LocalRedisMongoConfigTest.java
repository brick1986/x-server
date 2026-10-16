package io.github.brick.data;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LocalRedisMongoConfigTest {
    @Test void rejectsNonDisposableTargetsBeforeCleanup() {
        assertThatThrownBy(() -> TestDatabaseSafety.validate("redis://localhost:6379", 0, "mongodb://localhost:27018", "game_test", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseSafety.validate("redis://localhost:6379", 15, "mongodb://localhost:27018", "game", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseSafety.validate("redis://other:6379", 15, "mongodb://localhost:27018", "game_test", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDatabaseSafety.validate("redis://localhost:6379", 15, "mongodb://localhost:27018", "game_test", false)).isInstanceOf(IllegalStateException.class);
        TestDatabaseSafety.validate("redis://127.0.0.1:6379", 15, "mongodb://localhost:27018", "game_test", true);
    }
}
