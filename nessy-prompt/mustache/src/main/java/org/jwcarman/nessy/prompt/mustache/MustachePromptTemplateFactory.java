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
package org.jwcarman.nessy.prompt.mustache;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import java.util.Objects;
import org.jwcarman.nessy.prompt.PromptTemplate;
import org.jwcarman.nessy.prompt.PromptTemplateFactory;
import org.jwcarman.nessy.prompt.PromptVariables;

/**
 * Mustache, over JMustache: {@code {{name}}}, {@code {{#flag}}...{{/flag}}} sections, {@code
 * {{^flag}}} inverted ones. Variables are strings, so a section turns on a value being present and
 * not empty; iteration over lists is not something a {@link PromptVariables} can offer.
 *
 * <p>A hole nothing fills is refused, as JMustache does by default: {@link PromptTemplate#render}
 * throws rather than hand a model the placeholder.
 */
public final class MustachePromptTemplateFactory implements PromptTemplateFactory {

  private final Mustache.Compiler compiler;

  public MustachePromptTemplateFactory() {
    this(Mustache.compiler().emptyStringIsFalse(true));
  }

  /** With a compiler of your own: partials, escaping, delimiters. */
  public MustachePromptTemplateFactory(Mustache.Compiler compiler) {
    this.compiler = Objects.requireNonNull(compiler, "compiler must not be null");
  }

  @Override
  public PromptTemplate compile(String source) {
    Template compiled = compiler.compile(Objects.requireNonNull(source, "source must not be null"));
    return variables -> compiled.execute(asContext(variables));
  }

  /** A JMustache context that asks the variables by name; null is JMustache's "missing". */
  private static Mustache.CustomContext asContext(PromptVariables variables) {
    return name -> variables.variable(name).orElse(null);
  }
}
