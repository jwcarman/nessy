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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;

/**
 * A single-node cluster, up and ready.
 *
 * <p>The engine always shards, so a test system has to be a real cluster member before anything
 * will start. The address is not known until remoting binds (the port is 0), so the node joins
 * ITSELF programmatically rather than naming itself in {@code seed-nodes}.
 *
 * <p>Waiting for {@code Up} is not optional: {@code ClusterSharding.init} on a node that has not
 * joined leaves entities unreachable, and the failure looks like a message quietly going nowhere.
 */
final class ClusterOfOne {

  private ClusterOfOne() {}

  static ActorTestKit start() {
    ActorTestKit testKit = ActorTestKit.create(ConfigFactory.load("engine-test"));
    Cluster cluster = Cluster.get(testKit.system());
    cluster.manager().tell(Join.create(cluster.selfMember().address()));
    await()
        .atMost(20, SECONDS)
        .until(() -> cluster.selfMember().status().equals(MemberStatus.up()));
    return testKit;
  }
}
