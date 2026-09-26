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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.SystemPromptSource;

/**
 * A system prompt that is a template, rendered for the agent about to be asked, from sources of
 * variables asked in order.
 *
 * <p>Rendered on every call rather than once: the point of a per-agent prompt is that it can say
 * something different tomorrow, or for the next agent. An engine that cannot fill a hole throws,
 * and the throw reaches the inference effect -- a turn fails loudly rather than a model being
 * handed {@code ${name}} and left to guess.
 */
public final class TemplatedSystemPrompt implements SystemPromptSource {

  private final PromptTemplate template;
  private final PromptVariableSource variables;

  private TemplatedSystemPrompt(PromptTemplate template, PromptVariableSource variables) {
    this.template = Objects.requireNonNull(template, "template must not be null");
    this.variables = Objects.requireNonNull(variables, "variables must not be null");
  }

  public static SystemPromptSource of(PromptTemplate template, PromptVariableSource... sources) {
    return new TemplatedSystemPrompt(template, PromptVariableSource.firstOf(List.of(sources)));
  }

  public static SystemPromptSource of(
      PromptTemplateFactory engine, String source, PromptVariableSource... sources) {
    return of(Objects.requireNonNull(engine, "engine must not be null").compile(source), sources);
  }

  @Override
  public SystemPrompt forAgent(AgentId agentId) {
    return new SystemPrompt(template.render(variables.forAgent(agentId)));
  }
}
