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

import org.jwcarman.nessy.model.openai.OpenAiModelProvider;
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
 * Wires an OpenAI-backed {@link ModelProvider} when {@code nessy-model-openai} is on the classpath.
 *
 * <p>The ambiguous-classpath / fail-fast case is owned by {@link
 * AnthropicProviderAutoConfiguration#ambiguousModelProvider()} — declaring it here too would race a
 * duplicate {@link ModelProvider} bean definition against that one whenever both provider modules
 * are present. This configuration only needs to recognize when it is unambiguously OpenAI's turn to
 * build the bean: it is the only provider module present, it is explicitly selected via {@code
 * nessy.provider=openai}, or it is the only one of the two configured with an API key.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAiModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class OpenAiProviderAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(ModelProvider.class)
  @Conditional(OpenAiIsTheChoiceCondition.class)
  ModelProvider openAiModelProvider(NessyProperties properties) {
    return buildOpenAiProvider(properties);
  }

  static ModelProvider buildOpenAiProvider(NessyProperties properties) {
    var openai = properties.openai();
    var apiKey = openai == null ? null : openai.apiKey();
    var builder = OpenAiModelProvider.builder();
    if (StringUtils.hasText(apiKey)) {
      builder.apiKey(apiKey);
    } else {
      builder.fromEnv();
    }
    var baseUrl = openai == null ? null : openai.baseUrl();
    if (StringUtils.hasText(baseUrl)) {
      builder.baseUrl(baseUrl);
    }
    return builder.build();
  }

  /**
   * Matches when OpenAI is the provider this configuration should build.
   *
   * <p><strong>Precedence ruling:</strong> an explicit {@code nessy.*} property is a deliberate
   * nessy-level choice and outranks an ambient SDK environment variable, which is a weaker,
   * incidental signal. Concretely: if only {@code nessy.openai.api-key} is set (and {@code
   * nessy.anthropic.api-key} is not), OpenAI wins even when {@code ANTHROPIC_API_KEY} happens to be
   * present in the environment too — this configuration only inspects the {@code nessy.*} keys, so
   * an env-var-only Anthropic never counts as "keyed" here. "Ambiguous" (see {@link
   * AnthropicProviderAutoConfiguration#ambiguousModelProvider()}) means neither side is explicitly
   * keyed via a {@code nessy.*} property, not that neither side could ultimately build — that
   * distinction is deliberate, not an oversight.
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
      var anthropicPresent =
          ClassUtils.isPresent(
              ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME, context.getClassLoader());
      if (!anthropicPresent) {
        return ConditionOutcome.match(message.because("the only model-provider module present"));
      }
      var anthropicKeyed = environment.containsProperty(ProviderProperties.ANTHROPIC_KEY_PROPERTY);
      var openaiKeyed = environment.containsProperty(ProviderProperties.OPENAI_KEY_PROPERTY);
      if (openaiKeyed && !anthropicKeyed) {
        return ConditionOutcome.match(
            message.because("only " + ProviderProperties.OPENAI_KEY_PROPERTY + " is set"));
      }
      return ConditionOutcome.noMatch(
          message.because("both provider modules are present and unresolved"));
    }
  }
}
