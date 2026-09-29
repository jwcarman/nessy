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
package org.jwcarman.nessy.spring.boot.inference;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import org.jwcarman.nessy.inference.gemini.GeminiInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiInferenceProvider;
import org.springframework.util.ClassUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds an {@link InferenceProvider} for a resolved provider's wire, loading the wire's adapter
 * class only when it is actually needed.
 *
 * <p>Each wire's construction lives in its own private static nested class, so a wire whose adapter
 * jar is not on the classpath never triggers that adapter class's static initialisation: the class
 * is loaded only when its nested class is first used, which is only after the classpath check below
 * has already passed.
 */
final class WireProviders {

  private static final String CHAT_COMPLETIONS_CLASS =
      "org.jwcarman.nessy.inference.openai.OpenAiInferenceProvider";
  private static final String MESSAGES_CLASS =
      "org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider";
  private static final String GENERATE_CONTENT_CLASS =
      "org.jwcarman.nessy.inference.gemini.GeminiInferenceProvider";

  private WireProviders() {}

  /**
   * Whether the wire's adapter class is on the given classpath, without loading it.
   *
   * <p>The classloader is the caller's to supply -- {@link ProviderRegistrar} passes the bean
   * factory's own, not this class's defining one, so a test's {@code FilteredClassLoader} (which
   * wraps the context, not this jar) is actually consulted.
   */
  static boolean isPresent(Wire wire, ClassLoader classLoader) {
    return ClassUtils.isPresent(adapterClassName(wire), classLoader);
  }

  /** The Maven artifact a lit provider needs, named for {@link ProviderRegistrar}'s skip log. */
  static String artifactId(Wire wire) {
    return switch (wire) {
      case CHAT_COMPLETIONS -> "nessy-inference-openai";
      case MESSAGES -> "nessy-inference-anthropic";
      case GENERATE_CONTENT -> "nessy-inference-gemini";
    };
  }

  private static String adapterClassName(Wire wire) {
    return switch (wire) {
      case CHAT_COMPLETIONS -> CHAT_COMPLETIONS_CLASS;
      case MESSAGES -> MESSAGES_CLASS;
      case GENERATE_CONTENT -> GENERATE_CONTENT_CLASS;
    };
  }

  /**
   * Builds the provider. Callers check {@link #isPresent(Wire, ClassLoader)} first; called only
   * when it already answered {@code true}, so the {@code Optional} here is always present -- its
   * shape lets a caller cheaply guard the same check without asking twice via a second reflective
   * lookup.
   */
  static Optional<InferenceProvider> build(
      ResolvedProvider resolved, @Nullable JsonMapper mapper, ClassLoader classLoader) {
    if (!isPresent(resolved.wire(), classLoader)) {
      return Optional.empty();
    }
    return Optional.of(
        switch (resolved.wire()) {
          case CHAT_COMPLETIONS -> ChatCompletions.build(resolved, mapper);
          case MESSAGES -> Messages.build(resolved, mapper);
          case GENERATE_CONTENT -> GenerateContent.build(resolved, mapper);
        });
  }

  private static final class ChatCompletions {

    private ChatCompletions() {}

    static InferenceProvider build(ResolvedProvider resolved, @Nullable JsonMapper mapper) {
      return OpenAiInferenceProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.vendor(resolved.vendor());
            c.timeout(TransportTimeouts.PROVIDER_TRANSPORT);
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }

  private static final class Messages {

    private Messages() {}

    static InferenceProvider build(ResolvedProvider resolved, @Nullable JsonMapper mapper) {
      return AnthropicInferenceProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.timeout(TransportTimeouts.PROVIDER_TRANSPORT);
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }

  private static final class GenerateContent {

    private GenerateContent() {}

    static InferenceProvider build(ResolvedProvider resolved, @Nullable JsonMapper mapper) {
      return GeminiInferenceProvider.of(
          c -> {
            c.apiKey(resolved.apiKey());
            if (resolved.baseUrl() != null) {
              c.baseUrl(resolved.baseUrl());
            }
            c.timeout(TransportTimeouts.PROVIDER_TRANSPORT);
            if (mapper != null) {
              c.mapper(mapper);
            }
          });
    }
  }
}
