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
package org.jwcarman.nessy.engine.harness.direct;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.OutputReader;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TerminationOutcome;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnPolicy;
import org.jwcarman.nessy.api.TurnStats;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.agent.OutstandingAction;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.core.TurnTally;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.effect.EffectTerms;
import org.jwcarman.nessy.engine.observability.EffectSpans;
import org.jwcarman.nessy.engine.observability.Identity;
import org.jwcarman.nessy.engine.observability.ObservedInferenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A turn, run as a sequence of short locked steps with the slow work performed between them.
 *
 * <p><b>N short locked transactions, never one held across a turn.</b> Each step -- reconstitute
 * the agent from {@link AgentEvents#sinceLastTurnStarted}, decide, append -- happens under {@link
 * Locks#withLock}, which {@code JdbcRowLocks} turns into one short database transaction. The effect
 * a step decided on -- an inference, an approval, a tool call -- is then performed with the lock
 * released and no transaction open, and its outcome becomes the next step's command. No state is
 * carried across a release: every step re-reads the agent under its own lock (design record {@code
 * 2026-09-25-locks-as-plumbing}, §3).
 *
 * <p><b>The phase check is the guard, not the lock.</b> A hold is now a read, an append and a
 * commit -- a few milliseconds -- against a turn that runs for seconds, so a lock refusal would
 * catch a vanishing fraction of collisions. This door therefore always waits (§3a) rather than
 * being refused, and what decides whether a caller may proceed is what the reconstituted state says
 * once the wait is over. An agent that reconstitutes {@link AgentState.Terminal} is refused; one
 * reconstituted to anything but {@link AgentState.Idle} -- unless {@link #recoverToIdle} finds the
 * thing it is waiting on overdue -- is told {@link Outcome.Busy}.
 *
 * <p><b>Lazy recovery, by deadline, never by phase age.</b> A dead process leaves an agent on a
 * busy phase forever; the next caller to arrive reads not just the phase but when it started
 * ({@link AgentEvents#writtenAt}), compares that against the deadline {@link EffectHandlers} would
 * enforce for the thing being waited on, and -- if it has passed -- discharges it with {@link
 * EffectTerms#undispatchable()} in the same locked step before going on with its own turn (§4d).
 * Recovery performs nothing itself: discharging the last outstanding call can hand back an {@code
 * Infer} effect ({@link AgentState.AwaitingActions#discharge}), which is overdue by construction
 * the moment it appears, so {@link #recoverToIdle} discharges that too, in the same step, until the
 * agent is {@link AgentState.Idle}.
 *
 * <p>Note what is absent: no backlog, no coalescing, no claims, no leases, no deferral, and now no
 * outer lock either. Not forbidden -- nothing in this world can produce them.
 *
 * <p><b>What performs an effect is {@link EffectHandlers}, the same one the queued door dispatches
 * through.</b> This door does not know how to call a model, ask an approver or run a tool -- it
 * knows how to wait for an answer with a deadline and what to do with {@link Awaited}. Everything
 * else -- assembling context, minting a reply address, resolving a call back out of the story -- is
 * the handlers' job, and doing it once means a span the queued door produces, this door produces
 * too.
 *
 * <p><b>Every effect runs on a virtual thread and is waited for with its own deadline.</b> An
 * inference, a tool call and a blocking approver each advertise a timeout ({@link
 * org.jwcarman.nessy.api.InferenceConfig#timeout}, {@link
 * org.jwcarman.nessy.api.tool.ToolConfig#timeout}, {@link
 * org.jwcarman.nessy.api.tool.ApproverConfig#timeout}) that this door used to accept and ignore;
 * {@link #within} is what makes them true. Cancelling the {@link Future} rather than the work
 * itself: the deadline this door enforces is what recovery can rely on, and it is not the same
 * thing as the work actually stopping -- see the caveat on {@link #within}.
 *
 * <p><b>A batch's effects are performed concurrently, never one at a time.</b> {@link
 * AgentState.AwaitingActions#opening} stamps every call in a phase with the same {@code since} --
 * the {@code ActionsRequested} seq -- so the lazy deadline {@link #recoverToIdle} would compare
 * against assumes every call in the batch started at roughly the same moment. Performing a batch
 * serially breaks that assumption: the third of three calls would not even begin until the first
 * two had finished, while its deadline was measured from when the batch opened. {@link #drive} fans
 * a batch's effects out onto {@link #effects} instead, bounded by {@link #inFlight}, so {@code
 * since} and the actual attempt coincide for a batch that fits inside the bound.
 *
 * <p><b>{@link #inFlight} does not make that coincidence exact by itself.</b> Bounded to {@link
 * org.jwcarman.nessy.api.DirectHarnessConfig#maxInFlight} permits and shared by every {@code ask}
 * this harness ever serves, it can make an effect queue for a permit behind work belonging to a
 * different agent's turn entirely -- a gap that can be far larger than one turn's own batch. {@link
 * #perform} is what closes that gap for good: rather than handing a freshly-permitted effect a full
 * fresh timeout, it measures what is left of the budget {@code since} started and waits only for
 * that -- and discharges the effect as {@link EffectTerms#undispatchable()}, never even calling
 * {@link EffectHandlers#perform}, when that budget is already spent by the time a permit frees.
 * That is what keeps {@link EffectTerms#undispatchable()}'s wording ("so it was not run") true
 * regardless of how long an effect waited for a permit.
 *
 * <p>Folding stays serialized regardless of any of this: each completion is folded, one at a time,
 * in its own short {@link Locks#withLock} step, as soon as it arrives -- never waiting for the rest
 * of the batch.
 */
public final class DefaultDirectHarness<I, O> implements DirectHarness<I, O> {

  /**
   * This door tries a model call once. Its retry policy is stored and read back but not honoured,
   * so no work here is ever attempted twice and there is never anything for a turn to be told about
   * earlier tries. Named rather than inlined five times, so a reader meets the reason once.
   */
  private static final List<FailedAttempt> NO_ATTEMPTS = List.of();

  private static final Logger LOG = LoggerFactory.getLogger(DefaultDirectHarness.class);

  /**
   * Where events, content and the lock all come from -- held whole rather than torn into fields of
   * its own, because those stores are one decision chosen together, and a harness holding three of
   * its own fields would be exactly what let them drift apart.
   */
  private final DirectBackend backend;

  private final AgentType agentType;
  private final InputRenderer<I> renderer;

  /**
   * What a deadline recovery enforces is measured from -- the same clock {@link EffectHandlers}'
   * own collaborators use, so the number recovery compares against {@link AgentEvents#writtenAt} is
   * the same number a live effect's own timeout would have used.
   */
  private final Clock clock;

  /**
   * How an answered event becomes {@code O}, bound once rather than per call: for an unstructured
   * harness this reads the text of the answer, for a bound one it parses the text into the shape
   * the harness was made with.
   *
   * <p>Handed in rather than chosen here: which one applies is a fact only the factory's two {@code
   * create} methods can state, since only they know whether {@code O} is {@link String} or a bound
   * shape -- inside this generic class {@code O} is neither. It arrives final, because a harness
   * whose reader could be set afterwards would answer its first question with a null.
   */
  private final OutputReader<O> reading;

  /**
   * The handle everything watching this agent is reached through.
   *
   * <p>The same abstraction the queued door talks to, and for the same reasons: telling listeners
   * in order, off this thread, isolated from each other. This door once kept its own list and told
   * them inline, which meant no listener an application registered could reach it at all, and a
   * slow one sat between the caller and their answer.
   */
  private final Narrator narrator;

  /**
   * What performs an effect once the fold has decided one is owed -- the model call, the approval
   * question, the tool call -- and what each is worth. Built by the factory exactly as the queued
   * door's is, so the two doors cannot describe a call, an approval or an inference differently.
   */
  private final EffectHandlers handlers;

  /**
   * One virtual thread per effect, so {@link #within} can wait for one with a deadline and cancel
   * it -- interrupting the thread -- when that deadline passes.
   *
   * <p>The factory's, not this harness's. A thread-per-task executor over virtual threads holds
   * nothing while idle, so there is nothing for a harness to own here and nothing for it to release
   * -- and a harness that owned one would have to be closeable, which would mean the factory
   * keeping a list of every harness it ever made in order to close them.
   */
  private final ExecutorService effects;

  /**
   * How many effects this harness may have running at once, across every {@link #ask} call it is
   * serving -- not scoped per turn, and deliberately so.
   *
   * <p><b>Harness-wide rather than per turn.</b> A per-turn semaphore was tried first and reverted:
   * twenty concurrent turns would each mint their own full set of permits, so it bounded nothing
   * that mattered and protected nothing -- the one thing worth bounding here is how much load this
   * harness, as a whole, puts on a provider or a downstream service. A single caller's runaway
   * batch is the narrower problem, and a harness-wide bound still catches it; the reverse is not
   * true.
   *
   * <p><b>Sized differently than the queued door's {@link
   * org.jwcarman.nessy.api.EffectsConfig#maxInFlight}, despite the shared name.</b> That one bounds
   * a poller draining a durable queue, where nothing blocks and work simply waits its turn a little
   * longer -- four is plenty. This one bounds callers who are synchronously blocked on {@link #ask}
   * waiting for their own answer, and {@link AgentEffect.Infer} is admitted through it too, so the
   * very first batch of every turn takes a permit before that turn can even begin. A harness-wide
   * cap of four would therefore cap this whole harness at four concurrent turns and make a fifth
   * caller wait before its own turn could start -- a surprising thing to discover behind a web
   * endpoint. {@link org.jwcarman.nessy.api.DirectHarnessConfig#maxInFlight}'s default of 64 is a
   * real ceiling against a harness gone runaway while staying far above ordinary concurrency, and
   * on virtual threads an unused permit costs nothing.
   */
  private final ObservationRegistry observations;

  private final TurnPolicy turnPolicy;

  private final Semaphore inFlight;

  public DefaultDirectHarness(
      DirectBackend backend,
      AgentType agentType,
      Clock clock,
      InputRenderer<I> renderer,
      OutputReader<O> reading,
      Narrator narrator,
      EffectHandlers handlers,
      ExecutorService effects,
      int maxInFlight,
      ObservationRegistry observations,
      TurnPolicy turnPolicy) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.agentType = agentType;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.reading = Objects.requireNonNull(reading, "reading must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.handlers = Objects.requireNonNull(handlers, "handlers must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    this.inFlight = new Semaphore(maxInFlight);
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
    this.turnPolicy = Objects.requireNonNull(turnPolicy, "turn policy must not be null");
  }

  @Override
  public Outcome<O> ask(AgentId agent, I input) {
    if (observations.isNoop()) {
      return asking(agent, input);
    }
    // semconv's invoke_agent: the span an agent's work belongs to, named the way its model calls
    // and tool calls already are. Identity goes on here rather than being read off a parent,
    // because this IS the parent -- everything below inherits it from this observation.
    Observation observation =
        Observation.createNotStarted(ObservedInferenceProvider.DURATION, observations)
            .contextualName("invoke_agent " + agentType.value())
            .lowCardinalityKeyValue("gen_ai.operation.name", "invoke_agent");
    new Identity(agentType, agent).on(observation, null);
    return observation.observe(() -> asking(agent, input));
  }

  /**
   * One turn, inside one observation.
   *
   * <p><b>The span this runs in is what makes the rest of them a tree.</b> Everything already
   * observed here -- the model call, each tool, the context assembly -- opens its own observation
   * and reads identity off whatever is current when it starts. With nothing enclosing them, each
   * became a root of its own: the pieces were all there and none of them were related, and the
   * model call carried no agent tag because there was no parent to read one from.
   *
   * <p>The queued door has always had this. It writes its trace context into the effect row and
   * restores it when the effect runs, because its effects run later and possibly in another
   * process. Here they run on this door's own virtual threads, so the executor carries the
   * observation across instead of a column doing it -- see {@code ContextExecutorService} where
   * that executor is made.
   */
  private Outcome<O> asking(AgentId agent, I input) {
    // renderer.render is pure and can run outside the lock; only the payload write it feeds has to
    // happen inside the first locked step, after the phase check, so a declined caller writes no
    // payload at all (§3, §4d).
    List<Block.InputContent> rendered = renderer.render(input);
    StepResult<O> first =
        backend.locks().withLock(Locks.TURN, agentType, agent, () -> beginTurn(agent, rendered));
    return switch (first) {
      case StepResult.Declined<O> declined -> declined.outcome();
      case StepResult.Advanced<O> advanced -> drive(agent, advanced.turn(), advanced.effects());
    };
  }

  @Override
  public TerminationOutcome terminate(AgentId agent) {
    // The core only takes Terminate from Idle: asking while a turn is running is declined by the
    // fold itself (Decision.ignore()), and waiting for the turn is not this door's habit. What the
    // fold decided is the answer -- an empty decision IS the refusal, so nothing needs to re-derive
    // which states accept ending.
    return backend
        .locks()
        .withLock(
            Locks.TURN,
            agentType,
            agent,
            () -> {
              AgentState state = reconstitute(agent);
              if (state instanceof AgentState.Terminal) {
                return new TerminationOutcome.AlreadyEnded();
              }
              Decision decision =
                  state.execute(new AgentCommand.Terminate(), turnPolicy, clock.instant());
              if (decision.events().isEmpty()) {
                LOG.debug(
                    "[{}] agent {} is mid-turn; ending it was refused", agentType.value(), agent);
                return new TerminationOutcome.Busy();
              }
              backend.events().append(agentType, agent, decision.events(), state.seq());
              decision.events().forEach(event -> narrate(agent, event));
              return new TerminationOutcome.Ended();
            });
  }

  /**
   * The first locked step of a turn: the phase check, lazy recovery, and -- only once the agent is
   * genuinely {@link AgentState.Idle} -- the payload write and {@code TurnStarted}.
   *
   * <p>A {@link AgentState.Terminal} agent is refused outright, never told {@link Outcome.Busy};
   * that refusal is what the door already gave before this step existed. Anything else that is not
   * {@link AgentState.Idle} goes through {@link #recoverToIdle}, which either proves the agent idle
   * -- because whatever it was waiting on had already passed its own deadline -- or reports that it
   * is genuinely busy.
   */
  private StepResult<O> beginTurn(AgentId agent, List<Block.InputContent> rendered) {
    AgentState state = reconstitute(agent);
    if (state instanceof AgentState.Terminal) {
      LOG.debug("[{}] agent {} has ended; the question is refused", agentType.value(), agent);
      // No turn opened, so there is nothing this agent did to report. An ended agent refusing a
      // question is the agent's state, not a turn's cost.
      return StepResult.declined(
          new Outcome.Refused<>("terminated", TurnStats.opened(clock.instant())));
    }
    return switch (recoverToIdle(agent, state)) {
      case RecoveryOutcome.Busy() -> StepResult.declined(new Outcome.Busy<>());
      case RecoveryOutcome.Recovered(AgentState.Idle idle) -> {
        Payloads content = backend.payloads().forAgent(agent);
        Decision decision =
            idle.execute(new AgentCommand.StartTurn(content.put(rendered), clock.instant()));
        backend.events().append(agentType, agent, decision.events(), idle.seq());
        decision.events().forEach(event -> narrate(agent, event));
        TurnId turn = ((AgentEvent.TurnStarted) decision.events().getFirst()).turn();
        yield StepResult.advanced(turn, timedEffectsOf(decision));
      }
    };
  }

  /**
   * A single locked step for a command that continues a turn this caller is already driving -- the
   * completion of an inference, an approval or a tool call.
   *
   * <p>Reconstitutes independently of whatever this caller last saw, exactly as every other step
   * does. A {@link AgentState.Terminal} read here is not this door's problem to solve: nothing this
   * caller does next can matter to an agent nobody else can reach any more, so the step is a no-op
   * rather than the loud refusal {@link AgentState.Terminal#execute} would otherwise throw.
   */
  private Decision executeStep(AgentId agent, AgentCommand command) {
    AgentState state = reconstitute(agent);
    if (state instanceof AgentState.Terminal) {
      return Decision.ignore();
    }
    Decision decision = state.execute(command, turnPolicy, clock.instant());
    backend.events().append(agentType, agent, decision.events(), state.seq());
    decision.events().forEach(event -> narrate(agent, event));
    return decision;
  }

  /**
   * Performs every effect of the first batch, and every effect each following batch produces in
   * turn, until a batch produces nothing more to do.
   *
   * <p><b>The final read is by turn, not by "whatever this caller last saw".</b> {@link #outcome}
   * re-reads the whole stream and looks for {@code turn}'s own terminal event rather than trusting
   * the last {@link Decision} this loop produced: a step near the end of a turn can be genuinely
   * {@link Decision#ignore()} -- a slow-but-alive original whose own completion lost a race with a
   * recovery that already discharged the same phase (§4d) -- and that caller still owes its caller
   * an answer, which is whatever the stream says {@code turn} itself came to, not an exception.
   *
   * <p>Every batch this one {@code ask} call performs draws its permits from {@link #inFlight}, the
   * one semaphore this whole harness shares -- see its own javadoc for why the scope is
   * harness-wide rather than per call.
   */
  private Outcome<O> drive(AgentId agent, TurnId turn, List<TimedEffect> firstBatch) {
    List<TimedEffect> batch = firstBatch;
    while (!batch.isEmpty()) {
      batch = performBatch(agent, batch);
    }
    return outcome(agent, turn);
  }

  /**
   * Performs one batch's effects concurrently -- bounded by {@link #inFlight} -- and folds each
   * completion into its own short locked step as soon as it arrives, rather than waiting for the
   * whole batch: a fast tool's result is written down promptly instead of sitting behind whatever
   * in the same batch is slowest.
   *
   * <p><b>Folding itself stays serialized.</b> Completions are drained and folded one at a time, on
   * this one thread, so two folds never run at once even though the effects that produced them ran
   * concurrently -- {@link #executeStep}'s own {@link Locks#withLock} is the transaction boundary,
   * and nothing here calls it from more than one thread.
   */
  private List<TimedEffect> performBatch(AgentId agent, List<TimedEffect> batch) {
    CompletionService<AgentCommand> completions = new ExecutorCompletionService<>(effects);
    for (TimedEffect timed : batch) {
      submit(completions, agent, timed);
    }
    List<TimedEffect> next = new ArrayList<>();
    for (int i = 0; i < batch.size(); i++) {
      AgentCommand command = take(completions);
      Decision decision =
          backend.locks().withLock(Locks.TURN, agentType, agent, () -> executeStep(agent, command));
      next.addAll(timedEffectsOf(decision));
    }
    return next;
  }

  /**
   * Admits one effect to {@link #inFlight} and hands it to {@code completions}, releasing the
   * permit the moment the effect itself is done -- not when its completion is taken, which can be
   * later still.
   *
   * <p>Acquiring here, on the thread driving the turn, rather than inside the submitted task:
   * blocking a caller who is already blocked in {@link #ask} costs nothing, and acquiring inside
   * the task would mean every effect is already "started" -- {@link #perform}'s clock included --
   * before it ever gets a permit. {@link #perform} does not rely on that not happening any more --
   * it measures the remaining budget itself -- but starting the clock before the permit is taken
   * would still be the wrong fact to report if this ever changes back.
   */
  private void submit(
      CompletionService<AgentCommand> completions, AgentId agent, TimedEffect timed) {
    acquire(inFlight);
    completions.submit(
        () -> {
          try {
            return perform(agent, timed.effect(), timed.since());
          } finally {
            inFlight.release();
          }
        });
  }

  private static void acquire(Semaphore inFlight) {
    try {
      inFlight.acquire();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for in-flight capacity", interrupted);
    }
  }

  /**
   * One effect, tagged with the seq of the single event whose fold produced it -- the same "since"
   * {@link OutstandingAction} and {@link AgentState.Inferring} themselves carry, and the one {@link
   * #recoverToIdle} would replay its way back to for this exact call. Tracked here, locally, rather
   * than trusted from a field on {@link AgentEffect} itself: {@link
   * AgentEffect.CallTool#requestSeq} names the {@code ActionsRequested} entry holding the call's
   * arguments, which is NOT the seq {@link OutstandingAction#since} moves to once the call is
   * {@code RUNNING} -- that is the {@code ToolApproved} seq instead. Reusing {@code requestSeq}
   * here would silently reintroduce the disagreement this whole fix exists to remove; computing
   * {@code since} from the decision that just produced the effect cannot disagree with replay,
   * because it is exactly what replay does.
   *
   * @param since the seq of the event {@code effect} was born alongside
   */
  private record TimedEffect(AgentEffect effect, Seq since) {}

  /**
   * Tags every effect one {@link Decision} produced with that decision's own event -- there is
   * always exactly one event behind any effects this engine emits, {@link Decision#of} and every
   * call site that builds one agree on that, so there is no ambiguity about which seq an effect was
   * born at.
   */
  private static List<TimedEffect> timedEffectsOf(Decision decision) {
    if (decision.effects().isEmpty()) {
      return List.of();
    }
    Seq since = decision.events().getFirst().seq();
    return decision.effects().stream().map(effect -> new TimedEffect(effect, since)).toList();
  }

  /**
   * The next finished effect of the current batch, in completion order rather than submission order
   * -- which is the whole point: a fast one is folded without waiting on a slow sibling.
   *
   * <p>{@link #perform} never lets an exception escape -- {@link #within} turns everything it
   * catches into a failed {@link AgentCommand} -- so {@link ExecutionException} here can only mean
   * a defect in this class rather than anything a provider, a tool or an approver did.
   */
  private static AgentCommand take(CompletionService<AgentCommand> completions) {
    try {
      return completions.take().get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for an effect", interrupted);
    } catch (ExecutionException broken) {
      throw new IllegalStateException("an effect task failed unexpectedly", broken.getCause());
    }
  }

  /**
   * Discharges whatever an out-of-phase agent is waiting on, for as long as each thing it finds is
   * overdue, and reports whether that leaves the agent {@link AgentState.Idle}.
   *
   * <p><b>Recovery performs nothing.</b> Every step here is fold and append, exactly like any other
   * step this door takes -- no model is called, no tool runs, no person is asked, on this caller's
   * behalf or anyone else's. {@link AgentState.AwaitingActions#discharge} can hand back an {@code
   * Infer} effect once its last call is gone; nobody is going to perform that inference, so the
   * loop below reaches it on its next pass and discharges it too (design record §4d).
   *
   * <p><b>Which is why the deadline check applies only to the phase this pass FIRST found.</b> That
   * phase is the one that distinguishes an abandoned agent from a live one, and a caller who finds
   * it inside its deadline is told {@link Outcome.Busy}. A phase recovery itself produced is a
   * different thing entirely: it was written a moment ago, so a deadline would call it live, and
   * nothing holds it -- the effect it implies was decided here and this method performs nothing.
   * Checked, it would leave the agent {@code Inferring} with nobody to answer for it until the
   * whole inference timeout had run, and the turn without the terminal event its original caller is
   * waiting to read.
   */
  private RecoveryOutcome recoverToIdle(AgentId agent, AgentState state) {
    AgentState current = state;
    boolean dischargedSomething = false;
    while (!(current instanceof AgentState.Idle)) {
      Optional<AgentCommand> discharge = discharging(agent, current, dischargedSomething);
      if (discharge.isEmpty()) {
        return new RecoveryOutcome.Busy();
      }
      dischargedSomething = true;
      Decision decision = current.execute(discharge.get(), turnPolicy, clock.instant());
      backend.events().append(agentType, agent, decision.events(), current.seq());
      decision.events().forEach(event -> narrate(agent, event));
      current = current.applyAll(decision.events());
    }
    return new RecoveryOutcome.Recovered((AgentState.Idle) current);
  }

  /**
   * The same question, and the one exception to it: a phase this pass produced itself is not
   * checked against a clock at all.
   */
  private Optional<AgentCommand> discharging(
      AgentId agent, AgentState state, boolean alreadyDischarged) {
    // An inference reached after this pass has already discharged something can only have come
    // from AwaitingActions giving up its last call, because discharging an inference leaves the
    // agent idle. So this inference is recovery's own: nothing holds it, and it is overdue by
    // construction rather than by the clock.
    if (alreadyDischarged && state instanceof AgentState.Inferring reopened) {
      return Optional.of(undispatchable(reopened.turn(), handlers.termsFor(infer(reopened))));
    }
    return overdueDischarge(agent, state);
  }

  /**
   * What {@code state} is waiting on, discharged with {@link EffectTerms#undispatchable()} if --
   * and only if -- it has already passed its own deadline; empty if it has not, which is what makes
   * the caller's answer {@link Outcome.Busy} rather than a recovery.
   */
  private Optional<AgentCommand> overdueDischarge(AgentId agent, AgentState state) {
    return switch (state) {
      case AgentState.Inferring inferring -> overdueInference(agent, inferring);
      case AgentState.AwaitingActions awaiting -> overdueCall(agent, awaiting);
      // Neither is reachable: the loop in recoverToIdle stops on Idle, and beginTurn refuses a
      // Terminal agent before recovery is consulted. Stated as arms rather than a default so that
      // a new phase on AgentState stops this compiling instead of reaching the throw.
      case AgentState.Idle _, AgentState.Terminal _ ->
          throw new IllegalStateException("recovery reached an unexpected phase: " + state);
    };
  }

  private Optional<AgentCommand> overdueInference(AgentId agent, AgentState.Inferring inferring) {
    EffectTerms terms = handlers.termsFor(infer(inferring));
    Instant started = backend.events().writtenAt(agentType, agent, inferring.seq());
    return isOverdue(started, terms)
        ? Optional.of(undispatchable(inferring.turn(), terms))
        : Optional.empty();
  }

  /**
   * The inference an {@link AgentState.Inferring} agent is waiting on, so its terms can be read.
   */
  private static AgentEffect.Infer infer(AgentState.Inferring inferring) {
    return new AgentEffect.Infer(inferring.turn());
  }

  /** Work nobody performed, as the completion of the turn that is owed it. */
  private static AgentCommand undispatchable(TurnId turn, EffectTerms terms) {
    return EffectOutcomes.command(turn, terms.undispatchable(), NO_ATTEMPTS);
  }

  /** The first outstanding call whose own deadline has passed, if there is one. */
  private Optional<AgentCommand> overdueCall(AgentId agent, AgentState.AwaitingActions awaiting) {
    for (OutstandingAction outstanding : awaiting.outstanding().values()) {
      AgentEffect effect = effectFor(awaiting.turn(), awaiting.requestSeq(), outstanding);
      EffectTerms terms = handlers.termsFor(effect);
      Instant started = backend.events().writtenAt(agentType, agent, outstanding.since());
      if (isOverdue(started, terms)) {
        return Optional.of(undispatchable(awaiting.turn(), terms));
      }
    }
    return Optional.empty();
  }

  /**
   * The effect one outstanding call implies, so its terms can be looked up the way §4c does.
   *
   * <p>Built from the call's id and tool name, which is all the state keeps of it.
   */
  private static AgentEffect effectFor(TurnId turn, Seq requestSeq, OutstandingAction outstanding) {
    return switch (outstanding.phase()) {
      case AWAITING_APPROVAL ->
          new AgentEffect.Approve(turn, requestSeq, outstanding.callId(), outstanding.toolName());
      case RUNNING ->
          new AgentEffect.CallTool(turn, requestSeq, outstanding.callId(), outstanding.toolName());
    };
  }

  private boolean isOverdue(Instant started, EffectTerms terms) {
    return started.plus(terms.timeout()).isBefore(clock.instant());
  }

  /**
   * Where an idle state has to sit for the first replayed event to be accepted.
   *
   * <p>The fold refuses an event at or before its own position, so replay starts one short of the
   * turn it is about to apply. Nothing is stored to say where that is -- the events carry it.
   */
  private static Seq previous(Seq seq) {
    return seq.value() <= 1 ? Seq.NONE : new Seq(seq.value() - 1);
  }

  /** The agent, rebuilt from its last turn alone -- one read every locked step starts with. */
  private AgentState reconstitute(AgentId agent) {
    List<AgentEvent> lastTurn = backend.events().sinceLastTurnStarted(agentType, agent);
    Seq from = lastTurn.isEmpty() ? Seq.NONE : previous(lastTurn.getFirst().seq());
    return AgentState.idle(from).applyAll(lastTurn);
  }

  /**
   * What one locked step of a turn's opening command came to: either a caller who was told no --
   * {@link Outcome.Refused} or {@link Outcome.Busy} -- or a turn genuinely under way.
   */
  private sealed interface StepResult<O> {

    record Declined<O>(Outcome<O> outcome) implements StepResult<O> {}

    record Advanced<O>(TurnId turn, List<TimedEffect> effects) implements StepResult<O> {}

    static <O> StepResult<O> declined(Outcome<O> outcome) {
      return new Declined<>(outcome);
    }

    static <O> StepResult<O> advanced(TurnId turn, List<TimedEffect> effects) {
      return new Advanced<>(turn, effects);
    }
  }

  /**
   * What {@link #recoverToIdle} came to: still busy, or an {@link AgentState.Idle} to proceed on.
   */
  private sealed interface RecoveryOutcome {

    record Busy() implements RecoveryOutcome {}

    record Recovered(AgentState.Idle state) implements RecoveryOutcome {}
  }

  /**
   * Where the outside world happens: everything slow, everything non-deterministic -- and now,
   * everything bounded by what is left of the deadline the effect's own terms name, measured from
   * {@code since} rather than from the moment this call happens to run.
   *
   * <p><b>The remaining budget, never a fresh one.</b> {@link #inFlight} is harness-wide, so an
   * effect can sit behind work belonging to an entirely different agent's turn before it ever gets
   * a permit -- by the time it does, part or all of its own timeout may already be spent. Handing
   * it a fresh {@link EffectTerms#timeout()} here would let the in-process deadline and the one
   * {@link #recoverToIdle} enforces disagree again, which is the exact defect this class exists to
   * remove. So this asks the same question recovery would ask first -- {@link #isOverdue} against
   * {@code since} -- and if the budget is already gone, discharges the effect as {@link
   * EffectTerms#undispatchable()} without ever calling {@link EffectHandlers#perform}: nothing here
   * is about to run it, so {@code undispatchable()}'s wording ("so it was not run") stays true.
   * Otherwise it waits for exactly what remains, never more.
   *
   * <p>What performs the effect, once there is budget left to spend, is {@link
   * EffectHandlers#perform}, the same call the queued door's dispatcher makes. The only things this
   * door adds are {@link #within}'s deadline and the one arm {@link EffectHandlers#perform} can
   * return that a queued row can park and this door cannot: {@link Awaited.Deferred}. Nothing here
   * is coming back for that answer, so it is a failure by the effect's own terms rather than a
   * denial this door has no standing to hand out.
   *
   * @param since the seq {@link #isOverdue} measures this effect's budget from -- see {@link
   *     TimedEffect} for why it travels beside the effect rather than being read off it
   */
  private AgentCommand perform(AgentId agent, AgentEffect effect, Seq since) {
    EffectTerms terms = handlers.termsFor(effect);
    // The turn comes off the effect, which is the only thing that knows it: this loop's own turn
    // is the same one, but an outcome has to name the turn that ASKED for the work rather than
    // whichever turn the agent happens to be in by the time the answer lands.
    TurnId turn = effect.turn();
    Instant started = backend.events().writtenAt(agentType, agent, since);
    if (isOverdue(started, terms)) {
      return undispatchable(turn, terms);
    }
    Duration remaining = Duration.between(clock.instant(), started.plus(terms.timeout()));
    return within(
        agent,
        effect,
        turn,
        remaining,
        terms,
        () ->
            switch (handlers.perform(agent, effect)) {
              case Awaited.Ready<EffectOutcome>(EffectOutcome outcome) ->
                  EffectOutcomes.command(turn, outcome, NO_ATTEMPTS);
              case Awaited.Deferred<EffectOutcome> _ ->
                  EffectOutcomes.command(
                      turn,
                      terms.failed(
                          new IllegalStateException(
                              "the effect was deferred, and nothing here can wait for it")),
                      NO_ATTEMPTS);
            });
  }

  /**
   * Performs {@code work} on its own virtual thread and waits for it no longer than {@code budget}
   * -- what {@link #perform} measured as left of the effect's own deadline, not a fresh {@link
   * EffectTerms#timeout()}.
   *
   * <p><b>The deadline this enforces is correctness; it is not cleanup.</b> {@link
   * Future#cancel(boolean)} interrupts the thread -- unlike {@link
   * java.util.concurrent.CompletableFuture#cancel}, whose javadoc says {@code
   * mayInterruptIfRunning} "has no effect in this implementation," which is exactly why this waits
   * on a plain {@link Future} from an {@link ExecutorService} rather than a {@code
   * CompletableFuture}. But an interrupted virtual thread blocked in a provider's own transport
   * does not necessarily abandon its socket read the moment it is asked to: the transport's own
   * timeout is what actually releases the connection. So this method's deadline is what the caller
   * and the agent's phase can rely on; the transport's is what releases the resource.
   *
   * <p>An expiry is delivered as {@link EffectTerms#failed}, never {@link
   * EffectTerms#undispatchable()}: the work was attempted on this thread in this process, and
   * nobody observed how it came out -- exactly what {@code failed} is documented for. {@code
   * undispatchable()} is for work nobody performed at all, which is what {@link #perform} itself
   * now decides before this method is ever called.
   */
  private AgentCommand within(
      AgentId agent,
      AgentEffect effect,
      TurnId turn,
      Duration budget,
      EffectTerms terms,
      Supplier<AgentCommand> work) {
    Future<AgentCommand> future = effects.submit(() -> observed(agent, effect, turn, work));
    try {
      return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException expired) {
      future.cancel(true);
      return EffectOutcomes.command(
          turn,
          terms.failed(new IllegalStateException("no answer within " + terms.timeout())),
          NO_ATTEMPTS);
    } catch (ExecutionException broken) {
      // A provider that throws rather than returning a Fault -- infer() hands provider.infer(...)
      // to a switch with no try around it -- surfaces here instead of escaping runTurn with the
      // agent stuck Inferring. Delivered the same way an expiry is: attempted, and nobody found out
      // how it went.
      return EffectOutcomes.command(
          turn, terms.failed(asRuntimeException(broken.getCause())), NO_ATTEMPTS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting on " + terms, interrupted);
    }
  }

  private static RuntimeException asRuntimeException(Throwable cause) {
    return cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
  }

  /**
   * What just became true, told to whoever is watching.
   *
   * <p>Narrated after the append, never before: a watcher that heard about a turn the store then
   * refused would be told something that did not happen. The deltas are the exception and arrive
   * ahead of everything, because a fragment of an answer is worth seeing before the answer exists.
   */
  private void narrate(AgentId agent, AgentEvent event) {
    // Some of these mean resolving what a reference stands for, which is real work: skipped
    // entirely when nobody is there to be told. Narrating anyway would still be correct.
    if (!narrator.listening()) {
      return;
    }
    switch (event) {
      // Not narrated. A watcher is told what is happening to a turn, and a call being tried
      // again is the engine keeping its own promise rather than anything the turn did. It is in
      // the story for whoever is counting what the turn spent.
      case AgentEvent.InferenceAttempted _ -> {}
      case AgentEvent.ActionsRequested asked ->
          tell(
              agent,
              new Narration.ActionsRequested(
                  asked.actions().stream()
                      .filter(ActionRequest.ToolCall.class::isInstance)
                      .map(ActionRequest.ToolCall.class::cast)
                      .map(ActionRequest.ToolCall::name)
                      .toList()));
      case AgentEvent.ToolApproved approved ->
          tell(agent, new Narration.CallApproved(approved.callId()));
      case AgentEvent.ToolDenied denied ->
          tell(agent, new Narration.CallDenied(denied.callId(), denied.reason()));
      case AgentEvent.ToolSucceeded done -> tell(agent, new Narration.CallFinished(done.callId()));
      case AgentEvent.ToolFailed failed ->
          tell(agent, new Narration.CallFailed(failed.callId(), failed.message()));
      case AgentEvent.InferenceAnswered answered -> {
        tell(agent, new Narration.Answered());
        tell(agent, new Narration.TurnEnded(answered.turn()));
      }
      case AgentEvent.InferenceRefused refused -> {
        tell(agent, new Narration.TurnRefused(refused.category()));
        tell(agent, new Narration.TurnEnded(refused.turn()));
      }
      case AgentEvent.InferenceFailed failed -> {
        tell(agent, new Narration.TurnFailed(failed.failure().reason()));
        tell(agent, new Narration.TurnEnded(failed.turn()));
      }
      // Heard exactly as any other failed turn is. A watcher does not care whether the model
      // could not answer or a policy decided it had answered enough; either way the turn is over
      // and the reason is the whole of what is worth saying about it.
      case AgentEvent.TurnFailed ended -> {
        tell(agent, new Narration.TurnFailed(ended.reason()));
        tell(agent, new Narration.TurnEnded(ended.turn()));
      }
      case AgentEvent.Terminated _ -> tell(agent, new Narration.Terminated());
      // Said even though the caller knows: the caller is not the only watcher. A page on the
      // narration stream while the request blocks, or a second one opened beside it, learns what
      // is happening only from here.
      case AgentEvent.TurnStarted started -> tell(agent, new Narration.TurnStarted(started.turn()));
    }
  }

  /**
   * Handed to the narrator, which is the one thing that knows who is listening.
   *
   * <p>Not a loop over listeners here. Telling them is the narrator's job -- off this thread, in
   * order, isolated from each other -- and doing it inline would put a slow listener between the
   * caller and their answer, which is exactly what this door must not do.
   */
  private void tell(AgentId agent, Narration event) {
    narrator.narrate(agentType, agent, event);
  }

  /**
   * What {@code turn} itself came to, read fresh from the whole stream rather than trusted from
   * whatever the last step in this call happened to produce.
   *
   * <p>Scoped by {@code turn} on purpose: with the whole-turn lock gone, the most recent terminal
   * event in the stream is not necessarily this caller's own -- a caller stuck behind a busy phase
   * proceeds on its own turn once recovered, and a slow-but-alive original can lose the race for
   * its own completion to that same recovery (§4d). Picking "the latest terminal event" rather than
   * "this turn's terminal event" is exactly how a caller would end up reading someone else's
   * answer, which this scoping is what rules out.
   */
  private Outcome<O> outcome(AgentId agent, TurnId turn) {
    // Read once and tallied once. The state that was accumulating this turn's counts has already
    // gone idle by now, so what the caller is told it cost is worked out from the same events the
    // fold counted -- by the same arithmetic, in TurnTally, so the two cannot disagree.
    List<AgentEvent> story = backend.events().readAll(agentType, agent);
    TurnStats stats = TurnTally.of(story, turn);
    return story.reversed().stream()
        .map(event -> asOutcome(agent, turn, event, stats))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("a turn that ended without ending"));
  }

  private Outcome<O> asOutcome(AgentId agent, TurnId turn, AgentEvent event, TurnStats stats) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered when answered.turn().equals(turn) ->
          readAnswer(agent, answered, stats);
      case AgentEvent.InferenceRefused refused when refused.turn().equals(turn) ->
          new Outcome.Refused<>(refused.category(), stats);
      case AgentEvent.InferenceFailed failed when failed.turn().equals(turn) ->
          new Outcome.Failed<>(failed.failure().reason(), stats);
      case AgentEvent.TurnFailed ended when ended.turn().equals(turn) ->
          new Outcome.Failed<>(ended.reason(), stats);
      default -> null;
    };
  }

  /**
   * The words of an answer, for the value this door hands back.
   *
   * <p>Nothing to do with narration, which no longer reads content at all: this is the answer the
   * caller asked for, and the only place the reference has to be resolved.
   */
  private static String textOf(
      Payloads payloads, AgentId agent, AgentEvent.InferenceAnswered answered) {
    return switch (payloads.forAgent(agent).get(answered.answer())) {
      case Payloads.Resolved.Found(List<Block> blocks) ->
          blocks.stream()
              .filter(Block.Text.class::isInstance)
              .map(Block.Text.class::cast)
              .map(Block.Text::text)
              .reduce("", String::concat);
      case Payloads.Resolved.Missing _ -> "";
    };
  }

  /**
   * The answer, read into the shape this harness was created for.
   *
   * <p>Fetching the text and deciding what a bad read costs belong here rather than in the {@link
   * OutputReader}: a reader is handed text and hands back an object, so the only thing it can do
   * about text that is not its shape is throw. A model that answered around its schema fails the
   * turn -- which is the whole reason a caller asked for a shape instead of prose -- and it fails
   * as an {@link Outcome.Failed} rather than an exception out of a door that promised a value.
   */
  private Outcome<O> readAnswer(
      AgentId agent, AgentEvent.InferenceAnswered answered, TurnStats stats) {
    String text = textOf(backend.payloads(), agent, answered);
    try {
      return new Outcome.Answered<>(reading.read(text), stats);
    } catch (RuntimeException notTheShape) {
      return new Outcome.Failed<>("the answer did not fit: " + notTheShape.getMessage(), stats);
    }
  }

  /**
   * One effect, inside a span of its own, on the thread that actually runs it.
   *
   * <p>Opened here rather than in {@link #perform} because that method runs on the caller's thread
   * and only submits: a span opened there would time the submission and the wait, not the work.
   * This runs on the virtual thread, so what it measures is the effect.
   *
   * <p>Named the way the queued door names the same effect -- see {@link EffectSpans} -- so that a
   * dashboard reads the same whichever door performed it. What it adds over the {@code chat} or
   * {@code execute_tool} span inside it is the part only this door knows: which effects of a turn
   * ran at once, and how long one waited before it could.
   */
  private AgentCommand observed(
      AgentId agent, AgentEffect effect, TurnId turn, Supplier<AgentCommand> work) {
    if (observations.isNoop()) {
      return work.get();
    }
    Observation observation =
        Observation.createNotStarted(EffectSpans.EFFECT, observations)
            .contextualName(EffectSpans.nameOf(effect));
    new Identity(agentType, agent).on(observation, turn);
    return observation.observe(work::get);
  }
}
