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
package org.jwcarman.nessy.examples.chatcli;

import com.typesafe.config.ConfigFactory;
import java.time.Clock;
import java.util.concurrent.Executors;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;
import org.jwcarman.nessy.engine.PekkoHarnessFactory;
import org.jwcarman.nessy.engine.ReplyTokens;
import org.jwcarman.nessy.engine.Traces;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * Everything a Nessy application needs, assembled by hand.
 *
 * <p>The Spring starter does all of this and an application never sees it. Doing it explicitly here
 * is the point of a CLI example: it shows that the engine needs an actor system, a substrate, a
 * model provider and somewhere to keep state, and nothing else — no container, no database, no
 * framework.
 *
 * <p><b>The cluster of one is not ceremony.</b> The engine always shards, and sharding on a node
 * that has not joined leaves entities unreachable — messages go nowhere rather than failing. So
 * this joins itself and waits for {@code Up} before handing anything back.
 */
final class Runtime implements AutoCloseable {

  private final ActorSystem<Void> system;
  private final PekkoHarnessFactory factory;

  private Runtime(ActorSystem<Void> system, PekkoHarnessFactory factory) {
    this.system = system;
    this.factory = factory;
  }

  static Runtime start(ModelProvider models, int maxTokens) {
    ActorSystem<Void> system =
        ActorSystem.create(Behaviors.empty(), "nessy", ConfigFactory.load("chat-cli"));
    Cluster cluster = Cluster.get(system);
    cluster.manager().tell(Join.create(cluster.selfMember().address()));
    awaitUp(cluster);
    Clock clock = Clock.systemUTC();
    PekkoHarnessFactory factory =
        new PekkoHarnessFactory(
            system,
            new InMemorySubstrate(clock),
            models,
            maxTokens,
            java.util.Set.of(),
            Executors.newVirtualThreadPerTaskExecutor(),
            clock,
            // Ephemeral, and correct here: a token only has to outlive the process that minted it,
            // and this process IS the conversation.
            ReplyTokens.ephemeral(),
            Traces.noop());
    return new Runtime(system, factory);
  }

  PekkoHarnessFactory factory() {
    return factory;
  }

  private static void awaitUp(Cluster cluster) {
    java.time.Instant deadline = java.time.Instant.now().plusSeconds(30);
    while (!cluster.selfMember().status().equals(MemberStatus.up())) {
      if (java.time.Instant.now().isAfter(deadline)) {
        throw new IllegalStateException("this node never reached Up; sharding would drop messages");
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while forming the cluster", e);
      }
    }
  }

  @Override
  public void close() {
    system.terminate();
  }
}
