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

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The floor beneath everything else: a sharded entity starts, receives, and answers.
 *
 * <p>Worth its own test because "always sharded" is a decision with a setup cost, and when that
 * setup is wrong the symptom is a message going nowhere in silence. If this passes, a failure
 * anywhere above it is the engine's fault rather than the cluster's.
 */
@DisplayName("A single-node cluster hosting sharded entities")
class ShardingSmokeTest {

  /** Deliberately trivial: this test is about the plumbing, not about any behaviour. */
  record Ping(ActorRef<String> replyTo) {}

  private static final EntityTypeKey<Ping> KEY = EntityTypeKey.create(Ping.class, "smoke");

  private static ActorTestKit testKit;

  @BeforeAll
  static void startCluster() {
    testKit = ClusterOfOne.start();
  }

  @AfterAll
  static void stopCluster() {
    testKit.shutdownTestKit();
  }

  private static Behavior<Ping> echo(String entityId) {
    return Behaviors.receiveMessage(
        ping -> {
          ping.replyTo().tell(entityId);
          return Behaviors.same();
        });
  }

  @Test
  void routes_a_message_to_the_entity_named_by_its_id() {
    ClusterSharding sharding = ClusterSharding.get(testKit.system());
    sharding.init(Entity.of(KEY, context -> echo(context.getEntityId())));
    TestProbe<String> probe = testKit.createTestProbe();

    sharding.entityRefFor(KEY, "house-12").tell(new Ping(probe.ref()));

    assertThat(probe.receiveMessage()).isEqualTo("house-12");
  }

  @Test
  void two_ids_are_two_entities() {
    ClusterSharding sharding = ClusterSharding.get(testKit.system());
    sharding.init(Entity.of(KEY, context -> echo(context.getEntityId())));
    TestProbe<String> probe = testKit.createTestProbe();

    sharding.entityRefFor(KEY, "house-12").tell(new Ping(probe.ref()));
    sharding.entityRefFor(KEY, "house-13").tell(new Ping(probe.ref()));

    assertThat(probe.receiveMessage()).isNotEqualTo(probe.receiveMessage());
  }
}
