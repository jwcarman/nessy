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
package org.jwcarman.nessy.embedding.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;

/**
 * No embedder property is supported yet: one under its own prefix is ignored with a warning;
 * another prefix is a mistake.
 */
class OpenAiEmbedderConfigTest {

  @Test
  void a_property_under_its_own_prefix_builds() {
    assertThatCode(
            () ->
                OpenAiEmbeddingProvider.of(
                        c -> c.apiKey("test-key").property("openai.user", "tenant-42"))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<OpenAiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("voyage.truncation", "false"));

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'voyage.truncation'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void a_blank_value_is_refused_naming_the_property() {
    Customizer<OpenAiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.user", " ");

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'");
  }

  @Test
  void a_property_under_its_own_prefix_is_warned_once_at_build_naming_it_and_no_supported_names() {
    var built = new OpenAiEmbeddingProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            OpenAiEmbedderConfig.class,
            () ->
                built[0] =
                    OpenAiEmbeddingProvider.of(
                        c -> c.apiKey("test-key").property("openai.user", "x")));

    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "NESSY EMBEDDING: property 'openai.user' is not supported by openai and is ignored;"
                + " supported: []");
    built[0].close();
  }

  @Test
  void a_name_with_no_prefix_is_refused_at_build() {
    Customizer<OpenAiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property("dimensions", "3");

    assertThatThrownBy(() -> OpenAiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'dimensions'");
  }

  @Test
  void a_name_with_no_prefix_is_refused_when_an_embedder_is_validated() {
    Map<String, String> properties = Map.of("dimensions", "3");

    assertThatThrownBy(() -> OpenAiEmbedderConfig.warnUnsupported(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'dimensions'")
        .hasMessageContaining("no prefix");
  }

  @Test
  void a_property_under_another_prefix_is_not_warned_but_named_at_debug() {
    Map<String, String> properties = Map.of("other.thing", "x");

    List<ILoggingEvent> events =
        LogCapture.during(
            OpenAiEmbedderConfig.class, () -> OpenAiEmbedderConfig.warnUnsupported(properties));

    assertThat(LogCapture.warnings(events)).isEmpty();
    assertThat(events)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "NESSY EMBEDDING: properties for other adapters, ignored here: [other.thing]");
  }

  @Test
  void a_supported_prefix_warning_names_the_full_prefixed_name() {
    Map<String, String> properties = Map.of("openai.thing", "x");

    List<ILoggingEvent> events =
        LogCapture.during(
            OpenAiEmbedderConfig.class, () -> OpenAiEmbedderConfig.warnUnsupported(properties));

    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "NESSY EMBEDDING: property 'openai.thing' is not supported by openai and is ignored; supported: []");
  }
}
