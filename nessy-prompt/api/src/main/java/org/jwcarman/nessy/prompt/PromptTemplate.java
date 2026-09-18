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

/**
 * A prompt with holes in it, compiled once and rendered as often as it is needed.
 *
 * <p>What the holes look like is the engine's business -- {@code ${name}} for the Spring flavour,
 * {@code {{name}}} for a Mustache -- and so is what happens to a hole nothing fills: the engines
 * this project ships refuse, loudly, rather than send a model a prompt with a hole still in it.
 */
@FunctionalInterface
public interface PromptTemplate {

  String render(PromptVariables variables);
}
