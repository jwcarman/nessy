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
package org.jwcarman.nessy.spike.pekko;

import static org.assertj.core.api.Assertions.assertThat;

import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import org.apache.pekko.actor.Address;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THROWAWAY SPIKE, TIER 1. Proving the claim that {@code pekko-remote} on the classpath costs
 * nothing.
 *
 * <p>It IS on tier 1's classpath — {@code pekko-persistence-typed} pulls it in for the
 * serialization machinery, and there is no way to exclude it. That makes "we are not running
 * remoting" a claim worth checking rather than assuming, because a jar that can open a socket is a
 * jar an auditor will ask about.
 *
 * <p>Under {@code provider = local} the provider is {@code LocalActorRefProvider}, whose default
 * address has no host and no port at all — artery is never constructed, so nothing binds and
 * nothing listens. That is what this asserts.
 */
@DisplayName("The single-node tier and remoting")
class SingleNodeRemotingTest {

  @Test
  void pekko_remote_is_on_the_classpath_but_never_opens_a_socket() {
    try (SpikeRuntime runtime =
        new LocalSpikeRuntime(
            ConfigFactory.load("spike-inmemory").resolve(),
            new ScriptedSpikeModel(Duration.ofMillis(1)),
            SpikeSweep.none())) {

      Address address = Adapter.toClassic(runtime.system()).provider().getDefaultAddress();

      assertThat(address.host().isDefined()).as("a local provider binds no host").isFalse();
      assertThat(address.port().isDefined()).as("a local provider binds no port").isFalse();
      assertThat(address.toString()).isEqualTo("pekko://spike");
    }
  }

  @Test
  void the_remoting_classes_are_present_which_is_why_the_test_above_is_worth_having() {
    assertThat(
            Thread.currentThread()
                .getContextClassLoader()
                .getResource("org/apache/pekko/remote/artery/ArteryTransport.class"))
        .as("pekko-remote is an unavoidable transitive of pekko-persistence-typed")
        .isNotNull();
  }
}
