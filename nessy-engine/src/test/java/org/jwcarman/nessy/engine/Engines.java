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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.apache.pekko.actor.typed.ActorSystem;
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
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * An engine's worth of parts, assembled for a test.
 *
 * <p>There used to be a {@code Turns} factory to stub, which made "an agent with a turn that never
 * finishes" a one-liner. One actor does the whole turn now, so a test stubs the MODEL instead —
 * which is closer to the truth anyway: a turn that never finishes is a provider that never answers.
 */
final class Engines {

  private static final Executor BLOCKING =
      java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  private Engines() {}

  /** Everything one agent type needs, over a database nobody else is using. */
  record Parts(
      DataSource dataSource,
      Claims claims,
      BacklogStore<HouseEvent> backlog,
      Instructions instructions,
      Remembered remembered,
      Narrated narrated) {}

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

  static Parts of(ActorSystem<?> system, AgentType type, Model model) {
    return of(system, type, model, List.of());
  }

  static Parts of(
      ActorSystem<?> system, AgentType type, Model model, List<ToolBinding<?>> bindings) {
    return of(system, type, model, bindings, BLOCKING);
  }

  /**
   * The same, with the blocking executor handed in.
   *
   * <p>Instruction batches are one task each on this executor, so a test that owns it owns the
   * order they run in — which is the only way to reproduce a race between two batches on purpose
   * rather than one run in five.
   */
  static Parts of(
      ActorSystem<?> system,
      AgentType type,
      Model model,
      List<ToolBinding<?>> bindings,
      Executor blocking) {
    return of(system, type, model, bindings, blocking, memory -> memory);
  }

  /**
   * The same, with the memory wrappable.
   *
   * <p>Forgetting deletes memory, backlog and claims in that order, and the window a racing write
   * lands in is BETWEEN them. A test that wants that window on purpose has to be able to stop the
   * world inside the memory, which is what this is for.
   */
  static Parts of(
      ActorSystem<?> system,
      AgentType type,
      Model model,
      List<ToolBinding<?>> bindings,
      Executor blocking,
      java.util.function.UnaryOperator<Memory> wrapping) {
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
    Instructions instructions =
        new Instructions(
            system,
            new Instructions.Dependencies(
                type,
                wrapping.apply(recording(remembered)),
                model,
                "you watch a house",
                256,
                new ToolBindings(bindings, EngineMapper.INSTANCE),
                Set.of(),
                agentId -> event -> narrated.add(agentId, event),
                claims,
                new Reminders(dataSource),
                ReplyTokens.ephemeral(),
                // A REAL executor, not Runnable::run. Slow work now runs from the agent's own
                // thread rather than a child actor's, so a model that blocks would block the
                // actor — which is the very thing the blocking executor exists to prevent.
                blocking,
                Traces.noop(),
                backlog));
    return new Parts(dataSource, claims, backlog, instructions, remembered, narrated);
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
            // Blocks its caller for good, which is the point: the turn never ends.
            try {
              Thread.sleep(java.time.Duration.ofMinutes(10));
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
