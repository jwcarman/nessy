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
package org.jwcarman.nessy.engine;

import java.util.function.Consumer;
import org.jwcarman.nessy.api.AgentEvent;

/**
 * Where an actor sends the story of what it is doing.
 *
 * <p>A one-method seam rather than an {@code ActorRef} passed around, so the actors that narrate do
 * not have to know that narration is an actor at all — and so a test can hand them a list.
 *
 * <p>Narration is at-least-once and never transactional with the record: a retried segment narrates
 * twice, with different event ids, because ids are minted at emit. Consumers dedupe by the event's
 * natural key.
 */
@FunctionalInterface
public interface Narrator {

  void narrate(AgentEvent event);

  /** Nobody is listening, and nothing is lost by saying so. */
  static Narrator silent() {
    return event -> {};
  }

  static Narrator to(Consumer<AgentEvent> sink) {
    return sink::accept;
  }
}
