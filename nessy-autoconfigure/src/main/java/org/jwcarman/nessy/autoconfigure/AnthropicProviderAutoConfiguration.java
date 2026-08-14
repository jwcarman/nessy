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
 */
@AutoConfiguration
@ConditionalOnClass(AnthropicModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class AnthropicProviderAutoConfiguration {

  static final String OPENAI_PROVIDER_CLASS_NAME =
      "org.jwcarman.nessy.model.openai.OpenAiModelProvider";
  static final String PROVIDER_PROPERTY = "nessy.provider";
  static final String ANTHROPIC_KEY_PROPERTY = "nessy.anthropic.api-key";
  static final String OPENAI_KEY_PROPERTY = "nessy.openai.api-key";

  @Bean
  @ConditionalOnMissingBean(ModelProvider.class)
  @Conditional(AnthropicIsTheChoiceCondition.class)
  ModelProvider anthropicModelProvider(NessyProperties properties) {
    return buildAnthropicProvider(properties);
  }

  /**
   * Both provider modules are on the classpath, and neither an explicit {@code nessy.provider} nor
   * a single configured API key resolves which one to use.
   */
  @Bean
  @ConditionalOnMissingBean(ModelProvider.class)
  @ConditionalOnClass(name = OPENAI_PROVIDER_CLASS_NAME)
  @Conditional(AmbiguousProviderCondition.class)
  ModelProvider ambiguousModelProvider() {
    throw new IllegalStateException(
        "two model-provider modules are on the classpath; set nessy.provider=anthropic|openai");
  }

  static ModelProvider buildAnthropicProvider(NessyProperties properties) {
    var anthropic = properties.anthropic();
    var apiKey = anthropic == null ? null : anthropic.apiKey();
    var builder = AnthropicModelProvider.builder();
    if (StringUtils.hasText(apiKey)) {
      builder.apiKey(apiKey);
    } else {
      builder.fromEnv();
    }
    var baseUrl = anthropic == null ? null : anthropic.baseUrl();
    if (StringUtils.hasText(baseUrl)) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  /** Matches when Anthropic is the provider this configuration should build. */
  static final class AnthropicIsTheChoiceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy Anthropic Provider Selection");
      var environment = context.getEnvironment();
      var provider = environment.getProperty(PROVIDER_PROPERTY);
      if ("anthropic".equals(provider)) {
        return ConditionOutcome.match(message.because("nessy.provider=anthropic"));
      }
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var openaiPresent =
          ClassUtils.isPresent(OPENAI_PROVIDER_CLASS_NAME, context.getClassLoader());
      if (!openaiPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var anthropicKeyed = environment.containsProperty(ANTHROPIC_KEY_PROPERTY);
      var openaiKeyed = environment.containsProperty(OPENAI_KEY_PROPERTY);
      if (anthropicKeyed && !openaiKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ANTHROPIC_KEY_PROPERTY + " is set"));
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
      var provider = environment.getProperty(PROVIDER_PROPERTY);
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      var anthropicKeyed = environment.containsProperty(ANTHROPIC_KEY_PROPERTY);
      var openaiKeyed = environment.containsProperty(OPENAI_KEY_PROPERTY);
      if (anthropicKeyed != openaiKeyed) {
        return ConditionOutcome.noMatch(
            message.because("exactly one provider is configured with a key"));
      }
      return ConditionOutcome.match(
          message.because("nessy.provider is unset and both modules are present"));
    }
  }
}
