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

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ObjIntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * honestly. No cluster is needed: {@link AgentRuntime} is its own {@link Dispatcher}, so it is
 * wired to itself directly.
 */
@DisplayName("A retried effect and its siblings")
class HeldSiblingsTest {

  private static final AgentType TYPE = AgentType.of("watchman");
  private static final AgentId AGENT = AgentId.of("house-1");
  private static final String ANSWER_CLAIM_KEY = "answer"; // mirrors EffectWorker's private key
  private static final String PENDING = "PENDING"; // mirrors EffectStore's private status literals

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
    store = new AgentStore(database, Clock.systemUTC());
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

  @Test
  @DisplayName(
      "R-AC: a held sibling is not charged a failure -- it was deliberately not run, and being"
          + " held is not a failure")
  void a_held_sibling_is_not_charged_a_failure() {
    TurnId turnId = TurnId.of("turn-2");
    transition.read(AGENT);
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
    // A performer that RECORDS the attempts count Release is actually performed with, chained
    // to the real EffectWorker -- because by the time Release's OWN pass finishes, a genuinely
    // successful run has already deleted the row (settle -> complete), so a raw SQL read taken
    // AFTER the pass cannot observe what attempts read the moment take() handed it over. That is
    // the one moment this defect is visible in.
    List<Integer> releaseAttempts = new ArrayList<>();
    EffectPoller poller =
        poller(
            throwOnceThenRecord(failedOnce),
            (effect, attempts) -> {
              if (effect instanceof Effect.Release) {
                releaseAttempts.add(attempts);
              }
            });

    poller.pollOnce();

    assertThat(releaseAttempts)
        .as("Release was not performed at all in the pass that held it back")
        .isEmpty();

    // The retry succeeds and the deferred sibling finally runs.
    sleepQuietly(Duration.ofMillis(50));
    poller.pollOnce();

    assertThat(releaseAttempts)
        .as(
            "Release genuinely ran for the first time here -- being held back earlier must not"
                + " have charged it a failure")
        .containsExactly(0);
  }

  @Test
  @DisplayName(
      "R-AD: a throwing obligation holds back its siblings -- an undecodable payload stops the"
          + " group exactly like a synchronous retry or abandon does")
  void a_throwing_obligation_holds_back_its_siblings() {
    TurnId turnId = TurnId.of("turn-3");
    transition.read(AGENT);
    claims.put(
        AGENT,
        turnId,
        ANSWER_CLAIM_KEY,
        answerCodec.encode(new AnswerMessage(List.of(new TextBlock("hi")))));
    // Ordinal 0's payload is not valid JSON at all -- EffectStore.PAYLOADS.decode throws inside
    // AgentRuntime#perform, before EffectWorker ever sees it. C1's own archetype: a deploy that
    // changed Effect's JSON shape out from under an in-flight row.
    EffectId lead =
        effects.insert(
            TYPE,
            AGENT,
            turnId,
            null,
            0,
            "not a valid payload".getBytes(StandardCharsets.UTF_8),
            null);
    EffectId sibling =
        effects.insert(
            TYPE, AGENT, turnId, null, 1, EffectStore.PAYLOADS.encode(new Effect.Release()), null);

    EffectPoller poller = poller(throwOnceThenRecord(new AtomicBoolean(true)));

    int firstPass = poller.pollOnce();

    assertThat(firstPass).isEqualTo(2);
    assertThat(claims.get(AGENT, turnId, ANSWER_CLAIM_KEY))
        .as("Release must not have run past a sibling that threw -- the claim survives")
        .isPresent();
    assertThat(statusOf(sibling))
        .as(
            "F-NEW: the held sibling is released back to PENDING. Left RUNNING -- which is what"
                + " this pass's own attempt() made it -- TAKE charges it a failure it never made"
                + " the next time its watchdog lapses.")
        .contains(PENDING);
    assertReleasedBehindTheStopper(Outcome.THROW, lead, sibling);
  }

  /**
   * Every way performing one row can END, so that the invariant is asserted once rather than
   * rediscovered a branch at a time. Four review rounds each found ONE of these leaving a sibling
   * behind (R-AC, F1, F-NEW); the enum exists so a fifth outcome cannot be added without the
   * exhaustive switches below refusing to compile.
   *
   * <p>{@code groupContinues} is the distinction that must NOT be flattened: an effect that hands
   * off to a model call, a tool or an approver has not failed, so its siblings are supposed to run
   * -- releasing them there would be just as wrong as leaving them RUNNING here.
   */
  private enum Outcome {
    SYNC_SUCCESS(true, false),
    SYNC_RETRY(false, true),
    SYNC_ABANDON(false, false),
    ASYNC_HANDOFF(true, false),
    THROW(false, true);

    private final boolean groupContinues;
    private final boolean stopperStaysOutstanding;

    Outcome(boolean groupContinues, boolean stopperStaysOutstanding) {
      this.groupContinues = groupContinues;
      this.stopperStaysOutstanding = stopperStaysOutstanding;
    }

    boolean groupContinues() {
      return groupContinues;
    }

    /**
     * Whether the row that stopped the group WILL be attempted again -- a retry with its backoff, a
     * throw with its watchdog. F1: those are exactly the cases where the siblings' new
     * actionable_at has to be the stopping row's own, because SELECT_DUE orders by (actionable_at,
     * ordinal) and anything earlier puts a released sibling back in front of the row it was
     * released from. {@code SYNC_ABANDON} is the one stop that is genuinely terminal: ABANDON nulls
     * actionable_at, there is nothing left to wait behind, and Instant.now() is right.
     */
    boolean stopperStaysOutstanding() {
      return stopperStaysOutstanding;
    }
  }

  @ParameterizedTest
  @EnumSource(Outcome.class)
  @DisplayName(
      "R-AG: no outcome of performing a row leaves an un-run sibling RUNNING -- a sibling either"
          + " ran, or was released back to PENDING")
  void no_outcome_leaves_an_un_run_sibling_running(Outcome outcome) {
    TurnId turnId = TurnId.of("turn-" + outcome);
    transition.read(AGENT);
    claims.put(
        AGENT,
        turnId,
        ANSWER_CLAIM_KEY,
        answerCodec.encode(new AnswerMessage(List.of(new TextBlock("hi")))));
    EffectId lead = effects.insert(TYPE, AGENT, turnId, null, 0, leadPayloadFor(outcome), null);
    EffectId sibling =
        effects.insert(
            TYPE, AGENT, turnId, null, 1, EffectStore.PAYLOADS.encode(new Effect.Release()), null);

    List<Integer> siblingRuns = new ArrayList<>();
    EffectPoller poller =
        poller(
            memoryFor(outcome),
            (effect, attempts) -> {
              if (effect instanceof Effect.Release) {
                siblingRuns.add(attempts);
              }
            },
            retryPolicyFor(outcome),
            blockingFor(outcome));

    poller.pollOnce();

    if (outcome.groupContinues()) {
      assertThat(siblingRuns)
          .as("nothing stopped this group, so the sibling ran -- at attempts 0, its first try")
          .containsExactly(0);
      assertThat(statusOf(sibling))
          .as("a sibling that ran and succeeded discharged its own row")
          .isEmpty();
    } else {
      assertThat(siblingRuns).as("the group stopped, so the sibling never ran").isEmpty();
      assertThat(statusOf(sibling))
          .as(
              "an un-run sibling is released back to PENDING. Left RUNNING -- which is what this"
                  + " pass's own attempt() made it -- TAKE charges it a failure it never made the"
                  + " next time its watchdog lapses.")
          .contains(PENDING);
      assertReleasedBehindTheStopper(outcome, lead, sibling);
    }
  }

  /** What ordinal 0 must be for {@code outcome} to be the way performing it ends. */
  private static byte[] leadPayloadFor(Outcome outcome) {
    return switch (outcome) {
      // A memory that accepts the write: settle succeeds and completes the row.
      case SYNC_SUCCESS, SYNC_ABANDON -> EffectStore.PAYLOADS.encode(new Effect.Remember.Answer());
      // A memory that throws once: settle catches it and schedules a retry.
      case SYNC_RETRY -> EffectStore.PAYLOADS.encode(new Effect.Remember.Answer());
      // A model that accepts the request and never answers: the row is still RUNNING when perform
      // returns, exactly like a row that quietly succeeded -- which is why "still RUNNING" alone
      // can never be the signal that a group must stop.
      case ASYNC_HANDOFF -> EffectStore.PAYLOADS.encode(new Effect.CallModel());
      // Not valid JSON at all: PAYLOADS.decode throws inside AgentRuntime#perform, before
      // EffectWorker ever sees it -- a deploy that changed Effect's shape out from under an
      // in-flight row.
      case THROW -> "not a valid payload".getBytes(StandardCharsets.UTF_8);
    };
  }

  private Memory memoryFor(Outcome outcome) {
    return switch (outcome) {
      case SYNC_RETRY -> throwOnceThenRecord(new AtomicBoolean(false));
      case SYNC_SUCCESS, SYNC_ABANDON, ASYNC_HANDOFF, THROW ->
          throwOnceThenRecord(new AtomicBoolean(true));
    };
  }

  private static RetryPolicy retryPolicyFor(Outcome outcome) {
    return switch (outcome) {
      // A budget already spent: EffectWorker#perform consults the policy BEFORE doing any work,
      // so this abandons the row without the memory ever being touched.
      case SYNC_ABANDON -> (attemptsMade, random) -> new RetryPolicy.RetryDecision.GiveUp();
      case SYNC_SUCCESS, SYNC_RETRY, ASYNC_HANDOFF, THROW ->
          RetryPolicy.exponential(Duration.ofMillis(1), 2.0, Duration.ofMillis(100), 5);
    };
  }

  private static Executor blockingFor(Outcome outcome) {
    return switch (outcome) {
      // The model call must NOT run on the polling thread: the point of this case is that perform
      // returns while the work is still outstanding. A virtual thread, never joined and never
      // interrupted -- the stalled model parks it for the life of the JVM, which costs nothing and
      // keeps the hand-off deterministic where a shutdown race would not be.
      case ASYNC_HANDOFF -> task -> Thread.ofVirtual().start(task);
      case SYNC_SUCCESS, SYNC_RETRY, SYNC_ABANDON, THROW -> Runnable::run;
    };
  }

  /**
   * F1: a released sibling is released to BEHIND the row that stopped it, never ahead of it.
   *
   * <p>Asserting only that a sibling went back to PENDING enforces "released" and nothing more --
   * which is how the throw branch shipped deferring its siblings to {@code Instant.now()} while
   * leaving itself RUNNING five minutes out. {@code SELECT_DUE} orders by {@code (actionable_at,
   * ordinal)}, so the two instants must be byte-identical for the ordinal to decide -- comparing
   * them as they read back out of the SAME column through the SAME driver is the only honest way to
   * say that.
   */
  private void assertReleasedBehindTheStopper(Outcome outcome, EffectId lead, EffectId sibling) {
    Optional<Instant> stopper = actionableOf(lead);
    if (!outcome.stopperStaysOutstanding()) {
      assertThat(stopper).as("an abandoned row is terminal: ABANDON nulls actionable_at").isEmpty();
      return;
    }
    assertThat(stopper).as("the row that stopped the group is still to be attempted").isPresent();
    assertThat(actionableOf(sibling))
        .as(
            "the sibling is due at the stopping row's OWN moment. Anything earlier -- Instant.now()"
                + " against a five-minute watchdog, say -- runs it AHEAD of the row it was released"
                + " from, which is the ordering the whole deferral exists to keep.")
        .isEqualTo(stopper);
  }

  /** When one row next becomes actionable, or empty when it is gone or terminal. */
  private Optional<Instant> actionableOf(EffectId id) {
    // Read as a Timestamp and mapped here rather than asked for as an Instant: the column is
    // nullable by design (ABANDON writes NULL), and a single-column query for a value type has no
    // way to hand back "the row is there and the value is null".
    List<java.sql.Timestamp> found =
        JdbcClient.create(database)
            .sql("SELECT actionable_at FROM nessy_effect WHERE effect_id = ?")
            .param(id.value())
            .query((rs, rowNum) -> rs.getTimestamp("actionable_at"))
            .list();
    return found.isEmpty()
        ? Optional.empty()
        : Optional.ofNullable(found.getFirst()).map(java.sql.Timestamp::toInstant);
  }

  /** The status of one row, or empty once it has been discharged and deleted. */
  private Optional<String> statusOf(EffectId id) {
    return JdbcClient.create(database)
        .sql("SELECT status FROM nessy_effect WHERE effect_id = ?")
        .param(id.value())
        .query(String.class)
        .optional();
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
    return poller(memory, (effect, attempts) -> {});
  }

  /**
   * As above, with {@code onPerform} called immediately before every real performance -- the ONLY
   * way to observe the {@code attempts} count a row was actually performed with when a genuinely
   * successful run deletes its own row before this test's assertions get to run (see R-AC's
   * falsification test).
   */
  private EffectPoller poller(Memory memory, ObjIntConsumer<Effect> onPerform) {
    return poller(
        memory,
        onPerform,
        RetryPolicy.exponential(Duration.ofMillis(1), 2.0, Duration.ofMillis(100), 5),
        Runnable::run);
  }

  /**
   * As above, with the {@link RetryPolicy} and the executor {@code EffectWorker} does external work
   * on both chosen by the caller -- the two knobs that decide WHICH of {@link Outcome}'s five ways
   * of ending a row this wiring produces.
   */
  private EffectPoller poller(
      Memory memory, ObjIntConsumer<Effect> onPerform, RetryPolicy retryPolicy, Executor blocking) {
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
                blocking,
                Traces.noop(),
                backlog,
                effects,
                dispatcherProxy,
                store,
                retryPolicy,
                new Random(0),
                Duration.ofDays(1)));
    AgentRuntime.Performer recordingPerformer =
        (agentId, state, turnId, effect, effectId, attempts) -> {
          onPerform.accept(effect, attempts);
          effectWorker.perform(agentId, state, turnId, effect, effectId, attempts);
        };
    AgentRuntime runtime =
        new AgentRuntime(TYPE, transition, recordingPerformer, direct, Traces.noop());
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
