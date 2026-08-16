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
import org.jwcarman.nessy.model.bedrock.BedrockModelProvider;
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
import org.springframework.util.StringUtils;

/**
 * Wires a Bedrock-backed {@link ModelProvider} when {@code nessy-model-bedrock} is on the classpath
 * — but only when {@code nessy.provider=bedrock} is set explicitly, never by classpath presence
 * alone and never by any {@code nessy.*.api-key} property (there is no {@code
 * nessy.bedrock.api-key}; Bedrock has none).
 *
 * <p>This is the one selection condition among the four provider autoconfigurations that does
 * <em>not</em> mirror its siblings' "sole module present" or "sole keyed" fallbacks (see {@link
 * GeminiProviderAutoConfiguration.GeminiIsTheChoiceCondition} for that shape) — deliberately, per
 * bedrock-provider design §4: AWS credentials are ambient on a large fraction of machines (env
 * vars, shared profile files, container/instance metadata — {@code AWS_REGION} itself is often
 * pre-set by the platform, e.g. AWS Lambda), so letting Bedrock auto-win merely because it is the
 * only provider module on the classpath would risk exactly the silent hijack explicit-only
 * selection exists to prevent. {@link BedrockIsTheChoiceCondition} therefore recognizes exactly one
 * signal: {@code nessy.provider=bedrock}, nothing else. {@link
 * AnthropicProviderAutoConfiguration.AmbiguousProviderCondition}'s keyed-ambiguity logic is
 * correspondingly untouched — Bedrock never counts as "keyed" there, so it never contributes to an
 * ambiguity failure the way Anthropic/OpenAI/Gemini do.
 *
 * <p>The bean here also backs off when a {@link Harness} bean is already present
 * ({@code @ConditionalOnMissingBean({ModelProvider.class, Harness.class})} — any listed type
 * present is enough to suppress the bean method), the same convention every sibling provider
 * autoconfiguration follows.
 */
@AutoConfiguration
@ConditionalOnClass(BedrockModelProvider.class)
@EnableConfigurationProperties(NessyProperties.class)
public class BedrockProviderAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @Conditional(BedrockIsTheChoiceCondition.class)
  ModelProvider bedrockModelProvider() {
    return BedrockModelProvider.builder().fromEnv().build();
  }

  /**
   * {@code nessy.provider} names something other than {@code anthropic}, {@code openai}, {@code
   * gemini}, or {@code bedrock} — most likely a typo. {@link
   * AnthropicProviderAutoConfiguration#invalidProviderModelProvider}, {@link
   * OpenAiProviderAutoConfiguration#invalidProviderModelProvider}, and {@link
   * GeminiProviderAutoConfiguration#invalidProviderModelProvider} already cover every classpath
   * where any of those three modules is present; this bean fills the one gap that leaves — a
   * Bedrock-only classpath (Anthropic, OpenAI, and Gemini all absent) — gated on all three's
   * absence so no two of the four ever match at once and race a duplicate bean definition.
   */
  @Bean
  @ConditionalOnMissingBean({ModelProvider.class, Harness.class})
  @ConditionalOnMissingClass({
    ProviderProperties.ANTHROPIC_PROVIDER_CLASS_NAME,
    ProviderProperties.OPENAI_PROVIDER_CLASS_NAME,
    ProviderProperties.GEMINI_PROVIDER_CLASS_NAME
  })
  @Conditional(InvalidProviderCondition.class)
  ModelProvider invalidProviderModelProvider(NessyProperties properties) {
    throw new IllegalStateException(
        "nessy.provider="
            + properties.provider()
            + " is not a recognized value; expected"
            + " anthropic, openai, gemini, or bedrock");
  }

  /**
   * Matches only when {@code nessy.provider=bedrock} is set explicitly — see the class javadoc for
   * why this condition, alone among the four providers' selection conditions, has no "sole module
   * present" or "sole keyed" fallback arm.
   */
  static final class BedrockIsTheChoiceCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(
        ConditionContext context, AnnotatedTypeMetadata metadata) {
      var message = ConditionMessage.forCondition("Nessy Bedrock Provider Selection");
      var provider = context.getEnvironment().getProperty(ProviderProperties.PROVIDER_PROPERTY);
      if ("bedrock".equals(provider)) {
        return ConditionOutcome.match(message.because("nessy.provider=bedrock"));
      }
      if (StringUtils.hasText(provider)) {
        return ConditionOutcome.noMatch(message.because("nessy.provider=" + provider));
      }
      return ConditionOutcome.noMatch(message.because("nessy.provider is unset"));
    }
  }
}
