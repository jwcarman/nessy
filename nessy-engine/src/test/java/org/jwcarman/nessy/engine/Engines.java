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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.tool.ToolBinding;
import org.jwcarman.nessy.engine.HouseEvents.HouseEvent;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An engine's worth of parts, assembled for a test.
 *
 * <p>There used to be a {@code Turns} factory to stub, which made "an agent with a turn that never
 * finishes" a one-liner. One method does the whole turn now, so a test stubs the MODEL instead --
 * which is closer to the truth anyway: a turn that never finishes is a provider that never answers.
 *
 * <p><b>Two families of {@code of(...)} overload.</b> The ones taking a {@link Dispatcher} wire
 * {@link EffectWorker} alone and leave a test to call {@link EffectWorker#perform} directly,
 * driving one effect at a time against a dispatcher that only ever captures what it was told. The
 * ones below that take no {@link Dispatcher} are the durable pipeline, whole -- {@link Transition},
 * {@link AgentRuntime}, and a continuously running {@link EffectPoller} -- the same parts {@link
 * EngineHarnessFactory#createHarness} assembles, so a test that dispatches a {@link
 * Dispatcher#dispatch} sees a turn actually run rather than commit and stop.
 */
final class Engines {

  private static final Executor BLOCKING =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  private Engines() {}

  /**
   * Everything one agent type needs, over a database nobody else is using.
   *
   * <p>{@code runtime} and {@code poll} are null for a {@code Parts} built from the {@link
   * Dispatcher}-taking overload, which never runs the durable pipeline; {@link #close} is a no-op
   * for one of those.
   */
  record Parts(
      DataSource dataSource,
      Claims claims,
      BacklogStore<HouseEvent> backlog,
      EffectWorker effectWorker,
      Remembered remembered,
      Narrated narrated,
      EffectStore effects,
      AgentStore store,
      AgentRuntime runtime,
      Sweeps poll)
      implements AutoCloseable {

    @Override
    public void close() {
      if (poll != null) {
        poll.close();
      }
    }
  }

  /**
   * What a test's memory was told, per agent.
   *
   * <p>Per AGENT, not per test class: a transcript belongs to one agent, and a double that pooled
   * them would let one test's conversation show up in another's recall — which reads as a bug in
   * the engine rather than in the fixture.
   */
  static final class Remembered {
    private final java.util.Map<String, List<HistoryMessage>> byAgent = new java.util.HashMap<>();

    synchronized void add(AgentId agentId, HistoryMessage message) {
      byAgent.computeIfAbsent(agentId.value(), id -> new ArrayList<>()).add(message);
    }

    synchronized List<HistoryMessage> of(AgentId agentId) {
      return List.copyOf(byAgent.getOrDefault(agentId.value(), List.of()));
    }

    synchronized void clear(AgentId agentId) {
      byAgent.remove(agentId.value());
    }

    synchronized List<HistoryMessage> all() {
      return byAgent.values().stream().flatMap(List::stream).toList();
    }
  }

  /** What a test's narrator was told, per agent — narration belongs to one agent, like memory. */
  static final class Narrated {
    private final java.util.Map<String, List<AgentEvent>> byAgent = new java.util.HashMap<>();

    synchronized void add(AgentId agentId, AgentEvent event) {
      byAgent.computeIfAbsent(agentId.value(), id -> new ArrayList<>()).add(event);
    }

    synchronized List<AgentEvent> of(AgentId agentId) {
      return List.copyOf(byAgent.getOrDefault(agentId.value(), List.of()));
    }

    synchronized List<AgentEvent> all() {
      return byAgent.values().stream().flatMap(List::stream).toList();
    }
  }

  /**
   * {@link EffectWorker} alone, against a {@link Dispatcher} a test supplies -- for driving one
   * effect directly with {@link EffectWorker#perform} and asserting on what the dispatcher was
   * told, rather than on a turn actually running to completion.
   *
   * <p>No {@link AgentRuntime}, no {@link Transition}, no {@link EffectPoller}: {@link
   * Parts#runtime()} and {@link Parts#poll()} are null, and {@link Parts#close} is a no-op.
   */
  static Parts of(
      AgentType type,
      Model model,
      List<ToolBinding<?>> bindings,
      Executor blocking,
      Dispatcher dispatcher) {
    DataSource dataSource = TestDatabase.fresh();
    Claims claims = new Claims(dataSource);
    BacklogStore<HouseEvent> backlog =
        new BacklogStore<>(
            dataSource,
            claims,
            HouseEvents.CODEC,
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            HouseEvents.RENDERER,
            HouseEvents.KEEP_ALL,
            Clock.systemUTC());
    Remembered remembered = new Remembered();
    Narrated narrated = new Narrated();
    EffectStore effects = new EffectStore(dataSource);
    AgentStore store = new AgentStore(dataSource, Clock.systemUTC());
    EffectWorker effectWorker =
        new EffectWorker(
            new EffectWorker.Dependencies(
                type,
                recording(remembered),
                model,
                "you watch a house",
                256,
                new ToolBindings(bindings, EngineMapper.INSTANCE),
                Set.of(),
                agentId -> event -> narrated.add(agentId, event),
                claims,
                ReplyTokens.ephemeral(),
                // A REAL executor, not Runnable::run. Slow work now runs from the agent's own
                // thread rather than a child actor's, so a model that blocks would block the
                // actor — which is the very thing the blocking executor exists to prevent.
                blocking,
                Traces.noop(),
                backlog,
                effects,
                dispatcher,
                store,
                RetryPolicy.exponential(
                    java.time.Duration.ofMillis(1), 2.0, java.time.Duration.ofSeconds(1), 3),
                new java.util.Random(0),
                java.time.Duration.ofDays(30)));
    return new Parts(
        dataSource,
        claims,
        backlog,
        effectWorker,
        remembered,
        narrated,
        effects,
        store,
        null,
        null);
  }

  /**
   * The durable pipeline, whole — {@link Transition}, {@link AgentRuntime} and a continuously
   * running {@link EffectPoller} — wired the same way {@link EngineHarnessFactory#createHarness}
   * wires one kind of agent, but with the pieces a test needs (the transcript a {@link Memory}
   * recorded, every event narrated) kept at hand rather than hidden behind {@code Harness}.
   *
   * <p>{@link Dispatcher#dispatch} drives {@link AgentRuntime} directly, so a turn actually runs
   * rather than committing and stopping.
   */
  static Parts of(AgentType type, Model model) {
    return of(type, model, List.of());
  }

  static Parts of(AgentType type, Model model, List<ToolBinding<?>> bindings) {
    return of(type, model, bindings, BLOCKING);
  }

  static Parts of(AgentType type, Model model, List<ToolBinding<?>> bindings, Executor blocking) {
    DataSource dataSource = TestDatabase.fresh();
    Claims claims = new Claims(dataSource);
    BacklogStore<HouseEvent> backlog =
        new BacklogStore<>(
            dataSource,
            claims,
            HouseEvents.CODEC,
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            HouseEvents.RENDERER,
            HouseEvents.KEEP_ALL,
            Clock.systemUTC());
    Remembered remembered = new Remembered();
    Narrated narrated = new Narrated();
    EffectStore effects = new EffectStore(dataSource);
    AgentStore store = new AgentStore(dataSource, Clock.systemUTC());
    Transition transition =
        new Transition(
            type,
            store,
            effects,
            new TransactionTemplate(new DataSourceTransactionManager(dataSource)));

    // Runtime and effectWorker reference each other, exactly as EngineHarnessFactory#createHarness
    // breaks the same cycle: an explicit holder rather than a lambda over a non-final local.
    AtomicReference<AgentRuntime> runtimeRef = new AtomicReference<>();
    Dispatcher dispatcher =
        (agentId, input, completing, observability) ->
            runtimeRef.get().dispatch(agentId, input, completing, observability);

    EffectWorker effectWorker =
        new EffectWorker(
            new EffectWorker.Dependencies(
                type,
                recording(remembered),
                model,
                "you watch a house",
                256,
                new ToolBindings(bindings, EngineMapper.INSTANCE),
                Set.of(),
                agentId -> event -> narrated.add(agentId, event),
                claims,
                ReplyTokens.ephemeral(),
                blocking,
                Traces.noop(),
                backlog,
                effects,
                dispatcher,
                store,
                RetryPolicy.exponential(Duration.ofMillis(1), 2.0, Duration.ofSeconds(1), 3),
                new Random(0),
                Duration.ofDays(30)));

    AgentRuntime runtime =
        new AgentRuntime(type, transition, effectWorker::perform, blocking, Traces.noop());
    runtimeRef.set(runtime);

    // Effects are durable rows now, committed by Transition and picked up by EffectPoller — there
    // is no actor to run them synchronously any more, so this fixture needs its own continuous
    // poll for a turn to make any progress. A short, unjittered floor keeps a 15-second Awaitility
    // budget comfortable.
    PollSchedule schedule =
        new PollSchedule(Duration.ofMillis(10), Duration.ofMillis(250), 2.0, 0.0, new Random(0));
    EffectPoller poller =
        new EffectPoller(type, effects, runtime, schedule, blocking, 50, Duration.ofSeconds(30));
    Sweeps poll = new Sweeps(() -> schedule.next(poller.pollOnce()));
    poll.start();

    return new Parts(
        dataSource,
        claims,
        backlog,
        effectWorker,
        remembered,
        narrated,
        effects,
        store,
        runtime,
        poll);
  }

  /**
   * Puts an observation where the backlog can see it, and wakes the agent — the same two steps
   * {@code LocalHarness#observe} does, spelled out here because a test wants {@code backlog} and
   * {@code runtime} kept apart.
   */
  static void observe(Parts parts, AgentId agentId, HouseEvent event) {
    parts.backlog().offer(agentId, event);
    parts.runtime().dispatch(agentId, new Input.BacklogUpdated());
  }

  /** A transcript that keeps everything and hands it all back. */
  private static Memory recording(Remembered remembered) {
    return new Memory() {
      @Override
      public Context recall(AgentId agentId) {
        return Context.of(remembered.of(agentId).stream().map(ContextMessage.class::cast).toList());
      }

      @Override
      public void remember(AgentId agentId, HistoryMessage message) {
        remembered.add(agentId, message);
      }

      @Override
      public void forget(AgentId agentId) {
        remembered.clear(agentId);
      }
    };
  }

  /** A provider that accepts the request and never says anything back. */
  static Model stalled() {
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("stalled");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        return new ModelStream() {
          @Override
          public java.util.Iterator<org.jwcarman.nessy.spi.model.ModelEvent> iterator() {
            // Blocks its caller for good, which is the point: the turn never ends. A latch
            // nobody counts down says that exactly, where a ten-minute sleep only said it for
            // ten minutes and left a reader wondering what was special about the number.
            try {
              new java.util.concurrent.CountDownLatch(1).await();
            } catch (InterruptedException _) {
              Thread.currentThread().interrupt();
            }
            return java.util.Collections.emptyIterator();
          }

          @Override
          public void close() {
            // Nothing to release.
          }
        };
      }
    };
  }

  /** A provider that says each scripted thing in turn, then repeats the last. */
  static Model saying(List<org.jwcarman.nessy.api.model.ModelResult> replies) {
    List<org.jwcarman.nessy.api.model.ModelResult> script = List.copyOf(replies);
    java.util.concurrent.atomic.AtomicInteger next =
        new java.util.concurrent.atomic.AtomicInteger();
    return new Model() {
      @Override
      public ModelId id() {
        return ModelId.of("scripted");
      }

      @Override
      public ModelStream stream(ModelRequest request) {
        int index = Math.min(next.getAndIncrement(), script.size() - 1);
        return Scripts.saying(script.get(index));
      }
    };
  }
}
