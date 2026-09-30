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
package org.jwcarman.nessy.embedding.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperty;

/**
 * No embedder property is supported yet: one under its own prefix is ignored with a warning;
 * another prefix is a mistake.
 */
class GeminiEmbedderConfigTest {

  @Test
  void a_property_under_its_own_prefix_builds() {
    assertThatCode(
            () ->
                GeminiEmbeddingProvider.of(
                        c -> c.apiKey("test-key").property("gemini.labels.team", "billing"))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<GeminiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("openai.user", "tenant-42"));

    assertThatThrownBy(() -> GeminiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'")
        .hasMessageContaining("'gemini.'");
  }

  @Test
  void a_typed_property_is_stored_under_its_name_and_refused_like_the_string_form() {
    Customizer<GeminiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property(VendorProperty.ofBoolean("openai.user"), true);

    assertThatThrownBy(() -> GeminiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'")
        .hasMessageContaining("'gemini.'");
  }

  @Test
  void a_blank_value_is_refused_naming_the_property() {
    Customizer<GeminiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property("gemini.labels.team", " ");

    assertThatThrownBy(() -> GeminiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.labels.team'");
  }

  @Test
  void a_property_under_its_own_prefix_is_warned_once_at_build_naming_it_and_no_supported_names() {
    var built = new GeminiEmbeddingProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            GeminiEmbedderConfig.class,
            () ->
                built[0] =
                    GeminiEmbeddingProvider.of(
                        c -> c.apiKey("test-key").property("gemini.labels.team", "x")));

    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "NESSY EMBEDDING: property 'gemini.labels.team' is not supported by gemini and is ignored;"
                + " supported: []");
    built[0].close();
  }

  @Test
  void a_name_with_no_prefix_is_refused_at_build() {
    Customizer<GeminiEmbedderConfig> customizer =
        c -> c.apiKey("test-key").property("dimensions", "3");

    assertThatThrownBy(() -> GeminiEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'dimensions'");
  }

  @Test
  void a_name_with_no_prefix_is_refused_when_an_embedder_is_validated() {
    Map<String, String> properties = Map.of("dimensions", "3");

    assertThatThrownBy(() -> GeminiEmbedderConfig.warnUnsupported(properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'dimensions'")
        .hasMessageContaining("no prefix");
  }

  @Test
  void a_property_under_another_prefix_is_not_warned_but_named_at_debug() {
    Map<String, String> properties = Map.of("other.thing", "x");

    List<ILoggingEvent> events =
        LogCapture.during(
            GeminiEmbedderConfig.class, () -> GeminiEmbedderConfig.warnUnsupported(properties));

    assertThat(LogCapture.warnings(events)).isEmpty();
    assertThat(events)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "NESSY EMBEDDING: properties for other adapters, ignored here: [other.thing]");
  }

  @Test
  void a_supported_prefix_warning_names_the_full_prefixed_name() {
    Map<String, String> properties = Map.of("gemini.thing", "x");

    List<ILoggingEvent> events =
        LogCapture.during(
            GeminiEmbedderConfig.class, () -> GeminiEmbedderConfig.warnUnsupported(properties));

    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "NESSY EMBEDDING: property 'gemini.thing' is not supported by gemini and is ignored; supported: []");
  }
}
