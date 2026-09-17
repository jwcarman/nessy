package org.jwcarman.nessy.spring.boot.embedding;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * True when no {@code openai.base-url} is set: the key is OpenAI's own, so OpenAI's own default
 * embedding model is a fact rather than a guess.
 *
 * <p>Written out because Boot's {@code @ConditionalOnProperty} can say what a property must BE and
 * not that it must be absent, and an empty {@code havingValue} means "set to anything but false",
 * which is the opposite of this.
 */
class OnNoOpenAiBaseUrl implements Condition {

  @Override
  public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
    return !StringUtils.hasText(context.getEnvironment().getProperty("openai.base-url"));
  }
}
