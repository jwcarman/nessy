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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.agent.Outstanding;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.effect.EffectHandlers;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.effect.EffectTerms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * A turn, run as a sequence of short locked steps with the slow work performed between them.
 *
 * <p><b>N short locked transactions, never one held across a turn.</b> Each step -- reconstitute
 * the agent from {@link AgentEvents#sinceLastTurnStarted}, decide, append -- happens under {@link
 * Locks#withLock}, which {@link org.jwcarman.nessy.engine.jdbc.JdbcRowLocks} turns into one short
 * database transaction. The effect a step decided on -- an inference, an approval, a tool call --
 * is then performed with the lock released and no transaction open, and its outcome becomes the
 * next step's command. No state is carried across a release: every step re-reads the agent under
 * its own lock (design record {@code 2026-09-25-locks-as-plumbing}, §3).
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
 */
public final class DefaultDirectHarness<I, O> implements DirectHarness<I, O> {

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
  private final BiFunction<AgentId, AgentEvent.InferenceAnswered, Outcome<O>> reading;

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

  public DefaultDirectHarness(
      DirectBackend backend,
      AgentType agentType,
      Clock clock,
      InputRenderer<I> renderer,
      BiFunction<AgentId, AgentEvent.InferenceAnswered, Outcome<O>> reading,
      Narrator narrator,
      EffectHandlers handlers,
      ExecutorService effects) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.agentType = agentType;
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.reading = Objects.requireNonNull(reading, "reading must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.handlers = Objects.requireNonNull(handlers, "handlers must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
  }

  @Override
  public Outcome<O> ask(AgentId agent, I input) {
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
  public void terminate(AgentId agent) {
    // The core only takes Terminate from Idle: asking while a turn is running is declined by the
    // fold itself (Decision.ignore()), and waiting for the turn is not this door's habit.
    backend
        .locks()
        .withLock(
            Locks.TURN,
            agentType,
            agent,
            () -> {
              AgentState state = reconstitute(agent);
              Decision decision = state.execute(new AgentCommand.Terminate());
              backend.events().append(agent, decision.events(), state.seq());
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
      return StepResult.declined(new Outcome.Refused<>("terminated"));
    }
    return switch (recoverToIdle(agent, state)) {
      case RecoveryOutcome.Busy() -> StepResult.declined(new Outcome.Busy<>());
      case RecoveryOutcome.Recovered(AgentState.Idle idle) -> {
        Payloads content = backend.payloads().forAgent(agent);
        Decision decision = idle.execute(new AgentCommand.StartTurn(content.put(rendered)));
        backend.events().append(agent, decision.events(), idle.seq());
        decision.events().forEach(event -> narrate(agent, event));
        TurnId turn = ((AgentEvent.TurnStarted) decision.events().getFirst()).turn();
        yield StepResult.advanced(turn, decision.effects());
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
    Decision decision = state.execute(command);
    backend.events().append(agent, decision.events(), state.seq());
    decision.events().forEach(event -> narrate(agent, event));
    return decision;
  }

  /**
   * Performs each effect the first step produced, and every effect each following step produces in
   * turn, with a fresh short locked step -- and no lock -- between one and the next.
   *
   * <p><b>The final read is by turn, not by "whatever this caller last saw".</b> {@link #outcome}
   * re-reads the whole stream and looks for {@code turn}'s own terminal event rather than trusting
   * the last {@link Decision} this loop produced: a step near the end of a turn can be genuinely
   * {@link Decision#ignore()} -- a slow-but-alive original whose own completion lost a race with a
   * recovery that already discharged the same phase (§4d) -- and that caller still owes its caller
   * an answer, which is whatever the stream says {@code turn} itself came to, not an exception.
   */
  private Outcome<O> drive(AgentId agent, TurnId turn, List<AgentEffect> firstEffects) {
    Deque<AgentEffect> pending = new ArrayDeque<>(firstEffects);
    while (!pending.isEmpty()) {
      AgentCommand command = perform(agent, pending.poll());
      Decision decision =
          backend.locks().withLock(Locks.TURN, agentType, agent, () -> executeStep(agent, command));
      pending.addAll(decision.effects());
    }
    return outcome(agent, turn);
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
      Decision decision = current.execute(discharge.get());
      backend.events().append(agent, decision.events(), current.seq());
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
    Instant started = backend.events().writtenAt(agent, inferring.seq());
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
    return EffectOutcomes.command(turn, terms.undispatchable());
  }

  /** The first outstanding call whose own deadline has passed, if there is one. */
  private Optional<AgentCommand> overdueCall(AgentId agent, AgentState.AwaitingActions awaiting) {
    for (Outstanding outstanding : awaiting.outstanding().values()) {
      AgentEffect effect = effectFor(awaiting.turn(), awaiting.requestSeq(), outstanding);
      EffectTerms terms = handlers.termsFor(effect);
      Instant started = backend.events().writtenAt(agent, outstanding.since());
      if (isOverdue(started, terms)) {
        return Optional.of(undispatchable(awaiting.turn(), terms));
      }
    }
    return Optional.empty();
  }

  /**
   * The effect one outstanding call implies, so its terms can be looked up the way §4c does.
   *
   * <p>A pattern switch rather than a cast, because {@link ActionRequest} is deliberately a grammar
   * with room for an arm beside {@code ToolCall}: on the day one arrives, this has to stop
   * compiling. A cast would instead have kept compiling and thrown here at recovery time, which is
   * the least observable moment in this class to learn about it.
   */
  private static AgentEffect effectFor(TurnId turn, Seq requestSeq, Outstanding outstanding) {
    return switch (outstanding.action()) {
      case ActionRequest.ToolCall(var id, var name) ->
          switch (outstanding.phase()) {
            case AWAITING_APPROVAL -> new AgentEffect.Approve(turn, requestSeq, id, name);
            case RUNNING -> new AgentEffect.CallTool(turn, requestSeq, id, name);
          };
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
    List<AgentEvent> lastTurn = backend.events().sinceLastTurnStarted(agent);
    Seq from = lastTurn.isEmpty() ? Seq.NONE : previous(lastTurn.getFirst().seq());
    return AgentState.idle(from).applyAll(lastTurn);
  }

  /**
   * What one locked step of a turn's opening command came to: either a caller who was told no --
   * {@link Outcome.Refused} or {@link Outcome.Busy} -- or a turn genuinely under way.
   */
  private sealed interface StepResult<O> {

    record Declined<O>(Outcome<O> outcome) implements StepResult<O> {}

    record Advanced<O>(TurnId turn, List<AgentEffect> effects) implements StepResult<O> {}

    static <O> StepResult<O> declined(Outcome<O> outcome) {
      return new Declined<>(outcome);
    }

    static <O> StepResult<O> advanced(TurnId turn, List<AgentEffect> effects) {
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
   * everything bounded by the deadline the effect's own terms name.
   *
   * <p>What performs the effect is {@link EffectHandlers#perform}, the same call the queued door's
   * dispatcher makes. The only thing this door adds is {@link #within}'s deadline and the one arm
   * {@link EffectHandlers#perform} can return that a queued row can park and this door cannot:
   * {@link Awaited.Deferred}. Nothing here is coming back for that answer, so it is a failure by
   * the effect's own terms rather than a denial this door has no standing to hand out.
   */
  private AgentCommand perform(AgentId agent, AgentEffect effect) {
    EffectTerms terms = handlers.termsFor(effect);
    // The turn comes off the effect, which is the only thing that knows it: this loop's own turn
    // is the same one, but an outcome has to name the turn that ASKED for the work rather than
    // whichever turn the agent happens to be in by the time the answer lands.
    TurnId turn = effect.turn();
    return within(
        turn,
        terms,
        () ->
            switch (handlers.perform(agent, effect)) {
              case Awaited.Ready<EffectOutcome>(EffectOutcome outcome) ->
                  EffectOutcomes.command(turn, outcome);
              case Awaited.Deferred<EffectOutcome> _ ->
                  EffectOutcomes.command(
                      turn,
                      terms.failed(
                          new IllegalStateException(
                              "the effect was deferred, and nothing here can wait for it")));
            });
  }

  /**
   * Performs {@code work} on its own virtual thread and waits for it no longer than {@code terms}
   * allows.
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
   * undispatchable()} is for work nobody performed at all, which belongs to recovery, not to this
   * door.
   */
  private AgentCommand within(TurnId turn, EffectTerms terms, Supplier<AgentCommand> work) {
    Future<AgentCommand> future = effects.submit(work::get);
    try {
      return future.get(terms.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException expired) {
      future.cancel(true);
      return EffectOutcomes.command(
          turn, terms.failed(new IllegalStateException("no answer within " + terms.timeout())));
    } catch (ExecutionException broken) {
      // A provider that throws rather than returning a Fault -- infer() hands provider.infer(...)
      // to a switch with no try around it -- surfaces here instead of escaping runTurn with the
      // agent stuck Inferring. Delivered the same way an expiry is: attempted, and nobody found out
      // how it went.
      return EffectOutcomes.command(turn, terms.failed(asRuntimeException(broken.getCause())));
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
        tell(agent, new Narration.TurnRefused());
        tell(agent, new Narration.TurnEnded(refused.turn()));
      }
      case AgentEvent.InferenceFailed failed -> {
        tell(agent, new Narration.TurnFailed());
        tell(agent, new Narration.TurnEnded(failed.turn()));
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
    return backend.events().readFrom(agent, Seq.NONE).reversed().stream()
        .map(event -> asOutcome(agent, turn, event))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("a turn that ended without ending"));
  }

  private Outcome<O> asOutcome(AgentId agent, TurnId turn, AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered when answered.turn().equals(turn) ->
          reading.apply(agent, answered);
      case AgentEvent.InferenceRefused refused when refused.turn().equals(turn) ->
          new Outcome.Refused<>(refused.category());
      case AgentEvent.InferenceFailed failed when failed.turn().equals(turn) ->
          new Outcome.Failed<>(failed.failure().reason());
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
   * What a caller who asked for no particular shape gets: whatever the model said.
   *
   * <p>Package-private rather than {@code private}: called through a method reference the factory
   * builds for a harness whose {@code O} is {@link String} -- the one fact only the factory's
   * unstructured {@code create} method can state, since that method is where {@code O} is String
   * rather than merely this class's abstract type variable.
   */
  static Outcome<String> saidText(
      Payloads payloads, AgentId agent, AgentEvent.InferenceAnswered answered) {
    return new Outcome.Answered<>(textOf(payloads, agent, answered));
  }

  /**
   * The answer, parsed into the shape it was asked for.
   *
   * <p>A model that answered around the schema fails the turn rather than handing back something
   * that does not fit -- which is the whole reason a caller asked for a shape instead of prose.
   *
   * <p>Package-private for the same reason as {@link #saidText}: the factory's bound {@code create}
   * method calls this on the harness it just built, where {@code type} and this instance's {@code
   * O} are the same type parameter.
   */
  static <T> Outcome<T> read(
      Payloads payloads,
      ObjectMapper mapper,
      AgentId agent,
      AgentEvent.InferenceAnswered answered,
      TypeRef<T> type) {
    String json = textOf(payloads, agent, answered);
    try {
      return new Outcome.Answered<>(mapper.readValue(json, mapper.constructType(type.getType())));
    } catch (RuntimeException notTheShape) {
      return new Outcome.Failed<>(
          "the answer did not fit " + type + ": " + notTheShape.getMessage());
    }
  }
}
