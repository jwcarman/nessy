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
import org.jwcarman.nessy.model.anthropic.AnthropicModelProvider;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * Wires an Anthropic-backed {@link ModelProvider} when {@code nessy-model-anthropic} is on the
 * classpath.
 *
 * <p>If {@code nessy-model-openai} is <em>also</em> present, this class only builds the bean when
 * Anthropic is the unambiguous choice: it is the only one of the two configured with an API key
 * (via {@code nessy.anthropic.api-key} / {@code nessy.openai.api-key}), or {@code nessy.provider}
 * names it explicitly. Otherwise {@link #ambiguousModelProvider()} fails fast, naming the property
 * that resolves the ambiguity.
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
   * Both provider modules are on the classpath, and neither an explicit {@code nessy.provider} nor
   * a single configured API key resolves which one to use.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnClass(name = ProviderProperties.OPENAI_PROVIDER_CLASS_NAME)
  @Conditional(AmbiguousProviderCondition.class)
  ModelProvider ambiguousModelProvider() {
    throw new IllegalStateException(
        "two model-provider modules are on the classpath; set nessy.provider=anthropic|openai");
  }

  /**
   * {@code nessy.provider} names something other than {@code anthropic} or {@code openai} — most
   * likely a typo. Declared here (rather than only in the ambiguous-classpath case above) so it
   * fires whenever this module is on the classpath at all, single-jar or both-jars; {@link
   * OpenAiProviderAutoConfiguration#invalidProviderModelProvider} covers the remaining case, an
   * OpenAI-only classpath, without racing a duplicate bean definition against this one when both
   * jars are present.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(InvalidProviderCondition.class)
  ModelProvider invalidProviderModelProvider(NessyProperties properties) {
    throw new IllegalStateException(
        "nessy.provider="
            + properties.provider()
            + " is not a recognized value; expected"
            + " anthropic or openai");
  }

  /**
   * {@code nessy.anthropic.api-key} / {@code nessy.anthropic.base-url} are overrides layered on top
   * of the SDK's own environment resolution, not replacements for it: {@link
   * AnthropicModelProvider.Builder#fromEnv()} is always called first (it only sets a flag — nothing
   * is read until {@code build()}), so every ambient source the SDK understands ({@code
   * ANTHROPIC_AUTH_TOKEN}, {@code ANTHROPIC_BASE_URL}, profile files, workload-identity federation)
   * is still honored when a property here is absent, and an explicit property always wins when
   * present. {@link AnthropicModelProvider.Builder#build()} does not throw in a keyless environment
   * as long as an explicit {@code apiKey} was layered on, per {@code fromEnv()}'s own javadoc.
   */
  static ModelProvider buildAnthropicProvider(NessyProperties properties) {
    var anthropic = properties.anthropic();
    var builder = AnthropicModelProvider.builder().fromEnv();
    var apiKey = anthropic == null ? null : anthropic.apiKey();
    if (StringUtils.hasText(apiKey)) {
      builder.apiKey(apiKey);
    }
    var baseUrl = anthropic == null ? null : anthropic.baseUrl();
    if (StringUtils.hasText(baseUrl)) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  /**
   * Matches when Anthropic is the provider this configuration should build.
   *
   * <p><strong>Precedence ruling:</strong> an explicit {@code nessy.*} property is a deliberate
   * nessy-level choice and outranks an ambient SDK environment variable, which is a weaker,
   * incidental signal. Concretely: if only {@code nessy.anthropic.api-key} is set (and {@code
   * nessy.openai.api-key} is not), Anthropic wins even when {@code OPENAI_API_KEY} happens to be
   * present in the environment too — this configuration only inspects the {@code nessy.*} keys, so
   * an env-var-only OpenAI never counts as "keyed" here. "Ambiguous" (see {@link
   * #ambiguousModelProvider()}) means neither side is explicitly keyed via a {@code nessy.*}
   * property, not that neither side could ultimately build — that distinction is deliberate, not an
   * oversight.
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
      var openaiPresent =
          ClassUtils.isPresent(
              ProviderProperties.OPENAI_PROVIDER_CLASS_NAME, context.getClassLoader());
      if (!openaiPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var anthropicKeyed = environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY);
      var openaiKeyed = environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY);
      if (anthropicKeyed && !openaiKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ProviderProperties.ANTHROPIC_KEY_PROPERTY + " is set"));
      }
      return ConditionOutcome.noMatch(
          message.because("both provider modules are present and unresolved"));
    }
  }

  /** Matches when both provider modules are present and nothing resolves which one to use. */
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
      var anthropicKeyed = environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY);
      var openaiKeyed = environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY);
      if (anthropicKeyed != openaiKeyed) {
        return ConditionOutcome.noMatch(
            message.because("exactly one provider is configured with a key"));
      }
      return ConditionOutcome.match(
          message.because("nessy.provider is unset and both modules are present"));
    }
  }
}
