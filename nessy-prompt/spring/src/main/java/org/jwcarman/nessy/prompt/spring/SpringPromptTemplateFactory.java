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
package org.jwcarman.nessy.prompt.spring;

import java.util.Objects;
import org.jwcarman.nessy.prompt.PromptTemplate;
import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.PromptVariables;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * The simple engine: Spring's own placeholder syntax, {@code ${name}}, with {@code ${name:default}}
 * for a hole that may go unfilled and {@code \${} for a literal. Pure substitution -- no sections,
 * no loops -- which is most prompts, and nothing beyond spring-core to carry.
 *
 * <p>A hole with neither a value nor a default is refused: {@link PromptTemplate#render} throws
 * rather than hand a model the placeholder.
 */
public final class SpringPromptTemplateFactory implements PromptTemplateFactory {

  private static final PropertyPlaceholderHelper DEFAULT =
      new PropertyPlaceholderHelper("${", "}", ":", '\\', false);

  private final PropertyPlaceholderHelper helper;

  public SpringPromptTemplateFactory() {
    this(DEFAULT);
  }

  /** With delimiters of your own -- {@code {{} and {@code }}}, say. */
  public SpringPromptTemplateFactory(PropertyPlaceholderHelper helper) {
    this.helper = Objects.requireNonNull(helper, "helper must not be null");
  }

  @Override
  public PromptTemplate compile(String source) {
    Objects.requireNonNull(source, "source must not be null");
    return variables -> render(source, variables);
  }

  private String render(String source, PromptVariables variables) {
    return helper.replacePlaceholders(source, name -> variables.variable(name).orElse(null));
  }
}
