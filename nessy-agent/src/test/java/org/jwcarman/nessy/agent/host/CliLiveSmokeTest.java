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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Exercises the {@code cli} door (§7.1) against whichever real provider {@link ModelDiscovery}
 * resolves from the environment.
 *
 * <p>The test starts with {@code assumeTrue} on at least one provider API key being present, so the
 * default, keyless build (which excludes the {@code live} tag entirely — see the root {@code
 * pom.xml}'s {@code nessy.excludedGroups}) never depends on network access, and a stray {@code
 * -Dtest=...} run without any key skips cleanly instead of failing. This is the intended tinkering
 * entry point for this module: point one of {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY},
 * {@code GEMINI_API_KEY}/{@code GOOGLE_API_KEY}, or {@code XAI_API_KEY} at a real key and run it
 * directly.
 */
@Tag("live")
class CliLiveSmokeTest {

  @Test
  void aRealProviderSaysHello() throws Exception {
    assumeTrue(anyProviderKeyPresent(), "no model provider API key set");

    var selection = ModelDiscovery.select();
    var settings = new ModelSettings(64, Set.of(), null);
    var input =
        new ByteArrayInputStream(
            "Reply with exactly the word: pong\n".getBytes(StandardCharsets.UTF_8));
    var captured = new ByteArrayOutputStream();

    try (var console =
        Nessy.cli()
            .model(selection.model())
            .systemPrompt("You are a terse assistant.")
            .settings(settings)
            .in(input)
            .out(new PrintStream(captured, true, StandardCharsets.UTF_8))
            .build()) {
      console.run();
    }

    assertThat(captured.toString(StandardCharsets.UTF_8))
        .isNotBlank()
        .doesNotContain("turn failed");
  }

  private static boolean anyProviderKeyPresent() {
    return System.getenv("ANTHROPIC_API_KEY") != null
        || System.getenv("OPENAI_API_KEY") != null
        || System.getenv("GEMINI_API_KEY") != null
        || System.getenv("GOOGLE_API_KEY") != null
        || System.getenv("XAI_API_KEY") != null;
  }
}
