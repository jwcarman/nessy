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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Gemini as a Boot citizen: {@code GEMINI_API_KEY} or {@code GOOGLE_API_KEY} present — Google's own
 * documented fallback pair, Boot's relaxed binding reading them as {@code gemini.api-key} / {@code
 * google.api-key} — contributes a {@link GeminiModelProvider} bean built from {@link
 * GeminiProviderConfig#fromEnv()}, which is what actually reads both variables, in that order.
 *
 * <p>The gate has to name both properties, not just {@code gemini.api-key}: a condition narrower
 * than the factory it guards is a trap — an application setting {@code GOOGLE_API_KEY} alone would
 * set a key the factory demonstrably reads and get no bean, with nothing to explain why. {@code
 * fromEnv()} is the one already correct here; widening it to match a narrower gate would have been
 * the wrong fix.
 *
 * <p>{@code @ConditionalOnMissingBean} rather than a hard requirement: an application that declares
 * its own {@link ModelProvider} bean is choosing one explicitly, and this backs off entirely — the
 * same convention {@code NessyAutoConfiguration} follows.
 */
@AutoConfiguration
@ConditionalOnExpression("'${gemini.api-key:}' != '' or '${google.api-key:}' != ''")
public class GeminiAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(ModelProvider.class)
  public ModelProvider geminiModelProvider() {
    return GeminiModelProvider.create(GeminiProviderConfig::fromEnv);
  }
}
