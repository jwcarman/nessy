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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
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
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.LockKind;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
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
 * A turn, run on the calling thread.
 *
 * <p>The whole harness is the loop in {@link #ask}: hand the state a command, keep the facts it
 * produced, run whatever work came back, and turn each outcome into the next command. What a queued
 * harness spreads across a transaction, an outbox and a poller happens here between two statements,
 * and {@link AgentState} cannot tell the difference.
 *
 * <p>Note what is absent: no backlog, no coalescing, no claims, no leases, no deferral. Not
 * forbidden -- nothing in this world can produce them.
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
   * What every turn and every termination of this door locks under, until the per-step relocking of
   * {@code docs/superpowers/specs/2026-09-25-locks-as-plumbing-design.md} §3 lands. Public so an
   * application supplying its own {@link Locks} -- a lease, say -- knows what kind to give a
   * time-to-live for.
   */
  public static final LockKind TURN = new LockKind("nessy.direct-harness.turn");

  private final Locks locks;
  private final AgentType agentType;
  private final AgentEvents events;
  private final Payloads payloads;
  private final InputRenderer<I> renderer;

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
      Locks locks,
      AgentType agentType,
      AgentEvents events,
      Payloads payloads,
      InputRenderer<I> renderer,
      BiFunction<AgentId, AgentEvent.InferenceAnswered, Outcome<O>> reading,
      Narrator narrator,
      EffectHandlers handlers,
      ExecutorService effects) {
    this.locks = locks;
    this.agentType = agentType;
    this.events = events;
    this.payloads = payloads;
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.reading = Objects.requireNonNull(reading, "reading must not be null");
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.handlers = Objects.requireNonNull(handlers, "handlers must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
  }

  @Override
  public Outcome<O> ask(AgentId agent, I input) {
    return under(agent, () -> runTurn(agent, input));
  }

  /** One turn at a time per agent. */
  private Outcome<O> under(AgentId agent, Supplier<Outcome<O>> turn) {
    // Two callers asking at once used to race on that agent's stream and find out at the append;
    // now the second is told the agent is busy and nothing it did has to be undone. expectedLast
    // still guards the append, because a lease can expire under a holder that is merely slow --
    // this stops two callers, that stops two writers.
    return locks.tryWithLock(TURN, agentType, agent, turn).orElse(new Outcome.Busy<>());
  }

  private Outcome<O> runTurn(AgentId agent, I input) {
    // TWO READS, and they are not the same read.
    //
    // The state is rebuilt from the watermark alone -- the latest turn, and nothing before it.
    // That is what the watermark is for: reconstitution costs one turn's events however long this
    // agent has lived, so a conversation of a thousand turns rebuilds as fast as its first.
    //
    // The transcript is a different question. What the model is shown is the conversation, which
    // is every turn before this one as well, so it takes its own read. Using the last turn for
    // both is what made a second ask forget the first.
    // Content is kept per agent, so everything this turn puts away or reads back goes through a
    // view of the store that knows whose it is.
    Payloads content = payloads.forAgent(agent);

    List<AgentEvent> lastTurn = events.sinceLastTurnStarted(agent);
    Seq from = lastTurn.isEmpty() ? Seq.NONE : previous(lastTurn.getFirst().seq());
    AgentState state = AgentState.idle(from).applyAll(lastTurn);

    // Asked of an agent that has ended. The core refuses this loudly, and rightly -- silently
    // swallowing it is what costs somebody an afternoon -- but loudly is for a programming error
    // reaching the fold, not for a caller who is owed an answer. This door always has one waiting,
    // so the refusal is the answer rather than an exception out of a request thread.
    if (state instanceof AgentState.Terminal) {
      LOG.debug("[{}] agent {} has ended; the question is refused", agentType.value(), agent);
      return new Outcome.Refused<>("terminated");
    }

    // TODO: unwindowed. ContextConfig.maxTail is the knob this should hang off; until it does, a
    // long conversation sends the model all of it.
    List<AgentEvent> history = new ArrayList<>(events.readFrom(agent, Seq.NONE));

    // Claim-checked before it reaches the core, which never sees I and never sees blocks.
    Deque<AgentCommand> pending = new ArrayDeque<>();
    pending.add(new AgentCommand.StartTurn(content.put(renderer.render(input))));

    while (!pending.isEmpty()) {
      Decision decision = state.execute(pending.poll());

      // expectedLast is vacuous here -- nothing else writes this agent -- and load-bearing for a
      // queued harness using the same seam. One signature rather than two.
      events.append(agent, decision.events(), state.seq());
      decision.events().forEach(event -> narrate(agent, event));
      history.addAll(decision.events());
      state = state.applyAll(decision.events());

      for (AgentEffect effect : decision.effects()) {
        pending.add(perform(agent, effect));
      }
    }

    return outcome(agent, history);
  }

  @Override
  public void terminate(AgentId agent) {
    // Under the same lock as a turn, because the core only takes Terminate from Idle: asking
    // while a turn is running would be declined silently, and waiting for the turn is not this
    // door's habit. A caller that was refused the lock asks again once the turn it saw has ended.
    locks.tryWithLock(
        TURN,
        agentType,
        agent,
        () -> {
          List<AgentEvent> lastTurn = events.sinceLastTurnStarted(agent);
          Seq from = lastTurn.isEmpty() ? Seq.NONE : previous(lastTurn.getFirst().seq());
          AgentState state = AgentState.idle(from).applyAll(lastTurn);
          Decision decision = state.execute(new AgentCommand.Terminate());
          events.append(agent, decision.events(), state.seq());
        });
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
    return within(
        terms,
        () ->
            switch (handlers.perform(agent, effect)) {
              case Awaited.Ready<EffectOutcome>(EffectOutcome outcome) ->
                  EffectOutcomes.command(outcome);
              case Awaited.Deferred<EffectOutcome> _ ->
                  EffectOutcomes.command(
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
  private AgentCommand within(EffectTerms terms, Supplier<AgentCommand> work) {
    Future<AgentCommand> future = effects.submit(work::get);
    try {
      return future.get(terms.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException expired) {
      future.cancel(true);
      return EffectOutcomes.command(
          terms.failed(new IllegalStateException("no answer within " + terms.timeout())));
    } catch (ExecutionException broken) {
      // A provider that throws rather than returning a Fault -- infer() hands provider.infer(...)
      // to a switch with no try around it -- surfaces here instead of escaping runTurn with the
      // agent stuck Inferring. Delivered the same way an expiry is: attempted, and nobody found out
      // how it went.
      return EffectOutcomes.command(terms.failed(asRuntimeException(broken.getCause())));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting on " + terms, interrupted);
    }
  }

  private static RuntimeException asRuntimeException(Throwable cause) {
    return cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
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

  private Outcome<O> outcome(AgentId agent, List<AgentEvent> history) {
    return history.reversed().stream()
        .map(event -> asOutcome(agent, event))
        .filter(Objects::nonNull)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("a turn that ended without ending"));
  }

  private Outcome<O> asOutcome(AgentId agent, AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered -> reading.apply(agent, answered);
      case AgentEvent.InferenceRefused refused -> new Outcome.Refused<>(refused.category());
      case AgentEvent.InferenceFailed failed -> new Outcome.Failed<>(failed.failure().reason());
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
