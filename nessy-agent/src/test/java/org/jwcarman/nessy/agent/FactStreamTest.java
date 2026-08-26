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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.memory.VerbatimMemory;
import org.jwcarman.nessy.agent.spi.Backlog;
import org.jwcarman.nessy.agent.spi.HarnessObserver;
import org.jwcarman.nessy.agent.spi.ModelCallExecutor;
import org.jwcarman.nessy.agent.spi.ToolCallExecutor;
import org.jwcarman.nessy.agent.store.AgentStateStore;
import org.jwcarman.nessy.agent.store.SubstrateAgentStateStore;
import org.jwcarman.nessy.agent.support.HarnessTeardown;
import org.jwcarman.nessy.agent.support.NoToolsExecutor;
import org.jwcarman.nessy.agent.support.RecordingHarnessObserver;
import org.jwcarman.nessy.agent.support.TestAgents;
import org.jwcarman.nessy.agent.support.TestMappers;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.turn.Subscription;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

/**
 * The harness's one fact stream (agentic-o11y spec §3): who may subscribe to it, what they are
 * told, and what a badly behaved subscriber can and cannot do to the fold that feeds it.
 */
class FactStreamTest {

  private static final AgentId SCOPE = AgentId.of("prod-eu");

  @AfterEach
  void tearDown() {
    HarnessTeardown.shutdownAllTracked();
  }

  /** A backlog that is a plain queue — enough for one observation to reach the reducer. */
  private static final class QueueBacklog implements Backlog<String> {

    private final Deque<String> queue = new ArrayDeque<>();

    @Override
    public void add(String observation) {
      queue.add(observation);
    }

    @Override
    public Optional<String> poll() {
      return Optional.ofNullable(queue.poll());
    }
  }

  /** A subscriber that throws on every fact it is given. */
  private static final class Saboteur implements HarnessObserver {

    @Override
    public void applied(AgentId id, AgentEvent event, Transition transition) {
      throw new IllegalStateException("this subscriber is broken");
    }

    @Override
    public void ignored(AgentId id, AgentEvent event) {
      throw new IllegalStateException("this subscriber is broken");
    }

    @Override
    public void renderFailed(AgentId id, Object observation, RuntimeException error) {
      throw new IllegalStateException("this subscriber is broken");
    }

    @Override
    public void applyFailed(AgentId id, AgentEvent event, RuntimeException error) {
      throw new IllegalStateException("this subscriber is broken");
    }

    @Override
    public void reFired(AgentId id, List<Effect> effects) {
      throw new IllegalStateException("this subscriber is broken");
    }

    @Override
    public void observationRequeued(AgentId id, Object observation) {
      throw new IllegalStateException("this subscriber is broken");
    }
  }

  /** A harness whose model call does nothing: one {@code Observed} fold is all this file needs. */
  private static Harness<String> harness() {
    ModelCallExecutor silentModel = sink -> {};
    ToolCallExecutor noTools = new NoToolsExecutor();
    AgentStateStore store =
        new SubstrateAgentStateStore(
            new InMemorySubstrate(), SCOPE.value(), Clock.systemUTC(), TestMappers.plainlyPinned());
    return TestAgents.harness(
        new VerbatimMemory(),
        store,
        new QueueBacklog(),
        text -> List.of(new TextBlock(text)),
        silentModel,
        noTools,
        HarnessObserver.noop(),
        false,
        StalenessPolicy.never());
  }

  @Nested
  class Subscribing {

    @Test
    void a_subscriber_is_told_which_scope_each_fact_belongs_to() {
      Harness<String> harness = harness();
      var recorder = new RecordingHarnessObserver();

      try (Subscription subscription = harness.subscribe(recorder)) {
        harness.bind(SCOPE).tell("restart prod-eu");

        assertThat(subscription).isNotNull();
        assertThat(recorder.applied())
            .singleElement()
            .satisfies(
                fact -> {
                  assertThat(fact.id()).isEqualTo(SCOPE);
                  assertThat(fact.event()).isInstanceOf(AgentEvent.Observed.class);
                });
      }
    }

    @Test
    void closing_a_subscription_takes_the_subscriber_off_the_stream() {
      Harness<String> harness = harness();
      var recorder = new RecordingHarnessObserver();
      Subscription subscription = harness.subscribe(recorder);
      subscription.close();

      harness.bind(SCOPE).tell("restart prod-eu");

      assertThat(recorder.applied()).isEmpty();
    }

    /**
     * The default narrator is the stream's first subscriber and is never displaced; the configured
     * observer joins beside it, and a runtime subscriber beside them both. Three, not two, since
     * the watchman branch made {@code harnessObserver(...)} additive.
     */
    @Test
    void the_configured_observer_and_a_subscriber_both_ride_the_one_stream() {
      Harness<String> harness = harness();

      // narrator + configured + Observations
      assertThat(harness.facts().subscriberCount()).isEqualTo(3);
      try (Subscription subscription = harness.subscribe(new RecordingHarnessObserver())) {
        assertThat(subscription).isNotNull();
        assertThat(harness.facts().subscriberCount()).isEqualTo(4);
      }
      assertThat(harness.facts().subscriberCount()).isEqualTo(3);
    }
  }

  @Nested
  class Isolation {

    /**
     * The fold has already committed by the time a fact is published, so a subscriber's throw must
     * never escape into it: an exception here would report a failure for work that actually
     * succeeded and is already in the store.
     */
    @Test
    void a_throwing_subscriber_never_propagates_into_the_fold() {
      Harness<String> harness = harness();
      Agent<String> agent = harness.bind(SCOPE);

      try (Subscription subscription = harness.subscribe(new Saboteur())) {
        assertThat(subscription).isNotNull();
        assertThatCode(() -> agent.tell("restart prod-eu")).doesNotThrowAnyException();
      }
    }

    @Test
    void a_throwing_subscriber_does_not_starve_the_ones_beside_it() {
      Harness<String> harness = harness();
      var recorder = new RecordingHarnessObserver();

      try (Subscription saboteur = harness.subscribe(new Saboteur());
          Subscription watching = harness.subscribe(recorder)) {
        assertThat(saboteur).isNotNull();
        assertThat(watching).isNotNull();
        harness.bind(SCOPE).tell("restart prod-eu");

        assertThat(recorder.applied())
            .singleElement()
            .satisfies(
                fact -> {
                  assertThat(fact.id()).isEqualTo(SCOPE);
                  assertThat(fact.event()).isInstanceOf(AgentEvent.Observed.class);
                });
      }
    }
  }
}
