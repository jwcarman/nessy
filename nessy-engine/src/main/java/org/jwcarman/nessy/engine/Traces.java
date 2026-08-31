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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Trace context across a mailbox, in Micrometer's vocabulary and nothing else.
 *
 * <p><b>Observations only — no {@code Tracer}, no {@code Propagator}, no {@code Span}.</b> That is
 * not tidiness. Mixing the two APIs gives a process two different notions of "what is current": a
 * span opened through {@code tracer.withSpan} is invisible to {@code
 * ObservationThreadLocalAccessor}, so context stops dead at a thread hop, and a tag written through
 * {@code span.tag} never becomes a metric tag because only LOW-CARDINALITY observation keys do.
 * Both of those were live bugs here before this class was rewritten.
 *
 * <p>What replaces them is Micrometer's own transport pair. A {@link SenderContext} carries context
 * OUT — the handler the tracing bridge installs writes the W3C headers into the carrier, so nothing
 * here knows what a {@code traceparent} is. A {@link ReceiverContext} carries it back IN, and the
 * matching handler makes the extracted parent this observation's parent.
 *
 * <p>Which bridge is underneath — OpenTelemetry, Brave, none at all — is then genuinely not this
 * class's business, and an application with no tracing at all pays for a no-op registry.
 */
public final class Traces {

  private final ObservationRegistry registry;

  /**
   * Tracing switched off: nothing is opened and {@link #capture()} is always empty.
   *
   * <p>An engine with no observability stack still runs — it just says nothing.
   */
  public static Traces noop() {
    return new Traces(ObservationRegistry.NOOP);
  }

  public Traces(ObservationRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
  }

  /**
   * This context, flattened to something a message can carry. Empty when nothing is being traced.
   *
   * <p>{@code what} names the message being sent, because a span called "send" tells a reader
   * nothing. It is the message TYPE and never an id — the protocol is sealed, so the cardinality is
   * bounded by the compiler.
   *
   * <p>A PRODUCER observation, briefly opened and closed, is what fills the carrier: the tracing
   * bridge's sender handler injects on start. Pairing it with the CONSUMER observation {@link
   * #inSpan} opens is what makes the gap between them queue latency — the thing an actor system
   * makes easy to have and hard to see.
   *
   * <p>Callers do not pass a context in. Every receive holds an observation in scope for the
   * duration of its handler, so "current" is already correct at any send site inside an actor —
   * which keeps parentage ambient instead of threading an envelope through by hand.
   */
  public Map<String, String> capture(String what) {
    Map<String, String> headers = new HashMap<>();
    SenderContext<Map<String, String>> sending =
        new SenderContext<>((carrier, key, value) -> carrier.put(key, value), Kind.PRODUCER);
    sending.setCarrier(headers);
    Observation.createNotStarted("send " + what, () -> sending, registry)
        .lowCardinalityKeyValue("messaging.system", "pekko")
        .lowCardinalityKeyValue("messaging.operation.name", "send")
        .lowCardinalityKeyValue("messaging.destination.name", what)
        .observe(() -> {});
    return Map.copyOf(headers);
  }

  /**
   * Run {@code work} in a CONSUMER observation whose parent is whatever {@code carried} names.
   *
   * <p>Carrying headers rather than relying on a thread-local is what makes this survive a hop no
   * captured scope would: the same message works across an executor, and would work across a
   * cluster node, because a {@code traceparent} is a string and a {@code Context} is not.
   */
  public <T> T inSpan(String name, Map<String, String> carried, Supplier<T> work) {
    Map<String, String> headers = carried == null ? Map.of() : carried;
    ReceiverContext<Map<String, String>> received = new ReceiverContext<>(Map::get, Kind.CONSUMER);
    received.setCarrier(headers);
    return Observation.createNotStarted(name, () -> received, registry).observe(work);
  }

  public void inSpan(String name, Map<String, String> carried, Runnable work) {
    inSpan(
        name,
        carried,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * Adds a LOW-CARDINALITY key to whatever observation is current.
   *
   * <p>Low cardinality on purpose: Micrometer turns these into metric tags as well as span
   * attributes, which is the whole reason to write them through the observation rather than onto a
   * span. Anything unbounded — an agent id, a call id — belongs on the span alone and goes through
   * {@link #detail}.
   */
  public void tag(String key, String value) {
    Observation current = registry.getCurrentObservation();
    if (current != null) {
      current.lowCardinalityKeyValue(key, value);
    }
  }

  /** Adds a HIGH-CARDINALITY key: on the span, never on a meter. */
  public void detail(String key, String value) {
    Observation current = registry.getCurrentObservation();
    if (current != null) {
      current.highCardinalityKeyValue(key, value);
    }
  }
}
