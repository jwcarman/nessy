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
package org.jwcarman.nessy.api.event;

import java.util.List;

/** Where an {@link EventSpine} comes from. */
public final class EventSpines {

  private EventSpines() {}

  /**
   * A spine whose frozen tier is exactly {@code declarations}, in order.
   *
   * <p>Delivery per emitted event (design §17): conversation-local subscribers first — only for a
   * {@link ConversationScoped} event, filtered to its own {@link
   * org.jwcarman.nessy.api.conversation.ConversationId} — then the frozen chain, in declaration
   * order (a harness's own declarations first when this spine was built by seeding an agent, since
   * the caller is expected to have already merged them in that order). A throw from a sync
   * listener, in either tier, propagates straight out of {@link EventEmitter#emit} and stops
   * delivery to everything after it — the veto is the throw; an {@link ListenerDeclaration#async
   * async} declaration never gets that power, since its listener already runs off the emitting
   * thread by the time delivery reaches it.
   */
  public static EventSpine of(List<ListenerDeclaration> declarations) {
    return new SynchronousEventSpine(List.copyOf(declarations));
  }
}
