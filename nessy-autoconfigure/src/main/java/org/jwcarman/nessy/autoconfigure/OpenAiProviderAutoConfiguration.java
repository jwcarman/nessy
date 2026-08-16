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
import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
import org.jwcarman.nessy.model.openai.OpenAiProviderConfig;
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
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Wires an OpenAI-backed {@link ModelProvider} when {@code nessy-model-openai} is on the classpath.
 *
 * <p>The Anthropic-present ambiguous-classpath / fail-fast cases are owned by {@link
 * AnthropicProviderAutoConfiguration#ambiguousModelProvider(Environment)} and {@link
 * AnthropicProviderAutoConfiguration#ambiguousModelProviderWithoutOpenAi(Environment)} — declaring
 * them here too would race a duplicate {@link ModelProvider} bean definition against those whenever
 * Anthropic is also present. This configuration owns the one ambiguous case Anthropic's own class
 * cannot see — OpenAI and Gemini both present and keyed, Anthropic absent from the classpath
 * entirely — via {@link #ambiguousModelProviderWithoutAnthropic(Environment)}, plus recognizing
 * when it is unambiguously OpenAI's turn to build the bean itself: it is the only provider module
 * present, it is explicitly selected via {@code nessy.provider=openai}, or it is the only present
 * one configured with an API key.
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
@ConditionalOnClass(OpenAiModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class OpenAiProviderAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(OpenAiIsTheChoiceCondition.class)
  ModelProvider openAiModelProvider(NessyProperties properties) {
    return buildOpenAiProvider(properties);
  }

  /**
   * OpenAI and Gemini are both present and keyed, and Anthropic is absent from the classpath
   * entirely — the one ambiguous combination {@link AnthropicProviderAutoConfiguration} cannot see,
   * since that whole class is gated on Anthropic's presence. Reuses {@link
   * AnthropicProviderAutoConfiguration.AmbiguousProviderCondition} and {@link
   * AnthropicProviderAutoConfiguration#ambiguousProviderMessage(Environment)} directly rather than
   * duplicating either — both are already classpath-aware and provider-agnostic, so there is
   * nothing OpenAI-specific to add.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnMissingClass(ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME)
  @ConditionalOnClass(name = ProviderProperties.GEMINI_PROVIDER_CLASS_NAME)
  @Conditional(AnthropicProviderAutoConfiguration.AmbiguousProviderCondition.class)
  ModelProvider ambiguousModelProviderWithoutAnthropic(Environment environment) {
    throw new IllegalStateException(
        AnthropicProviderAutoConfiguration.ambiguousProviderMessage(environment));
  }

  /**
   * {@code nessy.provider} names something other than {@code anthropic}, {@code openai}, {@code
   * gemini}, or {@code bedrock} — most likely a typo. {@link
   * AnthropicProviderAutoConfiguration#invalidProviderModelProvider} already covers every case
   * where {@code nessy-model-anthropic} is on the classpath; this bean fills the gap that leaves —
   * an Anthropic-absent classpath — gated on Anthropic's absence so the two never both match and
   * race a duplicate bean definition. {@link
   * GeminiProviderAutoConfiguration#invalidProviderModelProvider} and {@link
   * BedrockProviderAutoConfiguration#invalidProviderModelProvider} fill the narrower remaining gaps
   * the same way.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnMissingClass(value = ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME)
  @Conditional(InvalidProviderCondition.class)
  ModelProvider invalidProviderModelProvider(NessyProperties properties) {
    throw new IllegalStateException(
        "nessy.provider="
            + properties.provider()
            + " is not a recognized value; expected"
            + " anthropic, openai, gemini, or bedrock");
  }

  /**
   * {@code nessy.openai.api-key} / {@code nessy.openai.base-url} are overrides layered on top of
   * the SDK's own environment resolution, not replacements for it: {@link
   * OpenAiProviderConfig#fromEnv()} is always called first (it only sets a flag — nothing is read
   * until the provider is built), so every ambient source the SDK understands ({@code
   * OPENAI_ORG_ID}, {@code OPENAI_PROJECT_ID}, {@code OPENAI_BASE_URL}, {@code
   * OPENAI_WEBHOOK_SECRET}, {@code OPENAI_ADMIN_KEY}, {@code OPENAI_CUSTOM_HEADERS}, the {@code
   * AZURE_OPENAI_KEY} Azure-credential path) is still honored when a property here is absent, and
   * an explicit property always wins when present. Building does not throw in a keyless environment
   * as long as an explicit {@code apiKey} was layered on, per {@code fromEnv()}'s own javadoc.
   */
  static ModelProvider buildOpenAiProvider(NessyProperties properties) {
    var openai = properties.openai();
    var apiKey = openai == null ? null : openai.apiKey();
    var baseUrl = openai == null ? null : openai.baseUrl();
    return OpenAiModelProvider.create(
        config -> {
          config.fromEnv();
          if (StringUtils.hasText(apiKey)) {
            config.apiKey(apiKey);
          }
          if (StringUtils.hasText(baseUrl)) {
            config.baseUrl(baseUrl);
          }
        });
  }

  /**
   * Matches when OpenAI is the provider this configuration should build.
   *
   * <p><strong>Precedence ruling:</strong> an explicit {@code nessy.*} property is a deliberate
   * nessy-level choice and outranks an ambient SDK environment variable, which is a weaker,
   * incidental signal. Concretely: if only {@code nessy.openai.api-key} is set (and neither {@code
   * nessy.anthropic.api-key} nor {@code nessy.gemini.api-key} is), OpenAI wins even when {@code
   * ANTHROPIC_API_KEY} happens to be present in the environment too — this configuration only
   * inspects the {@code nessy.*} keys, so an env-var-only Anthropic never counts as "keyed" here.
   * "Ambiguous" (see {@link AnthropicProviderAutoConfiguration.AmbiguousProviderCondition}) means
   * more than one present provider is explicitly keyed via a {@code nessy.*} property, not that the
   * others could ultimately build — that distinction is deliberate, not an oversight.
   */
  static final class OpenAiIsTheChoiceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy OpenAI Provider Selection");
      var environment = context.getEnvironment();
      var provider = environment.getProperty(ProviderProperties.PROVIDER_PROPERTY);
      if ("openai".equals(provider)) {
        return ConditionOutcome.match(message.because("nessy.provider=openai"));
      }
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var classLoader = context.getClassLoader();
      var anthropicPresent =
          ClassUtils.isPresent(ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME, classLoader);
      var geminiPresent =
          ClassUtils.isPresent(ProviderProperties.GEMINI_PROVIDER_CLASS_NAME, classLoader);
      if (!anthropicPresent && !geminiPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var openaiKeyed = environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY);
      var otherKeyed =
          (anthropicPresent
                  && environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY))
              || (geminiPresent
                  && environment.containsProperty(ProviderProperties.GEMINI_KEY_PROPERTY));
      if (openaiKeyed && !otherKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ProviderProperties.OPENAI_KEY_PROPERTY + " is set"));
      }
      return ConditionOutcome.noMatch(
          message.because("multiple provider modules are present and unresolved"));
    }
  }
}
