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
package org.jwcarman.nessy.console;

import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.engine.Traces;
import org.jwcarman.nessy.model.discovery.ModelDiscovery;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * A terminal agent, from one call in a {@code main}.
 *
 * <pre>{@code
 * public static void main(String[] args) {
 *   Repl.run(config -> config
 *       .banner("nessy chat")
 *       .systemPrompt("You are a concise assistant living in someone's terminal.")
 *       .tool(new DaysUntilTool()));
 * }
 * }</pre>
 *
 * <p>Everything an engine needs is assembled here so an application does not have to: the model
 * comes from {@link ModelDiscovery}, which reads whichever credentials are in the environment; the
 * actor system forms a cluster of one; and state lives in memory.
 *
 * <p><b>Nothing survives the process, deliberately.</b> A conversation typed into a terminal has no
 * reason to outlive the terminal, so the substrate is in memory, agent and turn state go to Pekko's
 * in-memory durable-state store, and reply tokens are minted from an ephemeral key. That is the
 * honest shape for a REPL and the wrong one for anything else — an application that needs to
 * survive a restart is not a console application, and should assemble a {@link PekkoHarnessFactory}
 * itself or use the Spring Boot starter.
 *
 * <p><b>The cluster of one is not ceremony.</b> The engine always shards, and sharding on a node
 * that has not joined leaves entities unreachable — messages go nowhere rather than failing — so
 * this joins itself and waits for {@code Up} before anything is asked of it.
 */
public final class Repl {

  /** Long enough for a slow machine, short enough that a wedged join is not mistaken for a hang. */
  private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

  private Repl() {}

  /**
   * Runs a conversation until the person leaves, then releases everything it built.
   *
   * <p>Returns when the loop ends: an exit word, or end of input.
   *
   * @throws IllegalArgumentException if {@code customizer} is null
   */
  public static void run(ReplCustomizer customizer) {
    java.util.Objects.requireNonNull(customizer, "customizer must not be null");
    ReplConfig config = new ReplConfig();
    customizer.customize(config);
    run(config, ConsoleIo.standard());
  }

  /** The seam a test drives: a configured REPL against a console that need not be real. */
  static void run(ReplConfig config, ConsoleIo io) {
    ModelDiscovery.Selection chosen;
    try {
      chosen = ModelDiscovery.select();
    } catch (IllegalStateException nothingToTalkTo) {
      // Discovery's own message names every provider it knows and the variables each one reads, or
      // says which two are ambiguous. That is the whole useful content of this failure, and a stack
      // trace out of a main would only bury it. Caught around the ONE call that raises it, so a
      // later IllegalStateException from the engine still surfaces in full.
      io.write(nothingToTalkTo.getMessage() + System.lineSeparator());
      io.flush();
      return;
    }
    // Closed in reverse: the engine stops before the gateway it was calling, and the selection owns
    // the vendor's HTTP client, so letting it go is what releases the connection pool.
    try (ModelDiscovery.Selection selection = chosen;
        TerminatingSystem system =
            new TerminatingSystem(
                ActorSystem.create(Behaviors.empty(), "nessy", ConfigFactory.load()))) {
      Harness<String> harness = harness(system.get(), selection, config);
      new ReplLoop(harness, config.agentId(), config, io).run();
    }
  }

  private static Harness<String> harness(
      ActorSystem<Void> system, ModelDiscovery.Selection selection, ReplConfig config) {
    Clock clock = Clock.systemUTC();
    PekkoHarnessFactory factory =
        new PekkoHarnessFactory(
            system,
            new InMemorySubstrate(clock),
            selection.provider(),
            config.maxTokens(),
            Set.of(),
            Executors.newVirtualThreadPerTaskExecutor(),
            clock,
            // Ephemeral, and correct here: a token only has to outlive the process that minted it,
            // and this process IS the conversation.
            ReplyTokens.ephemeral(),
            Traces.noop());
    return factory.createHarness(
        String.class,
        harness -> {
          harness
              .type(config.type())
              .systemPrompt(config.systemPrompt())
              .model(selection.model().id())
              .renderer(UserMessage::of);
          config.tools().forEach(grant -> grant.accept(harness));
        });
  }

  /**
   * An actor system that has joined itself and can be closed.
   *
   * <p>{@link ActorSystem} is not {@link AutoCloseable}, and try-with-resources is how this class
   * promises to release what it built even when the loop throws.
   */
  private record TerminatingSystem(ActorSystem<Void> get) implements AutoCloseable {

    TerminatingSystem {
      Cluster cluster = Cluster.get(get);
      cluster.manager().tell(Join.create(cluster.selfMember().address()));
      awaitUp(cluster);
    }

    @Override
    public void close() {
      get.terminate();
    }

    private static void awaitUp(Cluster cluster) {
      Instant deadline = Instant.now().plus(JOIN_TIMEOUT);
      while (!cluster.selfMember().status().equals(MemberStatus.up())) {
        if (Instant.now().isAfter(deadline)) {
          throw new IllegalStateException(
              "this node never reached Up, so sharding would silently drop every message");
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while forming the cluster", e);
        }
      }
    }
  }
}
