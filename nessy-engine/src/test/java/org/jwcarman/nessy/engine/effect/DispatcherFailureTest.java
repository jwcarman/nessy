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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.store.Attempt;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.trace.Traces;
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
        UUID.randomUUID(), AGENT, new byte[0], new byte[0], attemptsMade, deadline, null);
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
    public Awaited<EffectOutcome> handle(AgentId agentId, AgentEffect.Infer effect) {
      return new Awaited.Ready<>(new EffectOutcome.InferenceRefused("stop"));
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
    public Awaited<EffectOutcome> handle(AgentId agentId, AgentEffect.Infer effect) {
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
    public Awaited<EffectOutcome> handle(AgentId agentId, E effect) {
      throw new UnsupportedOperationException("no test here writes this kind of effect");
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
      return new EffectOutcome.InferenceRefused("undispatchable");
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.InferenceRefused("failed");
    }
  }

  /** A store that can be asked to fail, one operation at a time. */
  private static final class Effects extends EffectStore {

    private EffectHandlers handlers;
    private List<Attempt> due = List.of();
    private RuntimeException claimFails;
    private RuntimeException effectFails;
    private RuntimeException failureFails;
    private boolean rescheduleWins = true;
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private final List<UUID> retired = new CopyOnWriteArrayList<>();
    private final List<UUID> rescheduled = new CopyOnWriteArrayList<>();

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
      return new AgentEffect.Infer();
    }

    @Override
    public EffectOutcome failureOf(Attempt attempt) {
      if (failureFails != null) {
        throw failureFails;
      }
      return handlers.termsFor(new AgentEffect.Infer()).undispatchable();
    }

    @Override
    public boolean complete(UUID effectId, int attemptsMade) {
      retired.add(effectId);
      return true;
    }

    @Override
    public boolean reschedule(UUID effectId, int attemptsMade, Instant at) {
      rescheduled.add(effectId);
      return rescheduleWins;
    }
  }

  /** Everything the dispatcher managed to tell an agent. */
  private static final class Deliveries implements AgentEffectCallback {

    private final List<EffectOutcome> outcomes = new CopyOnWriteArrayList<>();

    @Override
    public void deliverOutcome(AgentId agentId, EffectOutcome outcome, String traceContext) {
      outcomes.add(outcome);
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
