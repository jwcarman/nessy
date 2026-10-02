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
package org.jwcarman.nessy.prompt;

import java.util.Objects;
import org.jwcarman.nessy.api.SystemPrompt;

/**
 * A system prompt written as a template, rendered once into the text a harness is built with.
 *
 * <p>Once, because a system prompt cannot change from call to call: it is the head of every
 * request, and a provider caches a request's leading text, so a prompt that said something
 * different each time would invalidate everything cached for every agent of the type. What varies
 * by agent belongs in a state source, and what varies by the moment in an ambient source. An engine
 * that cannot fill a hole throws, here and now, at the point the prompt is rendered -- a harness
 * fails to build rather than a model being handed {@code ${name}} and left to guess.
 */
public final class TemplatedSystemPrompt {

  private TemplatedSystemPrompt() {}

  public static SystemPrompt render(PromptTemplate template, PromptVariables variables) {
    Objects.requireNonNull(template, "template must not be null");
    Objects.requireNonNull(variables, "variables must not be null");
    return new SystemPrompt(template.render(variables));
  }

  public static SystemPrompt render(
      PromptTemplateFactory engine, String source, PromptVariables variables) {
    Objects.requireNonNull(engine, "engine must not be null");
    return render(engine.compile(source), variables);
  }
}
