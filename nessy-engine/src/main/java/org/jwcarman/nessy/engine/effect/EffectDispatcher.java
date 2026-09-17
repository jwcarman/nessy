package org.jwcarman.nessy.engine.effect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.random.RandomGenerator;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryDecision;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.store.Attempt;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.trace.Traces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * One agent type's obligations, performed.
 *
 * <p>Owns no thread and no schedule. Something calls {@link #pass()}; how often is that caller's
 * business. The dispatcher that ran its own loop and backed off adaptively was tuning a latency
 * nobody had measured, against a queue holding one kind of work.
 *
 * <p><b>It routes; the handler works.</b> Adding another kind of effect is a handler, not another
 * arm in a switch that grows for the life of the project.
 *
 * <p>A row is marked running before it is performed, never after: the mark commits on its own, so a
 * crash mid-call leaves a row whose deadline has passed rather than one nothing will ever pick up.
 * Each row is performed inside its own try, so one bad row costs its own attempt and not the rest
 * of the batch.
 *
 * <p><b>Each effect runs on its own virtual thread.</b> An effect is a model call -- seconds of
 * waiting on a socket -- so performing a batch one row at a time would make ten agents wait for
 * each other for no reason but the order they came out of the query.
 *
 * <p><b>A semaphore sizes the batch; it does not gate execution.</b> Permits are taken before any
 * row is marked, so nothing is ever claimed that is not started immediately. Gating afterwards
 * would leave rows marked running with their watchdogs ticking while they queued for a permit, and
 * a row whose deadline passes while queued becomes actionable again -- so the same effect would be
 * performed twice, once by whoever was waiting and once by whoever took it back.
 *
 * <p>Dispatch does not wait for the batch it starts. That is what makes the permits meaningful: if
 * a pass blocked until its rows finished, the schedule could not run again anyway and nothing else
 * would ever be in flight. Since it returns immediately, the permits are the only thing bounding
 * how much work exists at once.
 */
public class EffectDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EffectDispatcher.class);

  private final AgentType agentType;
  private final EffectStore effects;
  private final EffectHandlers handlers;
  private final AgentEffectCallback callback;
  private final Clock clock;
  private final Semaphore inFlight;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final TaskScheduler scheduler;
  private final Traces traces;
  private final Duration pollInterval;

  private volatile ScheduledFuture<?> polling;

  public EffectDispatcher(
      AgentType agentType,
      EffectStore effects,
      EffectHandlers handlers,
      AgentEffectCallback callback,
      Clock clock,
      TaskScheduler scheduler,
      Traces traces,
      Duration pollInterval,
      int maxInFlight) {
    this.agentType = agentType;
    this.effects = effects;
    this.handlers = handlers;
    this.callback = callback;
    this.clock = clock;
    this.scheduler = scheduler;
    this.traces = traces;
    this.pollInterval = pollInterval;
    this.inFlight = new Semaphore(maxInFlight);
  }

  public AgentType agentType() {
    return agentType;
  }

  /**
   * Puts this dispatcher on its own schedule.
   *
   * <p>How often to look for due work is this class's business and nobody else's. A harness folds;
   * it has no view on how promptly its effects should be picked up, and an agent type answering a
   * person wants a short interval where one grinding through overnight work does not. Neither of
   * those is a fact about folding.
   *
   * <p>Separate from the constructor because a dispatcher reports back through the harness that
   * owns it: the harness has to exist first, and scheduling in the constructor would let a poll
   * fire against a half-built one.
   */
  public void start() {
    log.info("[{}] polling every {}", agentType.value(), pollInterval);
    // The first poll waits a full interval rather than firing at once. Starting immediately
    // would run a pass while the harness is still being wired, and it would leave a test no
    // way to keep the schedule out of its assertions -- a long interval could still be beaten
    // by the poll that happens on the way up.
    this.polling =
        scheduler.scheduleWithFixedDelay(
            this::pass, clock.instant().plus(pollInterval), pollInterval);
  }

  /**
   * Asks for a pass now rather than at the next poll, because this process just wrote work down.
   *
   * <p>Polling alone makes every step of a turn wait for the next tick -- several times a turn, and
   * plainly visible between the spans of a trace. The fold that wrote the rows knows they exist, so
   * it says so. The poll stays for everything a nudge cannot reach: rows written by another
   * process, rows coming due again after a timeout, and a nudge lost to a crash between commit and
   * here.
   *
   * <p>Runs on the scheduler, never on the caller: the caller is a fold's thread -- an
   * application's {@code observe}, or a tool's worker -- and a claim is a query it has no business
   * waiting on.
   *
   * <p>Nothing is remembered between nudges. Passes already overlap safely -- permits bound what is
   * taken, and {@code SKIP LOCKED} keeps two passes off one row -- so a nudge per fold is a pass
   * per fold, and a pass with no capacity to spend returns without a query. When every permit is
   * held the waiting rows are the poll's to find, which is what being at capacity means.
   */
  public void nudge() {
    ScheduledFuture<?> schedule = polling;
    if (schedule == null || schedule.isCancelled()) {
      return;
    }
    try {
      scheduler.schedule(this::pass, clock.instant());
    } catch (RuntimeException e) {
      // A scheduler that is shutting down refuses the task. The poll, or the next process to
      // start, finds the rows; nothing is lost by not being quicker about it.
      log.debug("[{}] could not schedule a nudged pass", agentType.value(), e);
    }
  }

  private void pass() {
    try {
      dispatch();
    } catch (RuntimeException e) {
      // Never let one bad pass end the schedule: an uncaught throw cancels a fixed-delay task
      // permanently, and the agent type would go quiet for good.
      log.error("[{}] dispatch failed", agentType.value(), e);
    }
  }

  public void close() {
    if (polling != null) {
      polling.cancel(false);
    }
    workers.close();
  }

  /**
   * Takes as much work as there is capacity for, marks it, and starts all of it.
   *
   * <p>Capacity is claimed first and the surplus handed straight back, because the queue's depth
   * cannot be asked for and then acted on -- it changes in between. Reserving what we may run and
   * returning what we did not need is always safe; the worst case is briefly holding permits nobody
   * wanted.
   */
  public void dispatch() {
    int batchSize = inFlight.drainPermits();
    log.trace("[{}] capacity for {} effect(s)", agentType.value(), batchSize);
    if (batchSize == 0) {
      // Fully saturated: no query at all, which is the case worth optimising at this
      // interval. Note this also happens transiently when another pass is mid-query holding
      // capacity it is about to return, so a skipped pass is not proof of saturation.
      return;
    }

    List<Attempt> attempts;
    try {
      attempts = effects.markRunning(clock.instant(), batchSize);
    } catch (RuntimeException e) {
      inFlight.release(batchSize);
      throw e;
    }
    inFlight.release(batchSize - attempts.size());
    if (attempts.isEmpty()) {
      return;
    }

    log.debug("[{}] performing {} effect(s)", agentType.value(), attempts.size());
    for (Attempt attempt : attempts) {
      start(attempt);
    }
  }

  /**
   * Hands one marked row to a thread, or gives back everything it was holding.
   *
   * <p>Per row rather than per batch, and with no counter between them: every permit is released
   * either by the task that took it or by the arm that failed to create one. A tally of how many
   * tasks were started would be right today and wrong after the next edit.
   *
   * <p>A row that could not be started keeps its marking, and its watchdog is what recovers it.
   * Putting it back here would mean a write that revokes a claim without being able to say whose
   * claim it is -- there is no owner column and no lease, deliberately, so the statement could only
   * ever say "release this row, whoever holds it". Every other operation here is safe under
   * double-take; that one would not be. The deadline already answers this exact question, and it
   * answers it without needing to know who was asking.
   */
  private void start(Attempt attempt) {
    try {
      workers.submit(
          () -> {
            try {
              perform(attempt);
            } finally {
              inFlight.release();
            }
          });
    } catch (RuntimeException e) {
      inFlight.release();
      log.error(
          "[{}] could not start effect {}; its watchdog will bring it back",
          agentType.value(),
          attempt.effectId(),
          e);
    }
  }

  /**
   * What an effect span is called: its kind, and for a tool call the tool, as {@code execute_tool
   * <name>} is. An approval often has no span beneath it to say which call it was for.
   */
  private static String spanNameOf(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Infer _ -> "nessy.effect infer";
      case AgentEffect.Approve(_, _, ToolName toolName) ->
          "nessy.effect approve " + toolName.value();
      case AgentEffect.CallTool(_, _, ToolName toolName) ->
          "nessy.effect call_tool " + toolName.value();
    };
  }

  /**
   * Performs one attempt inside the trace of the turn it belongs to.
   *
   * <p>The span covers everything the attempt does -- reading the payload, running the handler,
   * folding the outcome back in. Its parent is the turn's, not the effect that emitted it: every
   * effect of a turn is a sibling in one flat trace, in the order it ran, because nesting by
   * emitter made the follow-up inference the child of whichever tool happened to finish last.
   */
  private void perform(Attempt attempt) {
    traces.restore(
        "nessy.effect",
        attempt.traceContext(),
        new Identity(agentType, attempt.agentId()),
        () -> {
          performInTrace(attempt);
          return null;
        });
  }

  private void performInTrace(Attempt attempt) {
    // Before the payload is even read. A row is claimed at its deadline rather than filtered
    // out of the claim, because a row nobody claims is a row nobody retires -- and its agent
    // waits forever. Coming due at the deadline means coming due to be given up on.
    if (!clock.instant().isBefore(attempt.deadline())) {
      expired(attempt);
      return;
    }

    AgentEffect effect;
    try {
      effect = effects.effectOf(attempt);
    } catch (RuntimeException e) {
      // Nothing here will ever succeed, and retrying is a loop with a period rather than a
      // recovery. This is what the failure response beside the row is for: the agent is
      // waiting on this call and nothing else will wake it, and the row can still say what
      // to tell it without anyone understanding what the effect was.
      log.error(
          "[{}] effect {} for agent {} cannot be decoded; delivering its stored"
              + " failure response",
          agentType.value(),
          attempt.effectId(),
          attempt.agentId().value(),
          e);
      undispatchable(attempt);
      return;
    }

    traces.nameCurrent(spanNameOf(effect));
    try {
      log.debug(
          "[{}] performing {} for agent {} (attempt {})",
          agentType.value(),
          effect.getClass().getSimpleName(),
          attempt.agentId().value(),
          attempt.attemptsMade());
      switch (handlers.perform(attempt.agentId(), effect)) {
        case Awaited.Ready<EffectOutcome>(EffectOutcome outcome) -> {
          // The outcome is folded first: if that commits and this crashes, the row comes
          // due again, is performed again, and the fold recognises the redelivery and
          // ignores it.
          callback.deliverOutcome(attempt.agentId(), outcome, attempt.traceContext());
          retire(attempt, "performed");
        }
        // Neither delivered nor retired nor rescheduled -- the row is left exactly as it
        // was claimed, and that is the whole of parking. `actionable_at` was set to the
        // deadline when the row was claimed, so it comes due once more at the moment the
        // agent stops being willing to wait, and the stored failure discharges the call
        // then. If an answer arrives first it settles the row instead, fenced by the same
        // `attempts_made` this attempt carries.
        //
        // Deliberately NOT a retry. The work happened -- somebody was asked -- and asking
        // again is pestering rather than recovering.
        case Awaited.Deferred<EffectOutcome> _ ->
            log.info(
                "[{}] effect {} for agent {} deferred its answer; it stands until {}",
                agentType.value(),
                attempt.effectId(),
                attempt.agentId().value(),
                attempt.deadline());
      }
    } catch (RuntimeException e) {
      log.error(
          "[{}] effect {} failed on attempt {}",
          agentType.value(),
          attempt.effectId(),
          attempt.attemptsMade(),
          e);
      settle(attempt, effect, e);
    }
  }

  /**
   * Ends the turn of an agent whose effect ran out of time.
   *
   * <p>Nobody knows anything more specific than that, which is exactly what the stored response is
   * for: the deadline passing tells us the work is not going to happen, and says nothing whatever
   * about why. No handler ran, so there is no exception to describe and nothing richer to say.
   *
   * <p>Unlike {@link #undispatchable}, a fold that will not commit here is not the end of the road
   * -- the stored outcome is perfectly readable and the agent simply has not been told yet. So the
   * row stays and comes back, on the same principle as giving up: ending the attempts while the
   * agent is still waiting is the one outcome worse than trying again.
   */
  private void expired(Attempt attempt) {
    EffectOutcome outcome;
    try {
      outcome = effects.failureOf(attempt);
    } catch (RuntimeException e) {
      log.error(
          "[{}] effect {} passed its deadline and its stored failure response cannot"
              + " be read either; its agent will wait forever",
          agentType.value(),
          attempt.effectId(),
          e);
      retire(attempt, "expired, unreadable");
      return;
    }
    try {
      callback.deliverOutcome(attempt.agentId(), outcome, attempt.traceContext());
    } catch (RuntimeException e) {
      log.error(
          "[{}] could not tell agent {} that effect {} passed its deadline; keeping"
              + " it, and its watchdog will bring it back",
          agentType.value(),
          attempt.agentId().value(),
          attempt.effectId(),
          e);
      return;
    }
    log.warn(
        "[{}] effect {} passed its deadline after {} attempt(s); the turn has ended",
        agentType.value(),
        attempt.effectId(),
        attempt.attemptsMade());
    retire(attempt, "expired");
  }

  /**
   * Ends the turn of an agent whose effect nobody can read.
   *
   * <p>The stored response is delivered as it stands -- not decoded into anything that has to be
   * understood first, because "understanding the payload" is the thing that just failed. If even
   * this will not decode there is nothing left to try: two independent blobs have gone, and the row
   * is retired with an error rather than left to be picked up forever.
   */
  private void undispatchable(Attempt attempt) {
    try {
      callback.deliverOutcome(
          attempt.agentId(), effects.failureOf(attempt), attempt.traceContext());
    } catch (RuntimeException e) {
      log.error(
          "[{}] effect {} has no readable failure response either; its agent will"
              + " wait forever",
          agentType.value(),
          attempt.effectId(),
          e);
    }
    retire(attempt, "undispatchable");
  }

  /**
   * Decides what a failed attempt gets: another go, or an end to the turn.
   *
   * <p>Giving up has one obligation, and it is not tidying the table. The agent is waiting on this
   * call and will wait forever unless something arrives, so the last act of a failing effect is to
   * say so. That is why this works with retries switched off: {@link RetryPolicy.Never} gives up on
   * the first failure, the turn ends, and the agent goes on. Retries change when we give up, never
   * what happens then.
   */
  private void settle(Attempt attempt, AgentEffect effect, RuntimeException cause) {
    // The row's own policy, frozen when the effect was written. Not this dispatcher's:
    // there isn't one, because a model call and a tool call are worth trying to different
    // degrees, and a policy edited since must not reach work already queued.
    RetryDecision decision =
        handlers
            .termsFor(effect)
            .retryPolicy()
            .decide(attempt.attemptsMade(), RandomGenerator.getDefault());
    switch (decision) {
      case RetryDecision.RetryAfter(var backoff)
          when clock.instant().plus(backoff).isAfter(attempt.deadline()) -> {
        // A backoff landing past the deadline is not a later attempt, it is a give-up
        // with extra waiting. The policy says how many and how long; whether there is
        // room left for another go is not its question, and this is the one place that
        // has the deadline, the backoff and the clock together to answer it.
        log.warn(
            "[{}] effect {} would back off past its deadline; giving up instead",
            agentType.value(),
            attempt.effectId());
        giveUp(attempt, effect, cause);
      }
      case RetryDecision.RetryAfter(var backoff) -> {
        Instant next = clock.instant().plus(backoff);
        if (effects.reschedule(attempt.effectId(), attempt.attemptsMade(), next)) {
          log.debug(
              "[{}] effect {} will be tried again after {}",
              agentType.value(),
              attempt.effectId(),
              backoff);
        } else {
          log.debug(
              "[{}] effect {} was taken over while attempt {} was failing",
              agentType.value(),
              attempt.effectId(),
              attempt.attemptsMade());
        }
      }
      case RetryDecision.GiveUp _ -> giveUp(attempt, effect, cause);
    }
  }

  /**
   * Tells the agent the work is not happening, and lets go of the row.
   *
   * <p>Reached two ways -- a policy that has had enough, and a backoff with nowhere to land -- and
   * they owe the agent the same thing, so they say it the same way.
   */
  private void giveUp(Attempt attempt, AgentEffect effect, RuntimeException cause) {
    // The handler's own answer, and one that keeps the cause: this attempt ran and threw, so
    // the stored blob -- which says the effect could not be dispatched -- would be false.
    EffectOutcome outcome = handlers.termsFor(effect).failed(cause);
    try {
      callback.deliverOutcome(attempt.agentId(), outcome, attempt.traceContext());
    } catch (RuntimeException e) {
      // Giving up and failing to say so are not the same thing. Retiring the row here would
      // end the attempts and leave the agent waiting forever, so the obligation stays and
      // its watchdog brings it back. It will keep coming back, which is the point: an agent
      // that cannot be told is a fault someone has to see, not one to be tidied away.
      log.error(
          "[{}] could not tell agent {} that effect {} was given up on; keeping it,"
              + " and its watchdog will bring it back",
          agentType.value(),
          attempt.agentId().value(),
          attempt.effectId(),
          e);
      return;
    }
    // Said after the delivery, never before it. The turn ends when the fold commits, and
    // announcing it first is a claim a rollback can still make false.
    log.warn(
        "[{}] gave up on effect {} after {} attempt(s); the turn has ended",
        agentType.value(),
        attempt.effectId(),
        attempt.attemptsMade());
    retire(attempt, "given up on");
  }

  /**
   * Deletes the row, fenced on the attempt that is doing the deleting.
   *
   * <p>Losing the fence is not an error: it means this attempt overran its deadline and another
   * took the work over. Theirs is the live one, and it will retire the row itself.
   */
  private void retire(Attempt attempt, String what) {
    if (effects.complete(attempt.effectId(), attempt.attemptsMade())) {
      log.debug("[{}] effect {} retired, {}", agentType.value(), attempt.effectId(), what);
    } else {
      log.debug(
          "[{}] effect {} was taken over before attempt {} could retire it",
          agentType.value(),
          attempt.effectId(),
          attempt.attemptsMade());
    }
  }
}
