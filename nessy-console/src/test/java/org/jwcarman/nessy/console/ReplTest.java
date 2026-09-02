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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The whole application in one call, when there is nothing to talk to.
 *
 * <p>This module deliberately depends on NO model adapters — discovery finds whatever the
 * application put on its classpath — so in its own tests there is never a provider to find. That
 * makes the unhappy path the one testable here, and it is worth testing: it is the first thing
 * somebody meets when they have not set a key.
 */
@DisplayName("A console application with no model configured")
class ReplTest {

  @Test
  @DisplayName("it explains itself instead of throwing a stack trace out of main")
  void it_says_what_is_missing_and_returns() {
    FakeConsole console = new FakeConsole();

    assertThatCode(() -> Repl.run(new ReplConfig(), console)).doesNotThrowAnyException();

    assertThat(console.written()).isNotEmpty();
  }

  @Test
  @DisplayName("the message names what to configure, since that is the whole useful content")
  void the_message_is_discoverys_own() {
    FakeConsole console = new FakeConsole();

    Repl.run(new ReplConfig(), console);

    assertThat(console.written().toLowerCase())
        .as("discovery names every provider it knows and the variables each one reads")
        .containsAnyOf("provider", "model", "api key", "api_key");
  }

  @Test
  @DisplayName("it never reaches the loop, so nothing is read from the console")
  void it_does_not_start_a_conversation() {
    FakeConsole console = new FakeConsole("hello");

    Repl.run(new ReplConfig(), console);

    assertThat(console.written())
        .as("the banner belongs to the loop, which this run never gets to")
        .doesNotContain("nessy chat");
  }

  /**
   * The one-argument overload is the whole public entry point: build a config, hand it to the
   * customizer, run against the real console. It is exercised here rather than mocked away because
   * it is the ONE path {@code main} actually calls.
   */
  @Nested
  @DisplayName("the one call from main")
  class TheOneCallFromMain {

    @Test
    @DisplayName("a null customizer is refused, not silently treated as no configuration")
    void a_null_customizer_is_refused() {
      assertThatThrownBy(() -> Repl.run(null)).isInstanceOf(NullPointerException.class);
    }

    /**
     * Runs to completion without a model configured, same as {@link ReplTest} above, but through
     * the real one-argument entry point: the customizer is applied, a config is built from it, and
     * discovery still fails the same way. Nothing here reads from or writes to a fake console — the
     * real one is used, exactly as {@code main} would — so the only thing to assert is that it
     * returns instead of hanging or throwing.
     */
    @Test
    @DisplayName("the customizer is applied before the same 'nothing to talk to' failure appears")
    void the_customizer_runs_and_then_discovery_still_fails_the_same_way() {
      List<String> customizedWith = new ArrayList<>();

      assertThatCode(() -> Repl.run(config -> customizedWith.add(config.systemPrompt())))
          .doesNotThrowAnyException();

      assertThat(customizedWith)
          .containsExactly("You are a helpful assistant in someone's terminal.");
    }
  }
}
