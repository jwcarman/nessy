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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when every named property is set to something other than whitespace.
 *
 * <p>Boot's {@code @ConditionalOnProperty} counts a property as set when it is present at all, and
 * an unset environment variable read through a placeholder with an empty default -- {@code
 * ${CHAT_EMBEDDING_MODEL:}} -- is present and empty. That is the difference between ranking by
 * recency and building an embedder on a blank model name, so this package asks for text rather than
 * for presence. The other half of the pair is {@link OnNoOpenAiBaseUrl}, which has always asked the
 * same question in the negative.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnConfiguredProperty.class)
@interface ConditionalOnConfiguredProperty {

  /** The properties that must all name something. */
  String[] value();
}
