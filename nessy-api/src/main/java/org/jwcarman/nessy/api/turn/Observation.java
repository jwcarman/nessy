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
package org.jwcarman.nessy.api.turn;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;

/**
 * What opened a turn, as the model would read it.
 *
 * <p>Turn-shaped rather than entry-shaped: a projection hands out conversations, and how one was
 * stored is nobody else's business.
 */
public record Observation(Seq seq, List<Block.ObservationContent> blocks) {

  public Observation {
    Objects.requireNonNull(blocks, "blocks must not be null");
    if (blocks.isEmpty()) {
      throw new IllegalArgumentException("an observation must have at least one block");
    }
    blocks = List.copyOf(blocks);
  }
}
