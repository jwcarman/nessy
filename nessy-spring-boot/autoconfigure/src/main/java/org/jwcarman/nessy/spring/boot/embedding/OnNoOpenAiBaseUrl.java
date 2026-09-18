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
