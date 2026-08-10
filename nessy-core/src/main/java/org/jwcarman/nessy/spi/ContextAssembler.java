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
package org.jwcarman.nessy.spi;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.event.EventHub;
import org.jwcarman.nessy.api.event.RecallFailed;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.SessionState;
import org.jwcarman.nessy.internal.EngineObservations;
import org.jwcarman.nessy.spi.context.ContextBuilder;
import org.jwcarman.nessy.spi.memory.Memory;

/**
 * One implementation of "what would the model see": projects {@link SessionState} through a {@link
 * ContextBuilder}, then — unless {@link #memory} is the {@link Memory#NONE} singleton, checked by
 * identity so the default path allocates and observes nothing — gives {@link #memory} a chance to
 * prepend recalled messages ahead of that projection.
 *
 * <p>Concrete machinery, not an extension seam — nothing here is meant to be implemented by SPI
 * consumers, only constructed and called — so it lives beside {@link InProcessEngine} and {@link
 * ExecutionEngine} in the bare {@code spi} package rather than {@code internal}: {@code
 * org.jwcarman.nessy.Agent} and {@code org.jwcarman.nessy.AgentBuilder}, in the root-package front
 * door, need a reference to it, and the front door may depend on {@code api}/{@code spi} but never
 * {@code internal} (see {@code ZoneBoundariesTest}).
 *
 * <p>An agent has exactly one assembler instance, constructed once at {@code AgentBuilder.build()}
 * time from that agent's {@code ContextBuilder} and {@code Memory} choices, and shared by every
 * consumer that needs to answer "what would this call see" — {@link InProcessEngine#requestFor} at
 * every conversational request, and {@code Agent.contextFor} on demand. Sharing the instance
 * (rather than each consumer re-deriving the same choreography) is what keeps the two answers from
 * ever drifting apart.
 *
 * <p>Recall runs under its own {@code nessy.memory.recall} observation, matching the F2 convention
 * used everywhere else in the engine: a caught failure marks the observation with {@link
 * Observation#error(Throwable)} rather than letting it escape, and the request proceeds with no
 * recalled messages. Recall is enrichment, never the turn — a memory that throws, or that hands
 * back messages breaking {@link Context}'s tool-pairing invariant (caught by {@link Context#of}
 * inside the same {@code try}), costs this one assembly its enrichment, not the caller.
 */
public final class ContextAssembler {

  private final ContextBuilder contextBuilder;
  private final Memory memory;
  private final EventHub hub;
  private final ObservationRegistry observations;

  public ContextAssembler(
      ContextBuilder contextBuilder,
      Memory memory,
      EventHub hub,
      ObservationRegistry observations) {
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder must not be null");
    this.memory = Objects.requireNonNull(memory, "memory must not be null");
    this.hub = Objects.requireNonNull(hub, "hub must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /**
   * Assembles the {@link Context} one call against {@code state} sees. {@code state} carries both
   * the messages to project and the session id ({@link SessionState#id()}) that names the session
   * for the {@link RecallFailed} event a failed recall emits.
   */
  public Context assemble(SessionState state) {
    Context projected = contextBuilder.project(state);
    if (memory != Memory.NONE) {
      Observation observation = EngineObservations.recall(observations);
      try (var _ = observation.openScope()) {
        List<Message> recalled = memory.recall(state);
        projected = Context.of(concat(recalled, projected.messages()));
      } catch (RuntimeException e) {
        observation.error(e);
        hub.emit(new RecallFailed(state.id(), describe(e)));
      } finally {
        observation.stop();
      }
    }
    return projected;
  }

  private static List<Message> concat(List<Message> head, List<Message> tail) {
    List<Message> combined = new ArrayList<>(head.size() + tail.size());
    combined.addAll(head);
    combined.addAll(tail);
    return combined;
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + message;
  }
}
