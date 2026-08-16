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

import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when {@code nessy.provider} is set to something other than the three recognized values,
 * {@code anthropic}, {@code openai}, or {@code gemini} — a typo (e.g. {@code anthorpic}) that no
 * provider autoconfiguration's own selection condition recognizes.
 *
 * <p>Without this, an unrecognized value silently satisfies nothing: {@link
 * AnthropicProviderAutoConfiguration.AnthropicIsTheChoiceCondition}, {@link
 * OpenAiProviderAutoConfiguration.OpenAiIsTheChoiceCondition}, and {@link
 * GeminiProviderAutoConfiguration.GeminiIsTheChoiceCondition} all {@code noMatch} (the value isn't
 * their name), and {@link AnthropicProviderAutoConfiguration.AmbiguousProviderCondition} also
 * {@code noMatch}s (it requires the property to be entirely unset) — so no {@code ModelProvider}
 * bean is built, no {@code Harness} follows, and the application fails much later with a bare
 * {@code NoSuchBeanDefinitionException} that never mentions {@code nessy.provider} at all. This
 * condition closes that gap by matching the typo itself, so the bean it guards can fail fast and
 * name both the property and the bad value.
 *
 * <p>The exception messages thrown by {@link
 * AnthropicProviderAutoConfiguration#invalidProviderModelProvider} and {@link
 * OpenAiProviderAutoConfiguration#invalidProviderModelProvider} still read "expected anthropic or
 * openai" verbatim — those two beans predate Gemini and their literal wording is pinned by existing
 * tests, so it is left as-is rather than edited to also list {@code gemini} (which would be a
 * behavior-preserving edit only for scenarios those tests don't exercise, but the literal string is
 * exactly what {@code hasRootCauseMessage} asserts). {@link
 * GeminiProviderAutoConfiguration#invalidProviderModelProvider} — new in this class, owning only
 * the classpath-has-neither-Anthropic-nor-OpenAI case — lists all three.
 */
final class InvalidProviderCondition extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(
      ConditionContext context, AnnotatedTypeMetadata metadata) {
    var message = ConditionMessage.forCondition("Nessy Invalid Provider Selection");
    var provider = context.getEnvironment().getProperty(ProviderProperties.PROVIDER_PROPERTY);
    if (!StringUtils.hasText(provider)) {
      return ConditionOutcome.noMatch(message.because("nessy.provider is unset"));
    }
    if ("anthropic".equals(provider) || "openai".equals(provider) || "gemini".equals(provider)) {
      return ConditionOutcome.noMatch(
          message.because("nessy.provider=" + provider + " is recognized"));
    }
    return ConditionOutcome.match(
        message.because("nessy.provider=" + provider + " is not a recognized value"));
  }
}
