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
package org.jwcarman.nessy.engine.inference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.SystemPrompt;

/**
 * The sections added to an agent type's system prompt, and the one text they make with it.
 *
 * <p>Both doors' configs keep one of these and the factory asks it once, when the harness is built:
 * the prompt, then each section in the order it was added, separated by a blank line.
 */
public final class Instructions {

  private final List<String> sections = new ArrayList<>();

  /** Refuses blank text, as {@link SystemPrompt} does. */
  public void add(String text) {
    Objects.requireNonNull(text, "text must not be null");
    if (text.isBlank()) {
      throw new IllegalArgumentException("instructions must not be blank");
    }
    sections.add(text);
  }

  /** The prompt followed by every section, as one text. */
  public SystemPrompt after(SystemPrompt prompt) {
    Objects.requireNonNull(prompt, "prompt must not be null");
    List<String> parts = new ArrayList<>();
    parts.add(prompt.value());
    parts.addAll(sections);
    return new SystemPrompt(String.join("\n\n", parts));
  }
}
