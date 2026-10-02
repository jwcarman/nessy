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
package org.jwcarman.nessy.engine.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.engine.inference.ContextFingerprint;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.inference.InferenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the model is shown, assembled in one {@code nessy.context} span, with each read
 * beneath it.
 *
 * <p>What this measures is the part of a model call that is not the model: before today it was
 * indistinguishable from the provider's own latency.
 *
 * <p>The span also carries {@code nessy.context.changed}: the earliest stratum of the context that
 * differs from the previous call for the same agent, or {@code first-call} when there was none. Its
 * values are {@code none}, {@code history}, {@code memory}, {@code state}, {@code active-turn} and
 * {@code ambient}. A provider caches a request's leading text, and the context is laid out
 * most-stable-first, so the earliest change decides how much of that cache survives; later changes
 * cost nothing extra. Within a turn the healthy answers are {@code active-turn} and {@code
 * ambient}: {@code memory} or {@code state} in the middle of a turn means a source is not holding
 * still. The same value is logged at DEBUG with the agent type and agent.
 *
 * <p>The last fingerprint for each agent is kept in a bounded map, least recently used out first.
 */
public final class ObservedInferenceContextAssembler {

  public static final String CONTEXT = "nessy.context";

  /** The low-cardinality key naming the earliest stratum that changed since the previous call. */
  public static final String CHANGED = "nessy.context.changed";

  private static final String FIRST_CALL = "first-call";
  private static final int MAX_AGENTS = 10_000;

  private static final Logger log =
      LoggerFactory.getLogger(ObservedInferenceContextAssembler.class);

  private ObservedInferenceContextAssembler() {}

  public static InferenceContextAssembler wrap(
      InferenceContextAssembler delegate, ObservationRegistry observations) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    Objects.requireNonNull(observations, "observations must not be null");
    Map<Whose, ContextFingerprint> last = boundedLastSeen();
    return invocation ->
        observe(
            observations,
            CONTEXT,
            CONTEXT,
            new Identity(invocation.agentType(), invocation.agentId()),
            observation -> {
              InferenceContext context = delegate.assemble(invocation);
              String changed =
                  changeSincePreviousCall(
                      last, new Whose(invocation.agentType(), invocation.agentId()), context);
              observation.lowCardinalityKeyValue(CHANGED, changed);
              log.debug(
                  "context for {} {} changed since the last call: {}",
                  invocation.agentType().value(),
                  invocation.agentId().value(),
                  changed);
              return context;
            });
  }

  private static String changeSincePreviousCall(
      Map<Whose, ContextFingerprint> last, Whose whose, InferenceContext context) {
    ContextFingerprint now = ContextFingerprint.of(context);
    ContextFingerprint previous;
    synchronized (last) {
      previous = last.put(whose, now);
    }
    return previous == null ? FIRST_CALL : now.firstChangeSince(previous);
  }

  /** Access-ordered, so the agents not heard from for longest are the ones forgotten. */
  private static Map<Whose, ContextFingerprint> boundedLastSeen() {
    return new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Whose, ContextFingerprint> eldest) {
        return size() > MAX_AGENTS;
      }
    };
  }

  private record Whose(AgentType agentType, AgentId agentId) {}

  /**
   * Opens one span and runs the work in it. No guard: {@code createNotStarted} answers a no-op
   * registry with a no-op observation, which takes every tag and records nothing -- and a guard at
   * wiring time would be wrong anyway, since a registry with no handlers YET looks no-op.
   */
  private static <T> T observe(
      ObservationRegistry observations,
      String name,
      String spanName,
      Identity whose,
      Function<Observation, T> work) {
    Observation observation =
        Observation.createNotStarted(name, observations).contextualName(spanName);
    if (whose != null) {
      whose.on(observation, null);
    }
    return observation.observe(() -> work.apply(observation));
  }
}
