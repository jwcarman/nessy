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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.engine.store.Outbox;
import org.jwcarman.nessy.engine.trace.Traces;
import org.jwcarman.nessy.inference.Failure;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * What the dispatcher does on a bad day.
 *
 * <p>Every path here is one where something the dispatcher depends on has failed: the table, the
 * scheduler, the thread pool, or the payload. None of them may cost an agent its turn silently, and
 * none of them may take the schedule down with it -- an uncaught throw out of a fixed-delay task
 * cancels it for good, and the agent type goes quiet for the life of the process.
 *
 * <p>Driven through the dispatcher's own collaborators rather than a live engine, because a real
 * table cannot be asked to fail on command and a real scheduler cannot be asked to refuse.
 */
class DispatcherFailureTest {

  private static final AgentType TYPE = new AgentType("failing");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final int PERMITS = 4;

  private final Schedule schedule = new Schedule();
  private final Deliveries delivered = new Deliveries();

  @Test
  void a_dispatcher_says_which_agent_type_it_serves() {
    assertThat(dispatcherFor(new Effects(), answering()).agentType()).isEqualTo(TYPE);
  }

  /** Nothing to bring forward: there is no schedule yet to run a pass on. */
  @Test
  void a_dispatcher_that_was_never_started_has_nothing_to_nudge() {
    dispatcherFor(new Effects(), answering()).nudge();

    assertThat(schedule.nudges).isEmpty();
  }

  /** A fold committing as the process comes down has nobody left to tell. */
  @Test
  void a_closed_dispatcher_is_not_nudged_again() {
    EffectDispatcher dispatcher = dispatcherFor(new Effects(), answering());
    dispatcher.start();
    dispatcher.close();

    dispatcher.nudge();

    assertThat(schedule.nudges).isEmpty();
  }

  /**
   * A scheduler shutting down refuses the task, and being quicker is all that is lost -- the poll,
   * or the next process to start, still finds the rows.
   */
  @Test
  void a_scheduler_that_will_not_take_the_pass_does_not_fail_the_fold() {
    EffectDispatcher dispatcher = dispatcherFor(new Effects(), answering());
    dispatcher.start();
    schedule.refusing = true;

    assertThatCode(dispatcher::nudge).doesNotThrowAnyException();
  }

  /**
   * The poll runs {@code pass}, and a pass that let a throw out would cancel its own fixed-delay
   * task -- so the failure is swallowed deliberately, and the next poll tries again.
   */
  @Test
  void a_pass_that_throws_leaves_the_schedule_running() {
    Effects effects = new Effects();
    effects.claimFails = new IllegalStateException("the table is gone");
    dispatcherFor(effects, answering()).start();
    Runnable poll = schedule.polls.getFirst();

    assertThatCode(poll::run).doesNotThrowAnyException();

    effects.claimFails = null;
    poll.run();
    assertThat(effects.batchSizes)
        .as("the pass that threw gave its permits back, so the next one may take them all")
        .containsExactly(PERMITS, PERMITS);
  }

  /**
   * Capacity is taken before the claim, so a claim that throws has to hand it back on the way out
   * -- otherwise the permits are gone for the life of the process and the agent type quietly stops
   * doing anything.
   */
  @Test
  void capacity_reserved_for_a_claim_that_throws_is_given_back() {
    Effects effects = new Effects();
    effects.claimFails = new IllegalStateException("the table is gone");
    EffectDispatcher dispatcher = dispatcherFor(effects, answering());

    assertThatThrownBy(dispatcher::dispatch).isInstanceOf(IllegalStateException.class);

    effects.claimFails = null;
    dispatcher.dispatch();
    assertThat(effects.batchSizes).containsExactly(PERMITS, PERMITS);
  }

  /**
   * A row that could not be handed to a thread keeps its marking; its watchdog is what brings it
   * back. What must not leak is the permit it was holding.
   */
  @Test
  void an_effect_that_cannot_be_started_gives_back_its_permit() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(60), 1));
    EffectDispatcher dispatcher = dispatcherFor(effects, answering());
    // Closing the workers is what makes the submit fail, which is exactly the real cause: a
    // dispatcher coming down while a pass is still starting rows.
    dispatcher.close();

    dispatcher.dispatch();

    effects.due = List.of();
    dispatcher.dispatch();
    assertThat(effects.batchSizes)
        .as("the row that could not start released what it held")
        .containsExactly(PERMITS, PERMITS);
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * Two independent blobs have gone: the effect passed its deadline, and the response stored beside
   * it for exactly this moment will not read either. Nothing can reach the agent, so the row is
   * retired rather than left to be claimed forever.
   */
  @Test
  void an_expired_effect_whose_stored_failure_cannot_be_read_is_still_retired() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.minusSeconds(1), 3));
    effects.failureFails = new IllegalStateException("the failure blob is unreadable");

    dispatcherFor(effects, answering()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.retired).hasSize(1));
    assertThat(delivered.outcomes).as("there was nothing left to tell it").isEmpty();
  }

  /**
   * The same two blobs, the other way round: the effect itself will not decode, and neither will
   * the response that was supposed to cover that case.
   */
  @Test
  void an_undispatchable_effect_whose_stored_failure_cannot_be_read_is_still_retired() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(60), 1));
    effects.effectFails = new IllegalStateException("an effect from a build that was rolled back");
    effects.failureFails = new IllegalStateException("the failure blob is unreadable");

    dispatcherFor(effects, answering()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.retired).hasSize(1));
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * The one answer that cannot name its request: the effect would not decode, and the turn and the
   * request both live in the effect. It is delivered naming neither, and the fold matches it to
   * whatever the agent is on.
   */
  @Test
  void an_undispatchable_effect_delivers_its_stored_failure_naming_no_turn_and_no_request() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(60), 1));
    effects.effectFails = new IllegalStateException("an effect from a build that was rolled back");

    dispatcherFor(effects, answering()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(delivered.turns).containsExactly(Optional.empty());
    assertThat(delivered.requests).containsExactly(Optional.empty());
  }

  /**
   * The stored failure of a call whose effect will not decode is the only account of why it failed,
   * so what it says about the kind is what the agent is told.
   */
  @Test
  void an_undecodable_effect_delivers_its_stored_failure_with_its_kind() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(60), 1));
    effects.effectFails = new IllegalStateException("an effect from a build that was rolled back");
    effects.storedFailure =
        new EffectOutcome.ToolFailed(
            new CallId("c1"), CallFailure.PAST_DEADLINE, "the call did not complete in time");

    dispatcherFor(effects, answering()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(delivered.outcomes)
        .singleElement()
        .asInstanceOf(InstanceOfAssertFactories.type(EffectOutcome.ToolFailed.class))
        .extracting(EffectOutcome.ToolFailed::kind)
        .isEqualTo(CallFailure.PAST_DEADLINE);
  }

  /** A failed attempt with attempts left is written down to be tried again. */
  @Test
  void an_effect_that_failed_and_may_be_tried_again_is_rescheduled() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));

    dispatcherFor(effects, throwing()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.rescheduled).hasSize(1));
    assertThat(effects.retired).as("it has not been given up on").isEmpty();
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * A provider that catches its vendor's exception and classifies the failure is not thereby giving
   * up. Every adapter catches -- an escaping exception would leave an agent waiting on an answer
   * nothing will bring -- so a failure that arrives as a value is the only kind a model call
   * produces, and it has to be able to ask for another go.
   */
  @Test
  void a_transient_model_failure_is_tried_again() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));

    dispatcherFor(effects, failingWith(new Failure.Transient("the model was busy"))).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.rescheduled).hasSize(1));
    assertThat(effects.retired).as("it has not been given up on").isEmpty();
    assertThat(delivered.outcomes).as("the turn has not been told anything yet").isEmpty();
  }

  /** Permanent means the identical request fails identically, so another go is a turn wasted. */
  @Test
  void a_permanent_model_failure_ends_the_turn_without_trying_again() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));

    dispatcherFor(effects, failingWith(new Failure.Permanent("the model refused the request")))
        .dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(effects.rescheduled).as("nothing about it would go differently").isEmpty();
  }

  /**
   * What reaches the fold when the attempts run out is the handler's OWN account, not the stored
   * blob: it names the failure the provider classified and carries what the call cost, and both
   * would be lost by falling back to the response frozen beside the row.
   */
  @Test
  void a_transient_failure_that_runs_out_of_attempts_reaches_the_fold_with_what_it_learned() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));
    EffectOutcome.InferenceFailed failed =
        new EffectOutcome.InferenceFailed(
            new Failure.Transient("the model was busy"), Usage.of("a-model", 11, 0));

    dispatcherFor(effects, failingOnceWith(failed)).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(delivered.outcomes.getFirst())
        .as("the provider's own account survives the attempts running out")
        .isEqualTo(failed);
    assertThat(effects.rescheduled).as("the one attempt it was allowed is spent").isEmpty();
    assertThat(effects.retired).as("and the row is done with").hasSize(1);
  }

  /**
   * An attempt that threw knows only the exception, so what discharges it is the terms' account of
   * that -- not a provider classification, which on this path does not exist. Pinned because the
   * two kinds of failure now reach the same give-up through the same argument, and nothing else
   * would notice if the throwing one started discharging with the wrong thing.
   */
  @Test
  void a_thrown_failure_that_runs_out_of_attempts_is_discharged_by_its_terms() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));

    dispatcherFor(effects, throwingOnce()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(delivered.outcomes.getFirst())
        .as("the terms say what a thrown attempt amounts to")
        .isEqualTo(new EffectOutcome.InferenceRefused("failed", Usage.unreported()));
  }

  /**
   * A dropped connection is reported as unknown: nobody found out whether the model ran. Running a
   * model call again changes nothing but the bill, so the policy decides, as it does for transient.
   */
  @Test
  void an_unknown_model_failure_is_tried_again() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));

    dispatcherFor(effects, failingWith(new Failure.Unknown("no answer from the model"))).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.rescheduled).hasSize(1));
    assertThat(effects.retired).as("it has not been given up on").isEmpty();
    assertThat(delivered.outcomes).as("the turn has not been told anything yet").isEmpty();
  }

  /** With its attempts spent, an unknown failure reaches the fold as the provider reported it. */
  @Test
  void an_unknown_failure_that_runs_out_of_attempts_reaches_the_fold_with_what_it_learned() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));
    EffectOutcome.InferenceFailed failed =
        new EffectOutcome.InferenceFailed(
            new Failure.Unknown("no answer from the model"), Usage.unreported());

    dispatcherFor(effects, failingOnceWith(failed)).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(delivered.outcomes).hasSize(1));
    assertThat(delivered.outcomes.getFirst()).isEqualTo(failed);
    assertThat(effects.rescheduled).as("the one attempt it was allowed is spent").isEmpty();
  }

  /**
   * The reschedule is fenced on the attempt doing it, so losing the fence means this attempt
   * overran its deadline and another took the work over. Theirs is the live one; this one stops.
   */
  @Test
  void an_effect_taken_over_while_it_was_failing_is_left_to_whoever_has_it() {
    Effects effects = new Effects();
    effects.due = List.of(attempt(NOW.plusSeconds(600), 1));
    effects.rescheduleWins = false;

    dispatcherFor(effects, throwing()).dispatch();

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(effects.rescheduled).hasSize(1));
    assertThat(effects.retired).as("the attempt that lost the fence retires nothing").isEmpty();
    assertThat(delivered.outcomes).isEmpty();
  }

  /**
   * A claim that hands back more rows than it was asked for must not cost the dispatcher its
   * capacity. Handing back the difference would be negative, the semaphore would throw, and the
   * permits drained for the pass would never come back: the agent type would stop dispatching for
   * good. The rows beyond the batch are put straight back, due now, for the next pass; left marked,
   * they would come due only at their deadline and be given up on unrun.
   */
  @Test
  void a_claim_that_returns_more_than_its_batch_loses_no_capacity() {
    Effects effects = new Effects();
    List<Attempt> claimed =
        List.of(
            attempt(NOW.plusSeconds(600), 1),
            attempt(NOW.plusSeconds(600), 1),
            attempt(NOW.plusSeconds(600), 1),
            attempt(NOW.plusSeconds(600), 1),
            attempt(NOW.plusSeconds(600), 1),
            attempt(NOW.plusSeconds(600), 1));
    effects.due = claimed;
    EffectDispatcher dispatcher = dispatcherFor(effects, answering());

    assertThatCode(dispatcher::dispatch).doesNotThrowAnyException();
    assertThat(effects.rescheduled)
        .as("the surplus goes straight back, due now")
        .containsExactly(claimed.get(4).effectId(), claimed.get(5).effectId());
    assertThat(effects.rescheduledAt).containsOnly(NOW);

    effects.due = List.of();
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              dispatcher.dispatch();
              assertThat(effects.batchSizes.getLast()).isEqualTo(PERMITS);
            });
    assertThat(delivered.outcomes).hasSize(PERMITS);
  }

  // ---------------------------------------------------------------- fixture

  private EffectDispatcher dispatcherFor(
      Effects effects, EffectHandler<AgentEffect.Infer> handler) {
    EffectHandlers handlers = new EffectHandlers(handler, unusable(), unusable());
    effects.handlers = handlers;
    return new EffectDispatcher(
        TYPE,
        effects,
        handlers,
        delivered,
        CLOCK,
        schedule,
        Traces.noop(),
        Duration.ofMinutes(10),
        PERMITS);
  }

  private static Attempt attempt(Instant deadline, int attemptsMade) {
    return new Attempt(
        UUID.randomUUID(), AGENT, new byte[0], new byte[0], attemptsMade, deadline, null, null);
  }

  /** A handler that answers, for the tests where the handler is not what is failing. */
  private static EffectHandler<AgentEffect.Infer> answering() {
    return new Answering();
  }

  private static final class Answering implements EffectHandler<AgentEffect.Infer> {

    @Override
    public EffectTerms termsFor(AgentEffect.Infer effect) {
      return new Terms();
    }

    @Override
    public Awaited<EffectOutcome> handle(
        AgentId agentId, AgentEffect.Infer effect, Instant deadline) {
      return new Awaited.Ready<>(new EffectOutcome.InferenceRefused("stop", Usage.unreported()));
    }
  }

  /** A handler that fails the way a model call does: by throwing. */
  private static EffectHandler<AgentEffect.Infer> throwing() {
    return new Throwing();
  }

  private static final class Throwing implements EffectHandler<AgentEffect.Infer> {

    @Override
    public EffectTerms termsFor(AgentEffect.Infer effect) {
      return new Terms();
    }

    @Override
    public Awaited<EffectOutcome> handle(
        AgentId agentId, AgentEffect.Infer effect, Instant deadline) {
      throw new IllegalStateException("the model call failed");
    }
  }

  /** A handler that fails the way a real model call does: by returning a classified failure. */
  private static EffectHandler<AgentEffect.Infer> failingWith(Failure failure) {
    return new Failing(new EffectOutcome.InferenceFailed(failure, Usage.unreported()), new Terms());
  }

  /** The same, with one attempt allowed, so the give-up path is what gets measured. */
  private static EffectHandler<AgentEffect.Infer> failingOnceWith(EffectOutcome outcome) {
    return new Failing(outcome, new OneGo());
  }

  private record Failing(EffectOutcome outcome, EffectTerms terms)
      implements EffectHandler<AgentEffect.Infer> {

    @Override
    public EffectTerms termsFor(AgentEffect.Infer effect) {
      return terms;
    }

    @Override
    public Awaited<EffectOutcome> handle(
        AgentId agentId, AgentEffect.Infer effect, Instant deadline) {
      return new Awaited.Ready<>(outcome);
    }
  }

  /** Throws, with one attempt allowed, so the give-up path is what gets measured. */
  private static EffectHandler<AgentEffect.Infer> throwingOnce() {
    return new ThrowingOnce();
  }

  private static final class ThrowingOnce implements EffectHandler<AgentEffect.Infer> {

    @Override
    public EffectTerms termsFor(AgentEffect.Infer effect) {
      return new OneGo();
    }

    @Override
    public Awaited<EffectOutcome> handle(
        AgentId agentId, AgentEffect.Infer effect, Instant deadline) {
      throw new IllegalStateException("the model call failed");
    }
  }

  /** The kinds of effect no test here writes; reaching one is a bug in the test. */
  private static <E extends AgentEffect> EffectHandler<E> unusable() {
    return new Unusable<>();
  }

  private static final class Unusable<E extends AgentEffect> implements EffectHandler<E> {

    @Override
    public EffectTerms termsFor(E effect) {
      throw new UnsupportedOperationException("no test here writes this kind of effect");
    }

    @Override
    public Awaited<EffectOutcome> handle(AgentId agentId, E effect, Instant deadline) {
      throw new UnsupportedOperationException("no test here writes this kind of effect");
    }
  }

  /** One attempt and no more, so what is measured is what happens when they run out. */
  private record OneGo() implements EffectTerms {

    @Override
    public Duration timeout() {
      return Duration.ofMinutes(1);
    }

    @Override
    public RetryPolicy retryPolicy() {
      return new RetryPolicy.FixedDelay(1, Duration.ofSeconds(1), Duration.ZERO);
    }

    @Override
    public EffectOutcome undispatchable() {
      return new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported());
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.InferenceRefused("failed", Usage.unreported());
    }
  }

  /** Enough attempts that giving up is never what is being measured. */
  private record Terms() implements EffectTerms {

    @Override
    public Duration timeout() {
      return Duration.ofMinutes(1);
    }

    @Override
    public RetryPolicy retryPolicy() {
      return new RetryPolicy.FixedDelay(10, Duration.ofSeconds(1), Duration.ZERO);
    }

    @Override
    public EffectOutcome undispatchable() {
      return new EffectOutcome.InferenceRefused("undispatchable", Usage.unreported());
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.InferenceRefused("failed", Usage.unreported());
    }
  }

  /** A store that can be asked to fail, one operation at a time. */
  private static final class Effects extends Outbox {

    /** Whatever turn; nothing here folds, so only that the effect names one matters. */
    private static final TurnId TURN = new TurnId(1);

    private EffectHandlers handlers;
    private List<Attempt> due = List.of();
    private RuntimeException claimFails;
    private RuntimeException effectFails;
    private RuntimeException failureFails;
    private EffectOutcome storedFailure;
    private boolean rescheduleWins = true;
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private final List<UUID> retired = new CopyOnWriteArrayList<>();
    private final List<UUID> rescheduled = new CopyOnWriteArrayList<>();
    private final List<Instant> rescheduledAt = new CopyOnWriteArrayList<>();

    private Effects() {
      super(TYPE, null, null);
    }

    @Override
    public List<Attempt> markRunning(Instant now, int batchSize) {
      batchSizes.add(batchSize);
      if (claimFails != null) {
        throw claimFails;
      }
      return due;
    }

    @Override
    public AgentEffect effectOf(Attempt attempt) {
      if (effectFails != null) {
        throw effectFails;
      }
      return new AgentEffect.Infer(TURN);
    }

    @Override
    public EffectOutcome failureOf(Attempt attempt) {
      if (failureFails != null) {
        throw failureFails;
      }
      if (storedFailure != null) {
        return storedFailure;
      }
      return handlers.termsFor(new AgentEffect.Infer(TURN)).undispatchable();
    }

    @Override
    public boolean complete(UUID effectId, int attemptsMade) {
      retired.add(effectId);
      return true;
    }

    @Override
    public boolean reschedule(
        UUID effectId, int attemptsMade, Instant at, List<FailedAttempt> failedAttempts) {
      rescheduled.add(effectId);
      rescheduledAt.add(at);
      return rescheduleWins;
    }

    @Override
    public List<FailedAttempt> attemptsOf(Attempt attempt) {
      return List.of();
    }
  }

  /** Everything the dispatcher managed to tell an agent. */
  private static final class Deliveries implements AgentEffectCallback {

    private final List<EffectOutcome> outcomes = new CopyOnWriteArrayList<>();
    private final List<Optional<TurnId>> turns = new CopyOnWriteArrayList<>();
    private final List<Optional<Seq>> requests = new CopyOnWriteArrayList<>();

    @Override
    public void deliverOutcome(
        AgentId agentId,
        Optional<TurnId> turn,
        Optional<Seq> request,
        EffectOutcome outcome,
        String traceContext,
        List<FailedAttempt> priorAttempts) {
      outcomes.add(outcome);
      turns.add(turn);
      requests.add(request);
    }
  }

  /**
   * A scheduler that hands back the tasks it was given instead of running them, so a test decides
   * when a pass happens -- and can ask for one that never comes.
   */
  private static final class Schedule implements TaskScheduler {

    private final List<Runnable> polls = new ArrayList<>();
    private final List<Runnable> nudges = new ArrayList<>();
    private final Handle handle = new Handle();
    private boolean refusing;

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant start, Duration delay) {
      polls.add(task);
      return handle;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      if (refusing) {
        throw new TaskRejectedException("this scheduler is shutting down");
      }
      nudges.add(task);
      return handle;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      throw new UnsupportedOperationException("the dispatcher does not schedule by trigger");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant start, Duration period) {
      throw new UnsupportedOperationException("the dispatcher does not schedule at a fixed rate");
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      throw new UnsupportedOperationException("the dispatcher does not schedule at a fixed rate");
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      throw new UnsupportedOperationException("the dispatcher schedules from an instant");
    }
  }

  /** The schedule as the dispatcher holds it: something it can ask about and cancel. */
  private static final class Handle implements ScheduledFuture<Object> {

    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public long getDelay(TimeUnit unit) {
      return 0;
    }

    @Override
    public int compareTo(java.util.concurrent.Delayed other) {
      return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return cancelled.compareAndSet(false, true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public boolean isDone() {
      return cancelled.get();
    }

    @Override
    public Object get() {
      throw new UnsupportedOperationException("a poll schedule has no result");
    }

    @Override
    public Object get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException("a poll schedule has no result");
    }
  }
}
