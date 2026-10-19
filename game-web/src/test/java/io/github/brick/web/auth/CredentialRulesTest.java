package io.github.brick.web.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CredentialRulesTest {
    @Test
    void canonicalNamesAndPasswordCodePointBounds() {
        assertThat(CredentialRules.normalizeUsername(" Alice_1 ")).isEqualTo("alice_1");
        for (String bad : new String[]{"ab", "名字123", "a:b", "x".repeat(33)})
            assertThatThrownBy(() -> CredentialRules.normalizeUsername(bad)).isInstanceOf(RuntimeException.class);
        CredentialRules.validatePassword("😀".repeat(15));
        CredentialRules.validatePassword("x".repeat(128));
        assertThatThrownBy(() -> CredentialRules.validatePassword("x".repeat(14))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CredentialRules.validatePassword("😀".repeat(129))).isInstanceOf(RuntimeException.class);
    }
}
