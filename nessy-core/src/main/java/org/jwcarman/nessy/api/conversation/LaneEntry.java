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
package org.jwcarman.nessy.api.conversation;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.internal.Identifiers;

/**
 * One durable fact laid on the lane: a tell that arrived, or a park that resolved. Both carry a
 * time-ordered {@link #id()} minted the same way {@link ParkToken#generate()} mints its own — the
 * sanctioned api-to-internal precedent.
 *
 * <p>Sealed-grammar etiquette: core switches over this type are exhaustive with no {@code default}
 * arm.
 */
public sealed interface LaneEntry {

  /** This entry's own time-ordered id. */
  String id();

  /** Words interjected: content the agent was told, not yet folded. */
  record Told(String id, List<ContentBlock> content) implements LaneEntry {
    public Told {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(content, "content must not be null");
      content = List.copyOf(content);
    }
  }

  /** Homework that came back: the token it was waiting on, and what arrived. */
  record Resolved(String id, ParkToken token, ToolResolution resolution) implements LaneEntry {
    public Resolved {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(token, "token must not be null");
      Objects.requireNonNull(resolution, "resolution must not be null");
    }
  }

  static Told told(List<ContentBlock> content) {
    Objects.requireNonNull(content, "content must not be null");
    return new Told(Identifiers.next(), content);
  }

  static Resolved resolved(ParkToken token, ToolResolution resolution) {
    Objects.requireNonNull(token, "token must not be null");
    Objects.requireNonNull(resolution, "resolution must not be null");
    return new Resolved(Identifiers.next(), token, resolution);
  }
}
