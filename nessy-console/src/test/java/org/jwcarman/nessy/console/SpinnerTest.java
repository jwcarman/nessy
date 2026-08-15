/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.StringWriter;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SpinnerTest {

  @AfterEach
  void clear_the_override_seam() {
    Ansi.overrideEnabled(null);
  }

  @Nested
  class When_styling_is_enabled {

    @Test
    void the_spinner_writes_carriage_return_frames_until_stopped_then_erases_itself() {
      Ansi.overrideEnabled(true);
      StringWriter out = new StringWriter();
      Spinner spinner = new Spinner(out);

      spinner.start();
      await().atMost(Duration.ofSeconds(2)).until(() -> out.toString().contains("\r"));
      spinner.stop();

      String written = out.toString();
      assertThat(written).isNotEmpty().endsWith(" \r");
    }
  }

  @Nested
  class When_styling_is_disabled {

    @Test
    void the_spinner_never_writes_a_single_byte() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      Spinner spinner = new Spinner(out);

      spinner.start();
      spinner.stop();

      assertThat(out.toString()).isEmpty();
    }
  }
}
