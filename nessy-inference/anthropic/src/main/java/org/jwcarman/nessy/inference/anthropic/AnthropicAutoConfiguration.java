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
package org.jwcarman.nessy.inference.anthropic;

import org.jwcarman.nessy.spi.inference.InferenceProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Anthropic as a Boot citizen: {@code ANTHROPIC_API_KEY} present (Boot's relaxed binding reads it
 * as {@code anthropic.api-key}) contributes an {@link AnthropicInferenceProvider} bean built from
 * that key alone — the same {@code create(c -> c.apiKey(key))} an application would write, not
 * {@link AnthropicInferenceProvider#fromEnv()}, so the key Boot saw is the key that gets built and
 * no other SDK-level variable is read underneath it.
 *
 * <p>{@code @ConditionalOnMissingBean} rather than a hard requirement: an application that declares
 * its own {@link InferenceProvider} bean is choosing one explicitly, and this backs off entirely —
 * the same convention {@code NessyAutoConfiguration} follows.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "anthropic.api-key")
public class AnthropicAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(InferenceProvider.class)
  public InferenceProvider anthropicInferenceProvider(
      @Value("${anthropic.api-key}") String apiKey, ObjectProvider<JsonMapper> mappers) {
    return AnthropicInferenceProvider.create(
        c -> {
          c.apiKey(apiKey);
          mappers.ifAvailable(c::mapper);
        });
  }
}
