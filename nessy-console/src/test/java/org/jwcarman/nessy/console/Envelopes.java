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
package org.jwcarman.nessy.console;

import java.time.Instant;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Seq;

/** An event as a listener is handed it: a story event at a place in the story, a signal alone. */
final class Envelopes {

  private Envelopes() {}

  static Narrated of(AgentType type, AgentId agent, Narration event) {
    return switch (event) {
      case Narration.Story story -> Narrated.story(type, agent, story, new Seq(1), Instant.EPOCH);
      case Narration.Live live -> Narrated.live(type, agent, live);
    };
  }
}
