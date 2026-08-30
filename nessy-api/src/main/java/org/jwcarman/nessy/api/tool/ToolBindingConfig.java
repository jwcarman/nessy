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
package org.jwcarman.nessy.api.tool;

/**
 * How one tool is bound to a kind of agent: who may say no, and how to explain a call of it to a
 * person.
 *
 * <p>Both are the application's statements ABOUT the tool, never the tool's about itself. That is
 * what makes a third-party tool governable without wrapping it in a class: whoever wrote the remote
 * server does not get to author the sentence a human approves against.
 *
 * <p>Mutable during customization, read once afterwards. Neither call is required — an unbound
 * approver is {@link Approver#always()} and an unbound describer is {@link
 * ToolDescriber#byToString()}.
 *
 * @param <I> the tool's bound input
 */
public interface ToolBindingConfig<I> {

  /** Who decides whether a call of this tool may run. Defaults to {@link Approver#always()}. */
  ToolBindingConfig<I> approver(Approver approver);

  /**
   * How a call of this tool reads to a person — an approval page, a chat UI, a log line. Defaults
   * to {@link ToolDescriber#byToString()}, which is fine for narration and poor for consent.
   */
  ToolBindingConfig<I> describer(ToolDescriber<I> describer);
}
