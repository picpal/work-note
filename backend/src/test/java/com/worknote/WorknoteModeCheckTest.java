package com.worknote;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorknoteModeCheckTest {

    @Test
    void unknownModeFailsFast() {
        assertThatThrownBy(() -> new WorknoteModeCheck("prod"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("prod");
    }

    @Test
    void localAndServerAreValid() {
        assertThatCode(() -> new WorknoteModeCheck("local")).doesNotThrowAnyException();
        assertThatCode(() -> new WorknoteModeCheck("server")).doesNotThrowAnyException();
    }
}
