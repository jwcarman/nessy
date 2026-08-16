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
package org.jwcarman.nessy.autoconfigure;

/**
 * Property keys and provider class names shared by {@link AnthropicProviderAutoConfiguration},
 * {@link OpenAiProviderAutoConfiguration}, {@link GeminiProviderAutoConfiguration}, and {@link
 * BedrockProviderAutoConfiguration}'s selection conditions.
 *
 * <p>Hoisted into one place so the four autoconfigurations' mirrored selection logic can never
 * desync on a literal — a typo in a copy-pasted string constant would otherwise fail silently on
 * one side only.
 *
 * <p>Neither {@code BEDROCK_KEY_PROPERTY} nor {@code BEDROCK_PROVIDER_CLASS_NAME} exists here, and
 * deliberately so: Bedrock is explicit-selection-only (bedrock-provider design §4) — it is never
 * "keyed" the way the other three are, so it never participates in {@link
 * AnthropicProviderAutoConfiguration.AmbiguousProviderCondition}'s keyed count; and its classpath
 * presence must never influence any <em>other</em> provider's selection condition either — not even
 * the "sole module present" fallback each of {@link
 * AnthropicProviderAutoConfiguration.AnthropicIsTheChoiceCondition}, {@link
 * OpenAiProviderAutoConfiguration.OpenAiIsTheChoiceCondition}, and {@link
 * GeminiProviderAutoConfiguration.GeminiIsTheChoiceCondition} run, which deliberately checks only
 * for each other, not for Bedrock. A classpath with Gemini and Bedrock both present, neither
 * Anthropic nor OpenAI, and no explicit {@code nessy.provider} still lets Gemini's own fallback
 * treat itself as the sole present module — correctly, since Bedrock can never contest that
 * decision (explicit-only selection means it never auto-wins anything). {@link
 * BedrockProviderAutoConfiguration} needs no string-name self-check either: its own
 * {@code @ConditionalOnClass(BedrockModelProvider.class)} uses the class literal directly, the same
 * as every sibling does for its own class.
 */
final class ProviderProperties {

  static final String PROVIDER_PROPERTY = "nessy.provider";
  static final String ANTHROPIC_KEY_PROPERTY = "nessy.anthropic.api-key";
  static final String OPENAI_KEY_PROPERTY = "nessy.openai.api-key";
  static final String GEMINI_KEY_PROPERTY = "nessy.gemini.api-key";
  static final String ANTHROPIC_PROVIDER_CLASS_NAME =
      "org.jwcarman.nessy.model.anthropic.AnthropicModelProvider";
  static final String OPENAI_PROVIDER_CLASS_NAME =
      "org.jwcarman.nessy.model.openai.OpenAiModelProvider";
  static final String GEMINI_PROVIDER_CLASS_NAME =
      "org.jwcarman.nessy.model.gemini.GeminiModelProvider";

  private ProviderProperties() {}
}
