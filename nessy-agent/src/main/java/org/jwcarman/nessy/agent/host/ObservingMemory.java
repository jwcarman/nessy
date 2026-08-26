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
package org.jwcarman.nessy.agent.host;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.Remembrance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Memory} that says how long it took, wrapped around whatever {@link
 * HarnessConfig#memoryFactory(java.util.function.Function)} built (agentic-o11y, James 2026-08-26:
 * "add memory spans"). Applied at the single site the harness builds a per-scope memory, so every
 * recall and every remember is covered — the model executor's recall included, which is the one
 * that decides how big every prompt is.
 *
 * <p><b>Semconv names these operations; we do not.</b> The 2026-08-26 audit found {@code
 * search_memory} and {@code create_memory} in {@code gen_ai.operation.name}'s own enum ("search/
 * query memories from a memory store"; "create new memory records"), so the proposed {@code
 * nessy.memory.recall}/{@code nessy.memory.remember} were never minted. The memory span's name
 * SHOULD be {@code {gen_ai.operation.name}} with no trailing identifier, and semconv defines no
 * duration metric for memory operations, so for these two alone the observation name and the span
 * name coincide. The count rides {@code gen_ai.memory.record.count}, which semconv defines as "the
 * number of memory records relevant to the operation" — for a search, "the number of memory records
 * returned"; for a create, "the number the operation attempted to create".
 *
 * <p><b>Messages, never bytes</b> (ruled 2026-08-26). A byte count would mean re-serializing the
 * whole context on every recall, purely to describe it; {@code gen_ai.usage.input_tokens} on the
 * {@code chat} span is the real size, measured by the party that charges for it.
 *
 * <p><b>Containment.</b> A memory operation must never fail because the thing describing it did:
 * the delegate call runs OUTSIDE the try that guards the instrumentation, its result is what this
 * method returns, and a throwing {@code ObservationHandler} is logged once at {@code WARN} and
 * dropped — the same rule the two executors keep, for the same reason (spec §3.1). A failing
 * DELEGATE, by contrast, propagates exactly as it always did, with {@code error.type} recorded on
 * the way past.
 */
final class ObservingMemory implements Memory {

  private static final Logger LOG = LoggerFactory.getLogger(ObservingMemory.class);

  private static final String SEARCH_MEMORY = "search_memory";
  private static final String CREATE_MEMORY = "create_memory";
  private static final String GEN_AI_OPERATION_NAME = "gen_ai.operation.name";
  private static final String GEN_AI_AGENT_NAME = "gen_ai.agent.name";
  private static final String GEN_AI_MEMORY_RECORD_COUNT = "gen_ai.memory.record.count";
  private static final String ERROR_TYPE = "error.type";

  /**
   * One {@code remember} carries exactly one {@link Remembrance} — one record, by the signature.
   */
  private static final String ONE_RECORD = "1";

  private final Memory delegate;
  private final ObservationRegistry registry;
  private final String agentName;
  private final Supplier<Observation> parentSegment;

  ObservingMemory(
      Memory delegate,
      ObservationRegistry registry,
      String agentName,
      Supplier<Observation> parentSegment) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
    this.parentSegment = Objects.requireNonNull(parentSegment, "parentSegment must not be null");
  }

  @Override
  public void remember(Remembrance remembrance) {
    Observation observation = started(CREATE_MEMORY);
    try {
      delegate.remember(remembrance);
      observation.highCardinalityKeyValue(GEN_AI_MEMORY_RECORD_COUNT, ONE_RECORD);
    } catch (RuntimeException e) {
      failed(observation, e);
      throw e;
    } finally {
      quietly(observation::stop);
    }
  }

  @Override
  public Context recall() {
    Observation observation = started(SEARCH_MEMORY);
    try {
      Context context = delegate.recall();
      observation.highCardinalityKeyValue(
          GEN_AI_MEMORY_RECORD_COUNT, Integer.toString(context.messages().size()));
      return context;
    } catch (RuntimeException e) {
      failed(observation, e);
      throw e;
    } finally {
      quietly(observation::stop);
    }
  }

  private void failed(Observation observation, RuntimeException e) {
    observation.lowCardinalityKeyValue(ERROR_TYPE, e.getClass().getSimpleName());
    quietly(() -> observation.error(e));
  }

  /**
   * Opens one memory span, parented to the scope's open segment (spec §3.2 — Micrometer's own scope
   * does not follow {@code executor.execute} onto the virtual thread a model call runs on). A
   * failed start yields {@link Observation#NOOP}, so the stop and the key-value writes that follow
   * are harmless no-ops rather than a second failure on the same broken handler.
   */
  private Observation started(String operation) {
    try {
      Observation observation =
          Observation.createNotStarted(operation, registry)
              .contextualName(operation)
              .lowCardinalityKeyValue(GEN_AI_OPERATION_NAME, operation)
              .lowCardinalityKeyValue(GEN_AI_AGENT_NAME, agentName)
              // Declared now, overwritten when known: one stable low-cardinality key set per name.
              .lowCardinalityKeyValue(ERROR_TYPE, KeyValue.NONE_VALUE);
      Observation parent = parentSegment.get();
      if (parent != null) {
        observation.parentObservation(parent);
      }
      return observation.start();
    } catch (RuntimeException e) {
      LOG.warn(
          "an observation handler threw starting {}; the memory call is unaffected", operation, e);
      return Observation.NOOP;
    }
  }

  private static void quietly(Runnable instrumentation) {
    try {
      instrumentation.run();
    } catch (RuntimeException e) {
      LOG.warn(
          "an observation handler threw around a memory span; the memory call is unaffected", e);
    }
  }
}
