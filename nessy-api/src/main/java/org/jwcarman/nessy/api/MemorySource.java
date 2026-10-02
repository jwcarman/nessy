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
package org.jwcarman.nessy.api;

import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.turn.Turn;

/**
 * Something that has recollections to offer about an agent, asked afresh every time it is called.
 *
 * <p><b>Asked on the dispatcher's thread, off the agent's row lock, once per call to the model.</b>
 * So it may do I/O -- read a table, call a service -- and it should expect to be asked again on the
 * very next call. It returns what is current each time it is asked, and nothing holds an earlier
 * answer for it.
 *
 * <p><b>It is handed the turn being answered</b>, so that it can choose what bears on it: the
 * question is the thing a recollection is relevant to.
 *
 * <p><b>Empty is the right answer for nothing to say.</b> A {@link Memory} with no content is
 * refused precisely so that a source with nothing to add returns none rather than an empty section:
 * a label with nothing under it reads to a model as a claim, where absence is not.
 *
 * <p>Two sources of the same stratum must not offer the same {@link #kind()}. An adapter would
 * write two sections under one label and the model would see a contradiction with no way to tell
 * which is current, so it is refused when the harness is built. The same kind in two different
 * strata is allowed.
 */
public interface MemorySource {

  /**
   * What this source contributes, as a section label. Fixed for the life of the source, because it
   * is what makes two of them a collision.
   */
  String kind();

  /**
   * @param agentId whose memory is being assembled
   * @param current the turn being answered; the newest turn, and the only one that may still be
   *     open
   */
  Optional<Memory> forAgent(AgentId agentId, Turn current);

  /** A memory section that is the same for every agent and every turn. */
  static MemorySource constant(Memory memory) {
    Objects.requireNonNull(memory, "memory must not be null");
    return new MemorySource() {
      @Override
      public String kind() {
        return memory.kind();
      }

      @Override
      public Optional<Memory> forAgent(AgentId agentId, Turn current) {
        return Optional.of(memory);
      }
    };
  }
}
