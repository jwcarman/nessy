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
package org.jwcarman.nessy.engine.harness;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What an input is called when it starts a turn, worked out the same way on both doors.
 *
 * <p>The application's {@link Stringifier} when it gave one, made one line and cut to {@link
 * ToolConfig#LINE_CAP} characters; the input's simple class name when it did not, when the
 * stringifier throws, and when it returns null or a blank string (the full class name for a class
 * with no simple name, such as an anonymous one). A label never fails a turn, so a stringifier that
 * throws is told as a warning naming the agent type and the turn goes on. An application that gave
 * none has chosen the default, and is not warned.
 *
 * @param <I> what a caller hands in
 */
public final class InputLabels<I> {

  private static final Logger LOG = LoggerFactory.getLogger(InputLabels.class);

  private final AgentType agentType;
  private final Optional<Stringifier<I>> configured;

  public InputLabels(AgentType agentType, Optional<Stringifier<I>> configured) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.configured =
        Objects.requireNonNull(configured, "configured must not be null")
            .map(label -> label.dropTail(ToolConfig.LINE_CAP));
  }

  /** The label for {@code input}, never null and never blank. */
  public String of(I input) {
    if (configured.isPresent()) {
      try {
        String label = configured.get().stringify(input);
        if (label != null && !label.isBlank()) {
          return label;
        }
      } catch (RuntimeException e) {
        LOG.warn(
            "[{}] the input label failed ({}); the input's class name is used",
            agentType.value(),
            e.getClass().getName());
      }
    }
    String simple = input.getClass().getSimpleName();
    return simple.isBlank() ? input.getClass().getName() : simple;
  }
}
