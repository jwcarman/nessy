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
package org.jwcarman.nessy.model.gemini;

import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Gemini as a Boot citizen: {@code GEMINI_API_KEY} present (Boot's relaxed binding reads it as
 * {@code gemini.api-key}) contributes a {@link GeminiModelProvider} bean built from that key.
 *
 * <p>Google's documented fallback pair also reads {@code GOOGLE_API_KEY} when {@code
 * GEMINI_API_KEY} is unset; this bean is gated on {@code gemini.api-key} specifically, since Boot's
 * {@code @ConditionalOnProperty} names one property. An application that authenticates only through
 * {@code GOOGLE_API_KEY} declares its own {@code ModelProvider} bean — {@code
 * GeminiModelProvider.create(GeminiProviderConfig::fromEnv)} still reads both variables, in that
 * order, exactly as it always has.
 *
 * <p>{@code @ConditionalOnMissingBean} rather than a hard requirement: an application that declares
 * its own {@link ModelProvider} bean is choosing one explicitly, and this backs off entirely — the
 * same convention {@code NessyAutoConfiguration} follows.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "gemini.api-key")
public class GeminiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(ModelProvider.class)
  public ModelProvider geminiModelProvider(@Value("${gemini.api-key}") String apiKey) {
    return GeminiModelProvider.create(c -> c.apiKey(apiKey));
  }
}
