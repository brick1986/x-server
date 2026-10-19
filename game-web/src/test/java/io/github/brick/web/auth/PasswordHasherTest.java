package io.github.brick.web.auth;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PasswordHasherTest {
    @Test
    void saltedHashesVerifyOnlyTheOriginalPassword() {
        var hasher = new PasswordHasher(new SecureRandom());
        String first = hasher.hash("a strong password 123"), second = hasher.hash("a strong password 123");
        assertThat(first).isNotEqualTo(second).doesNotContain("a strong password");
        assertThat(hasher.matches("a strong password 123", first)).isTrue();
        assertThat(hasher.matches("another password 123", first)).isFalse();
        assertThat(hasher.matches("a strong password 123", "pbkdf2$999999999$bad$bad")).isFalse();
        assertThat(hasher.matches("a strong password 123", "broken")).isFalse();
    }
}
