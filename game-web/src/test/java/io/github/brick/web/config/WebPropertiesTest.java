package io.github.brick.web.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class WebPropertiesTest {
    @Test
    void invalidLimitsCannotStart() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var config = new WebProperties();
            assertThat(factory.getValidator().validate(config)).isEmpty();
            config.setSessionTtlSeconds(0);
            config.setAuthIpLimit(0);
            assertThat(factory.getValidator().validate(config)).hasSize(2);
        }
    }
}
