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

import java.util.ArrayList;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.model.anthropic.AnthropicProviderConfig;
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
 * Wires an Anthropic-backed {@link ModelProvider} when {@code nessy-model-anthropic} is on the
 * classpath.
 *
 * <p>If {@code nessy-model-openai} and/or {@code nessy-model-gemini} are <em>also</em> present,
 * this class only builds the bean when Anthropic is the unambiguous choice: it is the only one of
 * the present modules configured with an API key (via {@code nessy.anthropic.api-key} / {@code
 * nessy.openai.api-key} / {@code nessy.gemini.api-key}), or {@code nessy.provider} names it
 * explicitly. Otherwise {@link #ambiguousModelProvider(Environment)} fails fast, naming the
 * property that resolves the ambiguity.
 *
 * <p>Every bean here also backs off when a {@link Harness} bean is already present
 * ({@code @ConditionalOnMissingBean({ModelProvider.class, Harness.class})} — any listed type
 * present is enough to suppress the bean method). The autoconfigured provider exists solely to feed
 * {@link NessyAutoConfiguration}'s autoconfigured {@code Harness}; an application that supplies its
 * own {@code Harness} has, by construction, already brought its own provider (a {@code Harness}
 * cannot be built without one), so eagerly building — and for {@link #anthropicModelProvider},
 * keylessly failing to build — a second, unused provider here is both wasted work and a spurious
 * startup failure for an app that never asked for this module's provider at all.
 */
@AutoConfiguration
@ConditionalOnClass(AnthropicModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class AnthropicProviderAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(AnthropicIsTheChoiceCondition.class)
  ModelProvider anthropicModelProvider(NessyProperties properties) {
    return buildAnthropicProvider(properties);
  }

  /**
   * Anthropic and OpenAI are both on the classpath, and neither an explicit {@code nessy.provider}
   * nor a single configured API key resolves which provider to use. Owns every ambiguous case that
   * includes OpenAI (OpenAI+Gemini-without-Anthropic is {@link
   * OpenAiProviderAutoConfiguration#ambiguousModelProviderWithoutAnthropic}'s to own instead, so
   * the two beans never both match and race a duplicate definition). {@link
   * #ambiguousProviderMessage} lists exactly the providers actually in conflict.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnClass(name = ProviderProperties.OPENAI_PROVIDER_CLASS_NAME)
  @Conditional(AmbiguousProviderCondition.class)
  ModelProvider ambiguousModelProvider(Environment environment) {
    throw new IllegalStateException(ambiguousProviderMessage(environment));
  }

  /**
   * The remaining ambiguous combination Anthropic itself can see: Gemini present and keyed
   * alongside Anthropic, with OpenAI absent from the classpath entirely (were OpenAI present too,
   * {@link #ambiguousModelProvider(Environment)} above already owns it, since that bean's own
   * {@code @ConditionalOnClass} gate only requires OpenAI's presence, not its keyedness).
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnMissingClass(ProviderProperties.OPENAI_PROVIDER_CLASS_NAME)
  @ConditionalOnClass(name = ProviderProperties.GEMINI_PROVIDER_CLASS_NAME)
  @Conditional(AmbiguousProviderCondition.class)
  ModelProvider ambiguousModelProviderWithoutOpenAi(Environment environment) {
    throw new IllegalStateException(ambiguousProviderMessage(environment));
  }

  /**
   * {@code nessy.provider} names something other than {@code anthropic}, {@code openai}, {@code
   * gemini}, or {@code bedrock} — most likely a typo. Declared here (rather than only in the
   * ambiguous-classpath case above) so it fires whenever this module is on the classpath at all,
   * regardless of which other provider modules ride along; {@link
   * OpenAiProviderAutoConfiguration#invalidProviderModelProvider}, {@link
   * GeminiProviderAutoConfiguration#invalidProviderModelProvider}, and {@link
   * BedrockProviderAutoConfiguration#invalidProviderModelProvider} each cover the remaining gap
   * their own module leaves — an Anthropic-absent classpath — without racing a duplicate bean
   * definition against this one when Anthropic is present.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(InvalidProviderCondition.class)
  ModelProvider invalidProviderModelProvider(NessyProperties properties) {
    throw new IllegalStateException(
        "nessy.provider="
            + properties.provider()
            + " is not a recognized value; expected"
            + " anthropic, openai, gemini, or bedrock");
  }

  /**
   * Builds the exception message naming exactly the providers configured with a {@code
   * nessy.*.api-key} property. {@link AmbiguousProviderCondition} additionally requires classpath
   * presence before counting a provider as contributing to the ambiguity (so a stray {@code
   * nessy.gemini.api-key} on a classpath that never added {@code nessy-model-gemini} can't trigger
   * this bean by itself); this message-builder does not re-check presence, so in that specific
   * contrived case — a property set for a module that isn't actually on the classpath, alongside a
   * genuine ambiguity between two others — the listed options could include one that was never
   * really in play. Accepted as a documented edge case rather than threading a {@link ClassLoader}
   * through every call site for it.
   */
  static String ambiguousProviderMessage(Environment environment) {
    var keyed = new ArrayList<String>();
    if (environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY)) {
      keyed.add("anthropic");
    }
    if (environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY)) {
      keyed.add("openai");
    }
    if (environment.containsProperty(ProviderProperties.GEMINI_KEY_PROPERTY)) {
      keyed.add("gemini");
    }
    var count = keyed.size() == 2 ? "two" : "three";
    return count
        + " model-provider modules are on the classpath; set nessy.provider="
        + String.join("|", keyed);
  }

  /**
   * {@code nessy.anthropic.api-key} / {@code nessy.anthropic.base-url} are overrides layered on top
   * of the SDK's own environment resolution, not replacements for it: {@link
   * AnthropicProviderConfig#fromEnv()} is always called first (it only sets a flag — nothing is
   * read until the provider is built), so every ambient source the SDK understands ({@code
   * ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_BASE_URL}, profile files, workload-identity federation)
   * is still honored when a property here is absent, and an explicit property always wins when
   * present. Building does not throw in a keyless environment as long as an explicit {@code apiKey}
   * was layered on, per {@code fromEnv()}'s own javadoc.
   */
  static ModelProvider buildAnthropicProvider(NessyProperties properties) {
    var anthropic = properties.anthropic();
    var apiKey = anthropic == null ? null : anthropic.apiKey();
    var baseUrl = anthropic == null ? null : anthropic.baseUrl();
    return AnthropicModelProvider.create(
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
   * Matches when Anthropic is the provider this configuration should build.
   *
   * <p><strong>Precedence ruling:</strong> an explicit {@code nessy.*} property is a deliberate
   * nessy-level choice and outranks an ambient SDK environment variable, which is a weaker,
   * incidental signal. Concretely: if only {@code nessy.anthropic.api-key} is set (and neither
   * {@code nessy.openai.api-key} nor {@code nessy.gemini.api-key} is), Anthropic wins even when
   * {@code OPENAI_API_KEY} happens to be present in the environment too — this configuration only
   * inspects the {@code nessy.*} keys, so an env-var-only OpenAI never counts as "keyed" here.
   * "Ambiguous" (see {@link #ambiguousModelProvider(Environment)}) means more than one present
   * provider is explicitly keyed via a {@code nessy.*} property, not that the others could
   * ultimately build — that distinction is deliberate, not an oversight.
   */
  static final class AnthropicIsTheChoiceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy Anthropic Provider Selection");
      var environment = context.getEnvironment();
      var provider = environment.getProperty(ProviderProperties.PROVIDER_PROPERTY);
      if ("anthropic".equals(provider)) {
        return ConditionOutcome.match(message.because("nessy.provider=anthropic"));
      }
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var classLoader = context.getClassLoader();
      var openaiPresent =
          ClassUtils.isPresent(ProviderProperties.OPENAI_PROVIDER_CLASS_NAME, classLoader);
      var geminiPresent =
          ClassUtils.isPresent(ProviderProperties.GEMINI_PROVIDER_CLASS_NAME, classLoader);
      if (!openaiPresent && !geminiPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var anthropicKeyed = environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY);
      var otherKeyed =
          (openaiPresent && environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY))
              || (geminiPresent
                  && environment.containsProperty(ProviderProperties.GEMINI_KEY_PROPERTY));
      if (anthropicKeyed && !otherKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ProviderProperties.ANTHROPIC_KEY_PROPERTY + " is set"));
      }
      return ConditionOutcome.noMatch(
          message.because("multiple provider modules are present and unresolved"));
    }
  }

  /**
   * Matches when two or more present provider modules are each configured with a {@code
   * nessy.*.api-key} property and nothing resolves which one to use.
   */
  static final class AmbiguousProviderCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy Ambiguous Provider Selection");
      var environment = context.getEnvironment();
      var provider = environment.getProperty(ProviderProperties.PROVIDER_PROPERTY);
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var classLoader = context.getClassLoader();
      var keyedCount = 0;
      if (ClassUtils.isPresent(ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME, classLoader)
          && environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY)) {
        keyedCount++;
      }
      if (ClassUtils.isPresent(ProviderProperties.OPENAI_PROVIDER_CLASS_NAME, classLoader)
          && environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY)) {
        keyedCount++;
      }
      if (ClassUtils.isPresent(ProviderProperties.GEMINI_PROVIDER_CLASS_NAME, classLoader)
          && environment.containsProperty(ProviderProperties.GEMINI_KEY_PROPERTY)) {
        keyedCount++;
      }
      if (keyedCount > 1) {
        return ConditionOutcome.match(
            message.because("nessy.provider is unset and multiple providers are keyed"));
      }
      return ConditionOutcome.noMatch(
          message.because("fewer than two present providers are configured with a key"));
    }
  }
}
