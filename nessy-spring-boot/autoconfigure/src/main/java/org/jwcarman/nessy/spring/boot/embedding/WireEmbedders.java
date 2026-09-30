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

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.embedding.EmbeddingProvider;
import org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider;
import org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider;
import org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds an {@link EmbeddingProvider} for a resolved embedder's wire, loading the wire's adapter
 * class only when it is actually needed.
 *
 * <p>Each wire's construction lives in its own private static nested class, so a wire whose adapter
 * jar is not on the classpath never triggers that adapter class's loading: the nested class is
 * first used only after the classpath check has passed. Sets what each adapter's config offers and
 * no more; no timeout is set, so Voyage keeps its own.
 */
final class WireEmbedders {

  private static final String OPENAI_CLASS =
      "org.jwcarman.nessy.embedding.openai.OpenAiEmbeddingProvider";
  private static final String GEMINI_CLASS =
      "org.jwcarman.nessy.embedding.gemini.GeminiEmbeddingProvider";
  private static final String VOYAGE_CLASS =
      "org.jwcarman.nessy.embedding.voyage.VoyageEmbeddingProvider";

  private WireEmbedders() {}

  /**
   * Whether the wire's adapter class is on the given classpath, without loading it. The caller
   * supplies the bean factory's class loader, so a test's {@code FilteredClassLoader} is consulted.
   */
  static boolean isPresent(EmbeddingWire wire, ClassLoader classLoader) {
    return ClassUtils.isPresent(adapterClassName(wire), classLoader);
  }

  /** The Maven artifact a lit embedder needs, named for the registrar's skip line. */
  static String artifactId(EmbeddingWire wire) {
    return switch (wire) {
      case OPENAI -> "nessy-embedding-openai";
      case GEMINI -> "nessy-embedding-gemini";
      case VOYAGE -> "nessy-embedding-voyage";
    };
  }

  private static String adapterClassName(EmbeddingWire wire) {
    return switch (wire) {
      case OPENAI -> OPENAI_CLASS;
      case GEMINI -> GEMINI_CLASS;
      case VOYAGE -> VOYAGE_CLASS;
    };
  }

  /** Builds the provider, or nothing when the wire's adapter is absent. */
  static Optional<EmbeddingProvider> build(
      ResolvedEmbedder resolved, @Nullable JsonMapper mapper, ClassLoader classLoader) {
    if (!isPresent(resolved.wire(), classLoader)) {
      return Optional.empty();
    }
    return Optional.of(
        switch (resolved.wire()) {
          case OPENAI -> OpenAi.build(resolved);
          case GEMINI -> Gemini.build(resolved);
          case VOYAGE -> Voyage.build(resolved, mapper);
        });
  }

  private static final class OpenAi {

    private OpenAi() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved) {
      return OpenAiEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.vendor(resolved.vendor());
            c.properties(resolved.properties());
          });
    }
  }

  private static final class Gemini {

    private Gemini() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved) {
      return GeminiEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.properties(resolved.properties());
          });
    }
  }

  private static final class Voyage {

    private Voyage() {}

    static EmbeddingProvider build(ResolvedEmbedder resolved, @Nullable JsonMapper mapper) {
      return VoyageEmbeddingProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.properties(resolved.properties());
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }
}
