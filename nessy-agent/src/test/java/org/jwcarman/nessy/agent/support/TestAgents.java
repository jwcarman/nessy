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
package org.jwcarman.nessy.agent.support;

import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.agent.Agent;
import org.jwcarman.nessy.agent.AgentId;
import org.jwcarman.nessy.agent.DefaultAgent;
import org.jwcarman.nessy.agent.Harness;
import org.jwcarman.nessy.agent.Kinds;
import org.jwcarman.nessy.agent.StalenessPolicy;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ObservationRenderer;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentPhaseStore;
import org.jwcarman.nessy.api.agent.AgentType;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.spi.Memory;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

/**
 * A one-scope {@link Harness} over fixed collaborators, for tests that want to wire a {@link
 * DefaultAgent} directly against test doubles — the id-free factories all ignore the raw id and
 * hand back the same fixed instance every time, matching the old {@code AgentWiring}'s one-scope
 * shape.
 */
public final class TestAgents {

  private TestAgents() {}

  public static <O> DefaultAgent<O> wired(
      Memory memory,
      AgentPhaseStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      HarnessObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy) {
    return wired(
        AgentType.of("test"),
        memory,
        store,
        backlog,
        renderer,
        model,
        tools,
        observer,
        drainOnIdle,
        stalenessPolicy);
  }

  /**
   * As {@link #wired(Memory, AgentPhaseStore, Backlog, ObservationRenderer, ModelCallExecutor,
   * ToolCallExecutor, HarnessObserver, boolean, StalenessPolicy)}, but naming {@code type} rather
   * than defaulting it to {@code "test"} — for a fixture whose deliveries/computations carry a
   * DIFFERENT agent type elsewhere (a {@code RegistryToolCallExecutor} constructed with its own
   * {@link AgentType}, say), where the harness-first type-filtered sweep (spec §5) would otherwise
   * treat every one of that fixture's own deliveries as foreign and silently skip them.
   */
  public static <O> DefaultAgent<O> wired(
      AgentType type,
      Memory memory,
      AgentPhaseStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      HarnessObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy) {
    Harness<O> harness =
        harness(
            type,
            memory,
            store,
            backlog,
            renderer,
            model,
            tools,
            observer,
            drainOnIdle,
            stalenessPolicy);
    // harness.bind(id) is the only door outside org.jwcarman.nessy.agent (harness-first spec §4,
    // the Binding demotion) — Harness.bind always returns a DefaultAgent, so this cast is safe;
    // this white-box fixture needs the concrete type for callers that reach for deliver() directly.
    Agent<O> agent = harness.bind(AgentId.of("test-scope"));
    return (DefaultAgent<O>) agent;
  }

  /**
   * The same one-scope harness {@link #wired} builds, returned directly — for tests that also need
   * the harness itself (e.g. to construct a delivery worker against the same fixed collaborators).
   * Defaults the harness's own {@link AgentType} to {@code "test"}.
   *
   * <p>Every {@link Harness} now owns its own life-support (harness-first spec §4) — a delivery
   * worker, daemon-threaded — so this factory synthesizes its own throwaway, private {@link
   * Substrate} for that purpose, entirely decoupled from whatever substrate a caller separately
   * wires {@code memory}/{@code store} against: a caller that constructs its OWN {@code
   * DeliveryWorker} directly (against its own substrate, for deterministic control over sweeps in a
   * test) must never race this harness's own background heartbeat over the same documents.
   */
  public static <O> Harness<O> harness(
      Memory memory,
      AgentPhaseStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      HarnessObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy) {
    return harness(
        AgentType.of("test"),
        memory,
        store,
        backlog,
        renderer,
        model,
        tools,
        observer,
        drainOnIdle,
        stalenessPolicy);
  }

  /**
   * As {@link #harness(Memory, AgentPhaseStore, Backlog, ObservationRenderer, ModelCallExecutor,
   * ToolCallExecutor, HarnessObserver, boolean, StalenessPolicy)}, but naming {@code type} rather
   * than defaulting it to {@code "test"} — see {@link #wired(AgentType, Memory, AgentPhaseStore,
   * Backlog, ObservationRenderer, ModelCallExecutor, ToolCallExecutor, HarnessObserver, boolean,
   * StalenessPolicy)} for why a fixture ever needs this.
   */
  public static <O> Harness<O> harness(
      AgentType type,
      Memory memory,
      AgentPhaseStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      HarnessObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy) {
    return harness(
        type,
        memory,
        store,
        backlog,
        renderer,
        model,
        tools,
        observer,
        drainOnIdle,
        stalenessPolicy,
        ObservationRegistry.NOOP);
  }

  /**
   * As above, but with an {@link ObservationRegistry} of the caller's choosing — a {@code
   * TestObservationRegistry} for the fixtures that assert on what the harness recorded, rather than
   * the {@link ObservationRegistry#NOOP} every other fixture wants.
   */
  public static <O> Harness<O> harness(
      AgentType type,
      Memory memory,
      AgentPhaseStore store,
      Backlog<O> backlog,
      ObservationRenderer<O> renderer,
      ModelCallExecutor model,
      ToolCallExecutor tools,
      HarnessObserver observer,
      boolean drainOnIdle,
      StalenessPolicy stalenessPolicy,
      ObservationRegistry observationRegistry) {
    Substrate lifeSupportSubstrate = new InMemorySubstrate();
    var mapper = TestMappers.plainlyPinned();
    var approvalClient = TestApprovalClients.client(Kinds.approval(type), mapper);
    var toolClient = TestToolClients.client(Kinds.tool(type), mapper);
    Harness<O> harness =
        Harness.of(
            type,
            "test_provider",
            "test-model",
            renderer,
            List.of(observer),
            TurnObserver.noop(),
            drainOnIdle,
            stalenessPolicy,
            rawId -> memory,
            rawId -> store,
            rawId -> backlog,
            (mem, obs) -> model,
            (id, obs) -> tools,
            lifeSupportSubstrate,
            mapper,
            approvalClient,
            toolClient,
            new ConcurrentHashMap<>(),
            observationRegistry,
            new ConcurrentHashMap<>());
    HarnessTeardown.track(harness);
    return harness;
  }
}
