package org.lsc.plugins.connectors.james;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.lsc.plugins.connectors.james.beans.Identity;

class IdentityDisplayNameTest {
    private static final String FALLBACK_EMAIL_ADDRESS = "antoine.griezmann@domain.tld";

    @Test
    void firstnameAndSurnameAreBothPresent() {
        Optional<String> firstname = Optional.of("Antoine");
        Optional<String> surname = Optional.of("Griezmann");

        assertThat(Identity.toDisplayName(firstname, surname, FALLBACK_EMAIL_ADDRESS))
            .isEqualTo("Antoine Griezmann");
    }

    @Test
    void onlyFirstnameIsPresent() {
        Optional<String> firstname = Optional.of("Antoine");
        Optional<String> surname = Optional.empty();

        assertThat(Identity.toDisplayName(firstname, surname, FALLBACK_EMAIL_ADDRESS))
            .isEqualTo("Antoine");
    }

    @Test
    void onlySurnameIsPresent() {
        Optional<String> firstname = Optional.empty();
        Optional<String> surname = Optional.of("Griezmann");

        assertThat(Identity.toDisplayName(firstname, surname, FALLBACK_EMAIL_ADDRESS))
            .isEqualTo("Griezmann");
    }

    @Test
    void firstnameAndSurnameAreBothEmpty() {
        Optional<String> firstname = Optional.empty();
        Optional<String> surname = Optional.empty();

        assertThat(Identity.toDisplayName(firstname, surname, FALLBACK_EMAIL_ADDRESS))
            .isEqualTo(FALLBACK_EMAIL_ADDRESS);
    }
}
