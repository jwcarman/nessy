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
package org.jwcarman.nessy.spring.boot.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.springframework.boot.test.context.FilteredClassLoader;
import tools.jackson.databind.json.JsonMapper;

/** Which adapter each embedding wire builds, and that what was resolved reaches it. */
class WireEmbeddersTest {

  private static final ClassLoader LOADER = WireEmbeddersTest.class.getClassLoader();

  @Test
  void the_openai_wire_builds_the_openai_adapter_reporting_the_resolved_vendor() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "local",
            EmbeddingWire.OPENAI,
            "http://localhost:1234/v1",
            "lmstudio",
            "lm-studio",
            Map.of());

    Optional<EmbeddingProvider> built = WireEmbedders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(
            OpenAiEmbeddingProvider.class,
            provider -> {
              assertThat(provider.vendor()).isEqualTo("lmstudio");
              provider.close();
            });
  }

  @Test
  void the_gemini_wire_builds_the_gemini_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("gemini", EmbeddingWire.GEMINI, null, "gcp.gemini", "k", Map.of());

    Optional<EmbeddingProvider> built = WireEmbedders.build(resolved, null, LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(GeminiEmbeddingProvider.class, GeminiEmbeddingProvider::close);
  }

  @Test
  void the_voyage_wire_builds_the_voyage_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage", EmbeddingWire.VOYAGE, "https://api.voyageai.com/v1", "voyage", "k", Map.of());

    Optional<EmbeddingProvider> built =
        WireEmbedders.build(resolved, JsonMapper.builder().build(), LOADER);

    assertThat(built)
        .get()
        .isInstanceOfSatisfying(VoyageEmbeddingProvider.class, VoyageEmbeddingProvider::close);
  }

  @Test
  void each_wire_names_the_module_it_needs() {
    assertThat(WireEmbedders.artifactId(EmbeddingWire.OPENAI)).isEqualTo("nessy-embedding-openai");
    assertThat(WireEmbedders.artifactId(EmbeddingWire.GEMINI)).isEqualTo("nessy-embedding-gemini");
    assertThat(WireEmbedders.artifactId(EmbeddingWire.VOYAGE)).isEqualTo("nessy-embedding-voyage");
  }

  @Test
  void a_wire_whose_adapter_is_absent_builds_nothing() {
    ClassLoader withoutVoyage = new FilteredClassLoader(VoyageEmbeddingProvider.class);
    ResolvedEmbedder resolved =
        new ResolvedEmbedder("voyage", EmbeddingWire.VOYAGE, null, "voyage", "k", Map.of());

    assertThat(WireEmbedders.isPresent(EmbeddingWire.VOYAGE, withoutVoyage)).isFalse();
    assertThat(WireEmbedders.build(resolved, null, withoutVoyage)).isEmpty();
  }

  /** Only the adapter config's own build-time check can refuse it, so it got there. */
  @Test
  void the_resolved_properties_reach_the_voyage_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "voyage",
            EmbeddingWire.VOYAGE,
            "https://api.voyageai.com/v1",
            "voyage",
            "k",
            Map.of("openai.user", "x"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'");
  }

  @Test
  void the_openai_wire_hands_its_properties_to_the_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "openai", EmbeddingWire.OPENAI, null, "openai", "k", Map.of("voyage.truncation", "x"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'voyage.truncation'");
  }

  @Test
  void the_gemini_wire_hands_its_properties_to_the_adapter() {
    ResolvedEmbedder resolved =
        new ResolvedEmbedder(
            "gemini", EmbeddingWire.GEMINI, null, "gcp.gemini", "k", Map.of("openai.user", "x"));

    assertThatThrownBy(() -> WireEmbedders.build(resolved, null, LOADER))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'");
  }
}
