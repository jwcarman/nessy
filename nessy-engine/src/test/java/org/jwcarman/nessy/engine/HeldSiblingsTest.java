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

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.HistoryMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.testing.TestDatabase;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C2: a retried effect used to be overtaken by its own siblings, losing a turn in silence.
 *
 * <p>{@code endTurn} emits {@code Remember.Answer}, then {@code Release}, in the SAME group. If
 * {@code Remember.Answer} throws, {@code settle} catches it, schedules a retry, and used to return
 * normally -- letting {@code EffectPoller}'s per-agent loop carry straight on to {@code Release},
 * which deletes the very claim the retried {@code Remember} still needs. Wired against a REAL
 * {@link EffectWorker} and {@link AgentRuntime}, deliberately -- {@link EffectPollerTest} tests
 * grouping and sequencing against a fake performer, but this defect is specifically about what a
 * real {@code settle} failure does to the rows after it, which a fake performer cannot reproduce
 * honestly. No Pekko cluster is needed: {@link AgentRuntime} is its own {@link Dispatcher}, so it
 * is wired to itself directly.
 */
@DisplayName("A retried effect and its siblings")
class HeldSiblingsTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final String ANSWER_CLAIM_KEY = "answer"; // mirrors EffectWorker's private key

  private EmbeddedDatabase database;
  private Claims claims;
  private EffectStore effects;
  private AgentStore store;
  private Transition transition;
  private final List<HistoryMessage> remembered = new ArrayList<>();
  private final Codec<AnswerMessage> answerCodec =
      JsonCodec.of(EngineMapper.INSTANCE, AnswerMessage.class);

  @BeforeEach
  void fresh() {
    database = TestDatabase.fresh();
    claims = new Claims(database);
    effects = new EffectStore(database);
    store = new AgentStore(database);
    transition =
        new Transition(
            TYPE,
            store,
            effects,
            new TransactionTemplate(new DataSourceTransactionManager(database)));
  }

  @AfterEach
  void close() {
    database.shutdown();
  }

  @Test
  @DisplayName(
      "a failed effect holds back its siblings -- Release does not run in the same pass, and the"
          + " exchange still reaches the transcript once the retry succeeds")
  void a_failed_effect_holds_back_its_siblings() {
    TurnId turnId = TurnId.of("turn-1");
    transition.read(AGENT); // conjures the idle nessy_agent row -- see EffectPollerTest#agent
    claims.put(
        AGENT,
        turnId,
        ANSWER_CLAIM_KEY,
        answerCodec.encode(new AnswerMessage(List.of(new TextBlock("hi")))));
    effects.insert(
        TYPE,
        AGENT,
        turnId,
        null,
        0,
        EffectStore.PAYLOADS.encode(new Effect.Remember.Answer()),
        null);
    effects.insert(
        TYPE, AGENT, turnId, null, 1, EffectStore.PAYLOADS.encode(new Effect.Release()), null);

    AtomicBoolean failedOnce = new AtomicBoolean(false);
    EffectPoller poller = poller(throwOnceThenRecord(failedOnce));

    int firstPass = poller.pollOnce();

    assertThat(firstPass).isEqualTo(2);
    assertThat(claims.get(AGENT, turnId, ANSWER_CLAIM_KEY))
        .as("Release must not have run in this pass -- the claim Remember still needs survives")
        .isPresent();
    assertThat(remembered).as("the exchange was not written on the failed pass").isEmpty();

    // The retried Remember's own backoff (RetryPolicy.exponential base=1ms) -- generous, so this
    // sleep is not itself a source of flakiness.
    sleepQuietly(Duration.ofMillis(50));
    int secondPass = poller.pollOnce();

    assertThat(secondPass)
        .as("Remember's retry AND Release, deferred to the same moment, are due together")
        .isEqualTo(2);
    assertThat(remembered)
        .as("the exchange reaches the transcript once the retry succeeds")
        .hasSize(1);
    assertThat(claims.get(AGENT, turnId, ANSWER_CLAIM_KEY))
        .as("Release ran on the pass where it was finally allowed to")
        .isEmpty();
  }

  /** Throws once for an {@link AnswerMessage}, then records every one after that. */
  private Memory throwOnceThenRecord(AtomicBoolean failedOnce) {
    return new Memory() {
      @Override
      public Context recall(AgentId agentId) {
        return Context.of(remembered.stream().map(ContextMessage.class::cast).toList());
      }

      @Override
      public void remember(AgentId agentId, HistoryMessage message) {
        if (!failedOnce.getAndSet(true)) {
          throw new IllegalStateException("the memory backend is down");
        }
        remembered.add(message);
      }

      @Override
      public void forget(AgentId agentId) {
        remembered.clear();
      }
    };
  }

  private EffectPoller poller(Memory memory) {
    Executor direct = Runnable::run;
    AgentRuntime[] runtimeHolder = new AgentRuntime[1];
    Dispatcher dispatcherProxy =
        (agentId, input, completing, observability) ->
            runtimeHolder[0].dispatch(agentId, input, completing, observability);
    BacklogStore<HouseEvents.HouseEvent> backlog =
        new BacklogStore<>(
            database,
            claims,
            HouseEvents.CODEC,
            JsonCodec.of(EngineMapper.INSTANCE, UserMessage.class),
            HouseEvents.RENDERER,
            HouseEvents.KEEP_ALL,
            Clock.systemUTC());
    EffectWorker effectWorker =
        new EffectWorker(
            new EffectWorker.Dependencies(
                TYPE,
                memory,
                Engines.stalled(),
                "you watch a house",
                256,
                new ToolBindings(List.of(), EngineMapper.INSTANCE),
                Set.of(),
                agentId ->
                    event -> {
                      // Narration is not this test's concern.
                    },
                claims,
                ReplyTokens.ephemeral(),
                direct,
                Traces.noop(),
                backlog,
                effects,
                dispatcherProxy,
                store,
                RetryPolicy.exponential(Duration.ofMillis(1), 2.0, Duration.ofMillis(100), 5),
                new Random(0),
                Duration.ofDays(1)));
    AgentRuntime runtime =
        new AgentRuntime(TYPE, transition, effectWorker::perform, direct, Traces.noop());
    runtimeHolder[0] = runtime;
    return new EffectPoller(
        TYPE,
        effects,
        runtime,
        new PollSchedule(Duration.ofMillis(10), Duration.ofSeconds(1), 2.0, 0.0, new Random(1)),
        direct,
        100,
        Duration.ofMinutes(1));
  }

  private static void sleepQuietly(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
