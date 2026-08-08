package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void runsOnJava25OrLater() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(25);
    }
}
