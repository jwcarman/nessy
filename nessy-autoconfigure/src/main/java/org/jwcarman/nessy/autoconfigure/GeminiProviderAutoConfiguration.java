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

import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.model.gemini.GeminiModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Wires a Gemini-backed {@link ModelProvider} when {@code nessy-model-gemini} is on the classpath.
 *
 * <p>The ambiguous-classpath / fail-fast cases that involve Gemini are owned elsewhere: {@link
 * AnthropicProviderAutoConfiguration#ambiguousModelProvider} and {@link
 * AnthropicProviderAutoConfiguration#ambiguousModelProviderWithoutOpenAi} own every case where
 * Anthropic is present, and {@link
 * OpenAiProviderAutoConfiguration#ambiguousModelProviderWithoutAnthropic} owns the one remaining
 * case — OpenAI and Gemini both present and keyed, Anthropic absent. Declaring an ambiguous bean
 * here too would race a duplicate {@link ModelProvider} bean definition against whichever of those
 * already matches. This configuration only needs to recognize when it is unambiguously Gemini's
 * turn to build the bean itself: it is the only provider module present, it is explicitly selected
 * via {@code nessy.provider=gemini}, or it is the only present one configured with an API key.
 *
 * <p>The bean here also backs off when a {@link Harness} bean is already present
 * ({@code @ConditionalOnMissingBean({ModelProvider.class, Harness.class})} — any listed type
 * present is enough to suppress the bean method). The autoconfigured provider exists solely to feed
 * {@link NessyAutoConfiguration}'s autoconfigured {@code Harness}; an application that supplies its
 * own {@code Harness} has, by construction, already brought its own provider (a {@code Harness}
 * cannot be built without one), so eagerly building — and keylessly failing to build — a second,
 * unused provider here is both wasted work and a spurious startup failure for an app that never
 * asked for this module's provider at all.
 */
@AutoConfiguration
@ConditionalOnClass(GeminiModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class GeminiProviderAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(GeminiIsTheChoiceCondition.class)
  ModelProvider geminiModelProvider(NessyProperties properties) {
    return buildGeminiProvider(properties);
  }

  /**
   * {@code nessy.provider} names something other than {@code anthropic}, {@code openai}, or {@code
   * gemini} — most likely a typo. {@link
   * AnthropicProviderAutoConfiguration#invalidProviderModelProvider} and {@link
   * OpenAiProviderAutoConfiguration#invalidProviderModelProvider} already cover every classpath
   * where either of those modules is present; this bean fills the one gap that leaves — a
   * Gemini-only classpath — gated on both their absence so no two of the three ever match at once
   * and race a duplicate bean definition. All three sibling beans' messages list the same three
   * recognized values.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnMissingClass({
    ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME,
    ProviderProperties.OPENAI_PROVIDER_CLASS_NAME
  })
  @Conditional(InvalidProviderCondition.class)
  ModelProvider invalidProviderModelProvider(NessyProperties properties) {
    throw new IllegalStateException(
        "nessy.provider="
            + properties.provider()
            + " is not a recognized value; expected"
            + " anthropic, openai, or gemini");
  }

  /**
   * {@code nessy.gemini.api-key} / {@code nessy.gemini.base-url} are overrides layered on top of
   * the SDK's own environment resolution, not replacements for it: {@link
   * GeminiModelProvider.Builder#fromEnv()} is always called first (it only sets a flag — nothing is
   * read until {@code build()}), so the ambient {@code GEMINI_API_KEY} / {@code GOOGLE_API_KEY}
   * environment variables are still honored when a property here is absent, and an explicit
   * property always wins when present.
   */
  static ModelProvider buildGeminiProvider(NessyProperties properties) {
    var gemini = properties.gemini();
    var builder = GeminiModelProvider.builder().fromEnv();
    var apiKey = gemini == null ? null : gemini.apiKey();
    if (StringUtils.hasText(apiKey)) {
      builder.apiKey(apiKey);
    }
    var baseUrl = gemini == null ? null : gemini.baseUrl();
    if (StringUtils.hasText(baseUrl)) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  /**
   * Matches when Gemini is the provider this configuration should build.
   *
   * <p><strong>Precedence ruling:</strong> an explicit {@code nessy.*} property is a deliberate
   * nessy-level choice and outranks an ambient SDK environment variable, which is a weaker,
   * incidental signal. Concretely: if only {@code nessy.gemini.api-key} is set (and neither {@code
   * nessy.anthropic.api-key} nor {@code nessy.openai.api-key} is), Gemini wins even when {@code
   * ANTHROPIC_API_KEY} happens to be present in the environment too — this configuration only
   * inspects the {@code nessy.*} keys, so an env-var-only Anthropic never counts as "keyed" here.
   * "Ambiguous" means more than one present provider is explicitly keyed via a {@code nessy.*}
   * property, not that the others could ultimately build — that distinction is deliberate, not an
   * oversight.
   */
  static final class GeminiIsTheChoiceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy Gemini Provider Selection");
      var environment = context.getEnvironment();
      var provider = environment.getProperty(ProviderProperties.PROVIDER_PROPERTY);
      if ("gemini".equals(provider)) {
        return ConditionOutcome.match(message.because("nessy.provider=gemini"));
      }
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var classLoader = context.getClassLoader();
      var anthropicPresent =
          ClassUtils.isPresent(ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME, classLoader);
      var openaiPresent =
          ClassUtils.isPresent(ProviderProperties.OPENAI_PROVIDER_CLASS_NAME, classLoader);
      if (!anthropicPresent && !openaiPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var geminiKeyed = environment.containsProperty(ProviderProperties.GEMINI_KEY_PROPERTY);
      var otherKeyed =
          (anthropicPresent
                  && environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY))
              || (openaiPresent
                  && environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY));
      if (geminiKeyed && !otherKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ProviderProperties.GEMINI_KEY_PROPERTY + " is set"));
      }
      return ConditionOutcome.noMatch(
          message.because("multiple provider modules are present and unresolved"));
    }
  }
}
