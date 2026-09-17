package org.jwcarman.nessy.engine.harness;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.ObservationCoalescer;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.agent.Outstanding;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.effect.EffectDispatcher;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.store.AgentStateStore;
import org.jwcarman.nessy.engine.store.EffectStore;
import org.jwcarman.nessy.engine.store.HistoryStore;
import org.jwcarman.nessy.engine.trace.Traces;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A harness, and the callback its own effects report through.
 *
 * <p>Both doors are the same object because both do the same thing: take the agent's row lock,
 * fold, and write what the fold decided. They differ only in what they fold. Package-private, so
 * the callback half is invisible to anyone holding the {@link Harness} this returns.
 *
 * <p><b>Transactions are explicit.</b> A {@link TransactionTemplate} rather than
 * {@code @Transactional}, because the annotation only works through a Spring proxy and nothing
 * makes a harness a bean -- {@code DefaultHarnessFactory.create} is an ordinary method call, and
 * its result is transactional or not depending on what the caller did with it afterwards. The
 * failure mode there is a successful write with no transaction, silent until a crash lands between
 * the state and the story. Wrapping the folds here makes the harness correct however it was built,
 * and makes the self-invocation trap -- one method of this object calling another, bypassing the
 * proxy -- stop existing.
 *
 * @param <O> the observation type
 */
final class DefaultHarness<O> implements Harness<O>, AgentEffectCallback, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DefaultHarness.class);

  private final AgentType agentType;

  /**
   * The one caller-supplied strategy this object keeps.
   *
   * <p>The renderer went to the history store because what it makes is stored; a coalescer makes
   * state, and state never leaves the fold. It cannot live in the backlog either -- that is
   * serialised beside the agent, and a function has no stored form -- so it is held here and handed
   * in.
   */
  private final ObservationCoalescer<O> coalescer;

  private final AgentStateStore<O> states;
  private final HistoryStore<O> history;
  private final EffectStore effects;
  private final TransactionTemplate transactions;
  private final Narrator narrator;
  private final Clock clock;
  private final Traces traces;

  private EffectDispatcher dispatcher;

  DefaultHarness(
      AgentType agentType,
      ObservationCoalescer<O> coalescer,
      AgentStateStore<O> states,
      HistoryStore<O> history,
      EffectStore effects,
      TransactionTemplate transactions,
      Narrator narrator,
      Clock clock,
      Traces traces) {
    this.agentType = agentType;
    this.coalescer = coalescer;
    this.states = states;
    this.history = history;
    this.effects = effects;
    this.transactions = transactions;
    this.narrator = narrator;
    this.clock = clock;
    this.traces = traces;
  }

  /**
   * Hands this harness the dispatcher that reports back through it, so that closing one closes the
   * other.
   *
   * <p>Lifecycle only. When and how often that dispatcher looks for work is its own business --
   * this object never learns the interval, because how promptly effects are picked up is not a fact
   * about folding.
   */
  void dispatchWith(EffectDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  /**
   * Stops polling. Inferred by Spring as the bean's destroy method, and there for anyone who built
   * a harness without Spring -- which the transaction template makes a supported thing to do.
   */
  @Override
  public void close() {
    if (dispatcher != null) {
      dispatcher.close();
    }
  }

  /**
   * Admits an observation, and is where a turn's trace begins when it opens one.
   *
   * <p>Named for what it times, which is admitting the observation and nothing more. The work a
   * turn goes on to do happens in effects, seconds or hours later and possibly elsewhere, each
   * carrying this span's identity written down beside it -- so the turn's effects hang beneath this
   * span, but no span can honestly claim to last as long as the turn, and this one does not try.
   */
  @Override
  public void observe(AgentId agentId, O observation) {
    Instant arrivedAt = clock.instant();
    log.debug("[{}] observing for agent {}: {}", agentType.value(), agentId.value(), observation);
    traces.in(
        "nessy.observe",
        new Identity(agentType, agentId),
        () -> {
          fold(
              agentId,
              "observation",
              Trace.CURRENT,
              state -> state.observe(observation, arrivedAt, coalescer));
          return null;
        });
  }

  @Override
  public void terminate(AgentId agentId) {
    log.info("[{}] terminating agent {}", agentType.value(), agentId.value());
    fold(agentId, "termination", Trace.CURRENT, AgentState::terminate);
  }

  @Override
  public void deliverOutcome(AgentId agentId, EffectOutcome outcome, String traceContext) {
    String what = describe(outcome);
    log.debug("[{}] delivering {} to agent {}", agentType.value(), what, agentId.value());
    fold(agentId, what, Trace.inherited(traceContext), state -> state.outcome(outcome));
  }

  /**
   * Which trace the effects of a fold are written into.
   *
   * <p>A turn is one trace, and its effects are siblings in it. So an effect caused by another
   * effect's outcome inherits that effect's stored context rather than capturing the one in force
   * -- which would be the answering effect's own span, and would nest every step inside the last.
   * An observation, or an outcome whose effect carried no context, captures what is in force.
   *
   * @param inherited the answered effect's stored context; null to capture the current one
   * @param fromOutcome whether this fold answers an effect, which decides what a turn taken from
   *     the backlog belongs to: not the turn that just ended, so it starts a trace of its own
   */
  private record Trace(String inherited, boolean fromOutcome) {

    static final Trace CURRENT = new Trace(null, false);

    static Trace inherited(String traceContext) {
      return new Trace(traceContext, true);
    }
  }

  /** The stored context for the effects of one fold. */
  private String traceFor(Trace trace, boolean opensTurn) {
    if (opensTurn && trace.fromOutcome()) {
      // The next turn, taken off the backlog as the last one closed. It belongs to neither the
      // closing turn nor the answering effect, so its first effect starts a trace of its own.
      return null;
    }
    return trace.inherited() != null ? trace.inherited() : traces.capture();
  }

  /**
   * An outcome, with what is known about a failure rather than just that there was one.
   *
   * <p>Worth the extra word in every line it appears on: a permanent failure and a transient one
   * are the difference between an agent that had a blip and one whose every future turn will fail,
   * and without this they read identically.
   */
  private static String describe(EffectOutcome outcome) {
    return switch (outcome) {
      case EffectOutcome.InferenceAnswered _ -> "InferenceAnswered";
      case EffectOutcome.InferenceFailed(var failure) ->
          "InferenceFailed(" + failure.getClass().getSimpleName() + ")";
      case EffectOutcome.InferenceRefused(var category) -> "InferenceRefused(" + category + ")";
      case EffectOutcome.InferenceRequestedActions(var blocks) ->
          "InferenceRequestedActions(" + blocks.size() + " block(s))";
      // Named by call rather than by content: which call was discharged is the fact that
      // moves the agent, and a result's blocks are whatever a tool chose to return.
      case EffectOutcome.ToolSucceeded(CallId callId, _) -> "ToolSucceeded(" + callId + ")";
      case EffectOutcome.ToolFailed(CallId callId, String message) ->
          "ToolFailed(" + callId + ": " + message + ")";
      case EffectOutcome.ToolDenied(CallId callId, String reason, _) ->
          "ToolDenied(" + callId + ": " + reason + ")";
      case EffectOutcome.ToolApproved(CallId callId, _) -> "ToolApproved(" + callId + ")";
    };
  }

  /**
   * Locks, folds, writes -- the only thing that ever changes an agent.
   *
   * <p>The lock is pessimistic on purpose. An optimistic version check would mean two observations
   * arriving at a busy agent race, one commits and the other fails -- and a lost update here is a
   * lost observation. Waiting is the correct behaviour, and the wait is bounded because a fold does
   * no I/O of its own.
   */
  private void fold(
      AgentId agentId, String what, Trace trace, Function<AgentState<O>, Decision<O>> decide) {
    Optional<Folded> outcome =
        Objects.requireNonNull(
            transactions.execute(
                status -> {
                  AgentStateStore.Locked<O> locked = states.lockOrCreate(agentId, clock.instant());

                  // Ignore is not "advance to the same state": it writes nothing at all, not even a
                  // version bump, because a record showing something happening when nothing did is
                  // worse
                  // than no record.
                  if (!(decide.apply(locked.state()) instanceof Decision.Advance<O> advance)) {
                    return Optional.<Folded>empty();
                  }

                  AgentState<O> next = advance.next();
                  states.save(locked, next, clock.instant());

                  // What the fold wrote itself, then the observation it could not write: rendering
                  // belongs to the store, and an opening always follows whatever closed the turn
                  // before it.
                  List<HistoryStore.Appended> appended =
                      new ArrayList<>(history.append(agentId, advance.recorded()));
                  if (advance.opensTurn()) {
                    appended.add(history.open(agentId, advance.opening()));
                  }

                  if (!advance.effects().isEmpty()) {
                    // Captured here, inside the fold's transaction, because this is the last
                    // moment the emitting trace is still in force. Whoever performs these rows
                    // will have nothing to inherit from.
                    String traceContext = traceFor(trace, advance.opensTurn());
                    for (AgentEffect effect : advance.effects()) {
                      effects.insert(agentId, effect, clock.instant(), traceContext);
                    }
                  }
                  return Optional.of(
                      new Folded(
                          describe(locked.state()),
                          describe(next),
                          List.copyOf(appended),
                          names(advance.effects()),
                          Narrations.of(
                              advance.recorded(), opening(advance), advance.effects(), next)));
                }),
            "a fold always returns");

    // Past this line the transaction has committed, so everything below is true. Logging the
    // transition from inside would announce a fold that a rollback could still undo -- a lock
    // timeout, a constraint violation on the append, a dropped connection -- and leave the log
    // and the database telling different stories. The log is the one somebody reads first.
    if (outcome.isEmpty()) {
      log.debug("[{}] agent {}: ignoring redelivered {}", agentType.value(), agentId.value(), what);
      return;
    }
    Folded folded = outcome.get();
    if (!folded.effects().isEmpty() && dispatcher != null) {
      // Committed, so the rows are there to be claimed. Asked for now rather than left for the
      // next poll, which would make every step of a turn wait out the interval.
      dispatcher.nudge();
    }
    log.debug(
        "[{}] agent {}: {} + {} -> {} | recorded {} | effects {}",
        agentType.value(),
        agentId.value(),
        folded.from(),
        what,
        folded.to(),
        folded.recorded(),
        folded.effects());
    // Announced on the same side of the commit as the log, and for the same reason: a watcher
    // told about a fold a rollback could still undo would be told something untrue. An
    // announcement lost to a crash here costs a line; the fact is in the story either way.
    for (AgentEvent event : folded.narration()) {
      announce(agentId, event);
    }
  }

  /** Says one thing to whoever is watching. The narrator isolates them from each other. */
  private void announce(AgentId agentId, AgentEvent event) {
    narrator.narrate(agentType, agentId, event);
  }

  /**
   * The turn this fold opened, if it opened one.
   *
   * <p>Carries the caller's own observation as it reads, not the rendered form the model is shown.
   * They are the same thing under the default renderer, and where they differ this is the more
   * useful of the two: a watcher is showing somebody what they said, not what was made of it.
   * {@code <O>} does not escape -- only a string does.
   */
  private AgentEvent.TurnStarted opening(Decision.Advance<O> advance) {
    return advance.opensTurn()
        ? new AgentEvent.TurnStarted(
            advance.opening().seq().opensTurn(), String.valueOf(advance.opening().observation()))
        : null;
  }

  /** What one committed fold did, kept only long enough to be logged once it is true. */
  private record Folded(
      String from,
      String to,
      List<HistoryStore.Appended> recorded,
      List<String> effects,
      List<AgentEvent> narration) {}

  /**
   * A state, and how much is waiting behind it.
   *
   * <p>The depth is the whole point: without it, one observation queued mid-turn and forty look
   * identical in the log, and the fold that closes a turn while opening the next one -- the most
   * interesting thing this does -- reads as a state that did not change.
   *
   * <p>An exhaustive switch rather than {@code toString()}, which would print the queued
   * observations themselves: noisy, and a way for a log file to end up holding whatever callers put
   * in an observation.
   */
  private static String describe(AgentState<?> state) {
    return switch (state) {
      case AgentState.Idle<?>(Seq lastSeq) -> "Idle(seq=" + lastSeq + ")";
      case AgentState.Inferring<?> calling ->
          "Inferring(turn=" + calling.turn() + ", backlog=" + calling.backlog().size() + ")";
      // Counted by phase, because "three outstanding" hides the difference between three
      // questions nobody has answered and three tools already touching the world.
      case AgentState.AwaitingActions<?> awaiting ->
          "AwaitingActions(turn="
              + awaiting.turn()
              + ", awaitingApproval="
              + awaiting.countOf(Outstanding.Phase.AWAITING_APPROVAL)
              + ", running="
              + awaiting.countOf(Outstanding.Phase.RUNNING)
              + ", backlog="
              + awaiting.backlog().size()
              + ")";
      case AgentState.Terminated<?>(Seq lastSeq) -> "Terminated(seq=" + lastSeq + ")";
    };
  }

  private static List<String> names(List<?> values) {
    return values.stream().map(value -> value.getClass().getSimpleName()).toList();
  }
}
