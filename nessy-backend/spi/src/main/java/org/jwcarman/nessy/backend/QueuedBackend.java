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
package org.jwcarman.nessy.backend;

import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * Everything the queued door needs from underneath, chosen together so that they agree.
 *
 * <p><b>Not a {@link DirectBackend}, by ruling.</b> Nothing ever takes a {@code DirectBackend} and
 * hopes to be handed a queued one, so asserting "a queued backend is a kind of direct backend" buys
 * nothing today -- and it would run out of luck the day a third door appears whose needs are not a
 * superset of either. The cost of the two interfaces declaring three signatures in common is the
 * price of not asserting a relationship that nothing needs.
 */
public interface QueuedBackend {

  AgentEvents events();

  Payloads payloads();

  Locks locks();

  Agents agents();

  Effects effects();

  /**
   * One agent type's queue of waiting inputs, made on demand.
   *
   * <p>Takes the input's {@link TypeRef} rather than a {@code Codec<I>} the caller could build
   * itself. The codec for an input type is not the caller's to make: a backend composes it as
   * Jackson with the application's storage transform (compression, encryption) applied after -- and
   * the backlog is the one table the schema itself flags as holding raw user text ("the one place
   * content sits in a control-plane table"), so a caller-supplied codec that bypassed that
   * transform would leave exactly that table unencrypted. The backend builds the codec; the harness
   * hands it only the type.
   */
  <I> Backlogs<I> backlogs(TypeRef<I> inputType);
}
