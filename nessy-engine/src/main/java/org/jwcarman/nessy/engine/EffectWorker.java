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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.ExchangeContentBlock;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.ExchangeMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Effect;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelReplies;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * Everything an agent's decisions actually DO, and none of the deciding.
 *
 * <p><b>Answers go out through {@link Dispatcher}, never through a reference.</b> Work handed to
 * the blocking executor reports back by calling {@code dispatcher.dispatch(agentId, input,
 * effectId, observability)} -- an agent id, not a handle to anything. Nothing here holds an actor
 * reference or a cluster address, which is what makes it irrelevant whether the thing that answers
 * is still the same process that started the work.
 *
 * <p><b>Content is claimed BEFORE the agent is told.</b> Every {@link Input} this produces carries
 * ids and small statuses; a tool's result and the model's asking message go into claims first. That
 * is what makes a persisted state safe to reference them: a state saying a call completed cannot
 * point at a result that is not there.
 */
final class EffectWorker {

  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(EffectWorker.class);

  private static final String ASKED_KEY = "asked";
  private static final String ANSWER_KEY = "answer";

  /**
   * What performing an effect needs.
   *
   * <p>Flat rather than layered, because there is no longer a hierarchy to hand things down: one
   * actor does the whole turn, so one record holds what the whole turn needs.
   */
  record Dependencies(
      AgentType agentType,
      Memory memory,
      Model model,
      String systemPrompt,
      int maxTokens,
      ToolBindings bindings,
      Set<Capability> capabilities,
      Function<AgentId, Narrator> narrators,
      Claims claims,
      ReplyTokens tokens,
      Executor blocking,
      Traces traces,
      BacklogStore<?> backlog,
      EffectStore effects,
      Dispatcher dispatcher,
      AgentStore store,
      RetryPolicy retryPolicy,
      RandomGenerator random,
      Duration maxDeferral) {}

  private final Dependencies deps;
  private final Codec<List<ExchangeContentBlock>> askedCodec;
  private final Codec<ToolResult> resultCodec;
  private final Codec<AnswerMessage> answerCodec;
  private final Codec<UserMessage> inputCodec;

  EffectWorker(Dependencies deps) {
    this.deps = Objects.requireNonNull(deps, "deps must not be null");
    ObjectMapper mapper = EngineMapper.INSTANCE;
    this.askedCodec = JsonCodec.ofList(mapper, ExchangeContentBlock.class);
    this.resultCodec = JsonCodec.of(mapper, ToolResult.class);
    this.answerCodec = JsonCodec.of(mapper, AnswerMessage.class);
    this.inputCodec = JsonCodec.of(mapper, UserMessage.class);
  }

  /**
   * Does one obligation, against the TURN it was decided in.
   *
   * <p>{@code effectId} travels with the work so that whoever answers can discharge it. Effects
   * that answer immediately discharge it themselves; ones that start external work -- a model call,
   * a tool -- hand it to the thread that will report back, and the effect stays outstanding with a
   * watchdog until that happens.
   *
   * <p><b>{@code turnId}, not {@code state.turnId()}, is what every turn-scoped operation below
   * uses -- claim keys, reply-token minting, {@code ToolCallRequest} coordinates.</b> An effect
   * decided in turn T must be performed against turn T even when this agent has since moved on to
   * T+1, which is exactly what happens on the recovery path: a stale {@code Release} using the
   * WRONG turn would run {@code claims().deleteTurn(agentId, T+1)} and wipe a live turn's rendered
   * observation, asking message and every tool result out from under it. The address a reply token
   * names is {@code (agentType, agentId, turnId, callId)} -- see {@code ReplyTokens.Coordinates} --
   * never "whatever turn happens to be current when the answer arrives", and this is that same
   * address applied here.
   *
   * <p><b>{@link Effect.TakeWork} is the one exception</b>, and deliberately does not take {@code
   * turnId}: which backlog row to sweep is a question about NOW -- {@code state.busy()} and {@code
   * state.observation()} -- never about the turn that happened to decide to ask. See {@link
   * #takeWork}.
   *
   * <p><b>{@code attempts}</b> is how many times this obligation has ALREADY failed -- {@code
   * EffectStore.Attempted#attempts()}, verbatim, no adjustment -- or {@code -1} for a caller with
   * no real row to count against (a test driving one effect directly, or the legacy Pekko actor's
   * own turn, which uses a freshly-minted {@link EffectId} naming no row at all). {@code -1} is the
   * sentinel that turns the {@link RetryPolicy} consultation below OFF entirely and restores the
   * pre-Task-7 behavior of folding any failure into the agent immediately: a policy consulted
   * against a count that means nothing would be worse than not consulting one.
   *
   * <p>For a real count, the policy is asked BEFORE any work runs: {@link RetryPolicy#decide}
   * either says {@code GiveUp} -- this obligation closes right here, via {@link #giveUp}, with no
   * external call made -- or hands back a {@code RetryAfter} delay that is threaded down into
   * {@link #run} / {@link #settle} and applied ONLY if the attempt about to be made also fails,
   * exactly the way a genuine timeout is discovered: not by this method, but by a LATER attempt
   * finding the row still there.
   */
  void perform(
      AgentId agentId,
      AgentState state,
      TurnId turnId,
      Effect effect,
      EffectId effectId,
      Map<String, String> carried,
      int attempts) {
    Duration retryDelay = null;
    if (attempts >= 0) {
      RetryPolicy.RetryDecision decision = deps.retryPolicy().decide(attempts, deps.random());
      if (decision instanceof RetryPolicy.RetryDecision.GiveUp) {
        giveUp(agentId, turnId, effect, effectId, "gave up after " + attempts + " failures");
        return;
      }
      retryDelay = ((RetryPolicy.RetryDecision.RetryAfter) decision).delay();
    }
    switch (effect) {
      case Effect.TakeWork() -> takeWork(agentId, state, effectId, carried, retryDelay);
      case Effect.CallModel() -> callModel(agentId, turnId, effectId, carried, retryDelay);
      // R-AB: AskApprover and RunTool take no retryDelay -- a thrown tool or approver has RUN;
      // its outcome is a failure result the model sees on the spot, never a retried obligation.
      case Effect.AskApprover ask -> askApprover(agentId, turnId, ask, effectId, carried);
      case Effect.RunTool run -> runTool(agentId, turnId, run, effectId, carried);
      case Effect.Remember.Input() ->
          settle(effectId, () -> rememberInput(agentId, state, turnId), retryDelay);
      case Effect.Remember.Answer() ->
          settle(effectId, () -> rememberAnswer(agentId, turnId), retryDelay);
      case Effect.Remember.Exchange() ->
          settle(effectId, () -> rememberExchange(agentId, turnId), retryDelay);
      case Effect.Release() ->
          settle(effectId, () -> deps.claims().deleteTurn(agentId, turnId), retryDelay);
      // NOT settle(): forget()'s own effects().deleteAgent() sweeps every row this agent owes,
      // including the very row effectId names -- discharging it is a side effect of erasing the
      // agent, not a separate step. Calling complete(effectId) afterward would try to discharge a
      // row forget() had already deleted, which is indistinguishable from a double-completion and
      // (correctly) raises. A failure inside forget() before it reaches deleteAgent() leaves this
      // row RUNNING with its watchdog armed, same as any other obligation that threw.
      case Effect.Forget() -> forget(agentId);
      case Effect.Narrate narrate -> narrate(agentId, turnId, narrate);
    }
  }

  /**
   * The shape {@link AgentRuntime.Performer} calls through: no trace headers of its own to carry (a
   * re-attempted effect does not reconstruct the trace it was decided under -- see {@code
   * AgentRuntime#perform}), and {@code attempts} passed straight through from whichever caller
   * knows it -- {@link AgentRuntime#perform} for a real row, {@code -1} from {@link
   * AgentRuntime#narrate}, which is never a row.
   */
  void perform(
      AgentId agentId,
      AgentState state,
      TurnId turnId,
      Effect effect,
      EffectId effectId,
      int attempts) {
    perform(agentId, state, turnId, effect, effectId, Map.of(), attempts);
  }

  /**
   * The {@link RetryPolicy} said stop: retires the obligation, logs it loudly (I4, Task 7 fix
   * round), and folds an outcome into the agent ONLY for the two call-shaped effects, {@code
   * AskApprover} and {@code RunTool} -- reusing the very {@link Input} each already answers with
   * when its external call fails, so exhaustion reads to {@code AgentLogic} like any other failed
   * call rather than a new case it has to learn. Every other kind folds NOTHING: {@code TakeWork}
   * and {@code CallModel} used to dispatch {@code Input.ModelFailed} here, which made {@code
   * endTurn} emit a fresh {@code TakeWork} with {@code attempts} reset to zero -- an unreadable
   * backlog row or a chronically broken model call then looped forever, narrating turns that never
   * started. {@code Remember.*}, {@code Release} and {@code Forget} produce no {@link Input} on
   * success either -- they {@link #settle} rather than {@link #tell} -- so there was never anything
   * to fold for them. For all of these, the abandoned row and its logged reason ARE the whole
   * story: an agent whose backlog or model call is chronically broken stalls visibly rather than
   * spinning invisibly.
   *
   * <p>Called BEFORE any external work is attempted, from the top of {@link #perform} -- so a spent
   * effect closes without making the call it would otherwise have retried one time too many.
   */
  private void giveUp(
      AgentId agentId, TurnId turnId, Effect effect, EffectId effectId, String reason) {
    deps.effects().abandon(effectId, reason);
    // I4 (Task 7 fix round), applied uniformly: exhaustion of ANY effect is loud on its own,
    // whether or not the switch below also folds something into the agent -- an operator reading
    // logs should never have to infer abandonment from its absence.
    LOG.error(
        "[{}] obligation {} ({}) exhausted its retry budget and was abandoned: {}",
        agentId.value(),
        effectId,
        effect,
        reason);
    switch (effect) {
      // I4's ruling: exhaustion of an engine-owned effect ABANDONS the row and folds NOTHING --
      // it does not dispatch ModelFailed (which used to make endTurn emit a FRESH TakeWork with
      // attempts=0, so an unreadable backlog row looped forever narrating turns that never
      // started) and does not invent an Input arm to say so (a new arm is a public-API concept
      // needing sign-off this round does not have). The abandoned row with its reason IS the
      // operator's signal; an agent whose backlog or model call is chronically broken stalls
      // visibly rather than spinning invisibly.
      case Effect.TakeWork _, Effect.CallModel _ -> {
        // Folds nothing -- see above.
      }
      case Effect.AskApprover ask ->
          giveUpCall(agentId, turnId, ask.callId(), reason + "; the call was not made");
      case Effect.RunTool run ->
          giveUpCall(agentId, turnId, run.callId(), reason + "; it may have partially completed");
      case Effect.Remember.Input _,
          Effect.Remember.Answer _,
          Effect.Remember.Exchange _,
          Effect.Release _,
          Effect.Forget _ -> {
        // No Input exists for these on success either -- see the method javadoc. Abandoning the
        // row above is the whole story.
      }
      case Effect.Narrate _ ->
          throw new IllegalStateException("narrations are never durable rows: " + effect);
    }
  }

  private void giveUpCall(AgentId agentId, TurnId turnId, CallId callId, String message) {
    hold(agentId, turnId, callId, ToolResult.error(message));
    deps.dispatcher().dispatch(agentId, new Input.ToolCompleted(callId), null, null);
  }

  /**
   * Convenience for a caller with no claimed effect of its own to address against: the legacy Pekko
   * actor's own turn, and any test that builds a {@code state} already at the turn it means.
   * Derives the turn from {@code state.turnId()}, which is exactly right when {@code state} IS the
   * turn in question -- and exactly the bug the {@code turnId}-carrying overload above exists to
   * fix when it is not.
   */
  void perform(
      AgentId agentId,
      AgentState state,
      Effect effect,
      EffectId effectId,
      Map<String, String> carried) {
    perform(agentId, state, state.turnId(), effect, effectId, carried, -1);
  }

  /** As above, with no trace headers carried. */
  void perform(AgentId agentId, AgentState state, Effect effect, EffectId effectId) {
    perform(agentId, state, effect, effectId, Map.of());
  }

  /**
   * Runs work that finishes here, and discharges its obligation.
   *
   * <p>These produce no Input -- nothing is waiting to hear that a claim was deleted -- so the
   * effect is retired directly rather than by a transition. A failure leaves the row outstanding
   * and its watchdog armed, which is exactly right: someone should try again.
   *
   * <p><b>Not one transaction with {@code work}, and that is a considered gap, not an
   * oversight.</b> {@code work} for {@code Remember.*} and {@code Forget} runs through {@link
   * org.jwcarman.nessy.api.memory.Memory#remember} / {@code #forget} -- an application-supplied SPI
   * this engine does not own and must not assume is backed by the same database {@code
   * EffectStore#complete} writes to, or backed by a database at all. Enclosing {@link
   * EffectStore#complete} in the same transaction as an opaque collaborator's own writes would mean
   * either inventing a transactional contract for {@code Memory} (a new SPI concept, not this
   * correction's to make) or silently assuming every {@code Memory} shares the engine's {@code
   * DataSource}, which is false in general and unenforceable. {@code Release}'s own work ({@code
   * claims().deleteTurn}) is the one case that IS purely engine-owned SQL and could in principle
   * share a transaction with {@code complete} -- but singling it out while leaving the other four
   * call sites non-atomic would trade one small, well-understood gap for an inconsistent one.
   * Today's failure direction stays the safe one this class was built around: if {@code complete}
   * raises after {@code work} already committed, the work is done and stays done, and the effect
   * row -- for a genuinely claimed effect -- survives to be retried by the watchdog rather than
   * being marked done for work that never happened.
   */
  private void settle(EffectId effectId, Runnable work, Duration retryDelay) {
    try {
      work.run();
    } catch (RuntimeException failure) {
      if (retryDelay == null) {
        // No real RetryPolicy count to have consulted (attempts was the -1 sentinel): unchanged
        // pre-Task-7 behavior -- propagate, and leave the row outstanding for AgentRuntime#perform
        // to log and abandon-in-place.
        throw failure;
      }
      LOG.warn("[{}] settle failed, retrying in {}", effectId, retryDelay, failure);
      deps.effects().retry(effectId, Instant.now().plus(retryDelay));
      return;
    }
    deps.effects().complete(effectId);
  }

  /** Narration is per agent, so it is resolved per call: an entity ref is a routing decision. */
  private Narrator narrator(AgentId agentId) {
    return deps.narrators().apply(agentId);
  }

  /**
   * The one executor the engine does agent work on. {@link AgentActor} -- provisional, and gone in
   * Task 11 -- borrows it to run a decision's effects off its own thread, exactly as {@code
   * performAll} used to.
   */
  Executor blocking() {
    return deps.blocking();
  }

  /**
   * Feeds the agent an outcome, and discharges the effect that produced it -- except for a parked
   * tool, which has NOT finished: a person or a deadline discharges that one, so passing the effect
   * id here would retire an obligation nobody has met.
   */
  private void tell(
      AgentId agentId, Input input, EffectId completing, Map<String, String> carried) {
    EffectId discharges = input instanceof Input.ToolParked ? null : completing;
    deps.dispatcher().dispatch(agentId, input, discharges, observabilityOf(carried));
  }

  /**
   * Asks the store for the next row, naming the turn this agent has finished so its row is swept.
   *
   * <p>{@code state.observation()} survives a finished turn precisely for this: it is the id the
   * sweep has to name, and naming it is what distinguishes a turn that ended from a take the agent
   * never recorded.
   *
   * <p><b>Deliberately keyed off {@code state}, not off a claimed effect's {@code turnId}.</b>
   * Every other effect in {@link #perform} is performed against the TURN that decided it; this one
   * is the documented exception, because which backlog row to sweep is a question about NOW --
   * {@code state.busy()} and {@code state.observation()} -- and never about which turn happened to
   * decide to ask for work. A {@code TakeWork} re-driven after this agent has moved several turns
   * past the one that emitted it should still sweep whatever it most recently finished, not the
   * turn it was born in.
   */
  private void takeWork(
      AgentId agentId,
      AgentState state,
      EffectId effectId,
      Map<String, String> carried,
      Duration retryDelay) {
    // The TURN id, which is the backlog row's id — not the claim key. Null until this agent has
    // finished one, and null while it is busy, because a turn in flight is nobody's to sweep.
    TurnId finished = state.busy() ? null : state.turnId();
    run(
        () -> deps.backlog().take(agentId, finished),
        taken ->
            switch (taken) {
              case BacklogStore.TakeResult.Work(TurnId turnId, String claim) ->
                  new Input.WorkTaken(turnId, claim);
              case BacklogStore.TakeResult.Empty() -> new Input.NoWork();
              case BacklogStore.TakeResult.Poisoned() -> new Input.Poisoned();
            },
        failure -> new Input.ModelFailed("the backlog could not be read: " + failure),
        agentId,
        effectId,
        carried,
        retryDelay);
  }

  /**
   * Asks the model, on the blocking executor, with the transcript read INSIDE the hop.
   *
   * <p>{@code recall()} is an application's own implementation and may do arbitrary IO, so building
   * the request out here would put it on the actor's thread — three lines above the hop that exists
   * for exactly that reason.
   */
  private void callModel(
      AgentId agentId,
      TurnId turnId,
      EffectId effectId,
      Map<String, String> carried,
      Duration retryDelay) {
    run(
        () ->
            deps.traces()
                .inSpan(
                    "agent call model",
                    carried,
                    () ->
                        ModelReplies.drain(
                            deps.model().stream(
                                new ModelRequest(
                                    deps.memory().recall(agentId),
                                    deps.systemPrompt(),
                                    deps.maxTokens(),
                                    deps.bindings().tools(),
                                    deps.capabilities())),
                            event -> narrateChunk(agentId, event))),
        result -> answerOf(agentId, turnId, result),
        failure -> new Input.ModelFailed(failure),
        agentId,
        effectId,
        carried,
        retryDelay);
  }

  /**
   * Turns what the model said into an input carrying no content.
   *
   * <p>The asking message is claimed before the agent hears about it, and it is what pins the CALL
   * IDS: without it a recovered turn would have to ask the model again, get fresh ids, and re-run
   * tools whose answers it already had.
   */
  private Input answerOf(AgentId agentId, TurnId turnId, ModelResult result) {
    return switch (result) {
      case ModelResult.Refused(var category, var explanation, var usage) ->
          new Input.ModelAnswered.Refused(category, explanation, usage);
      case ModelResult.Answered(var message, var stopReason, var usage) -> {
        deps.claims().put(agentId, turnId, ANSWER_KEY, answerCodec.encode(message));
        yield new Input.ModelAnswered.Answered(stopReason, usage);
      }
      case ModelResult.Asked(var content, var usage) -> {
        deps.claims().put(agentId, turnId, ASKED_KEY, askedCodec.encode(content));
        yield new Input.ModelAnswered.Asked(
            callsIn(content).stream()
                .map(call -> new Input.CallSummary(call.id(), call.name()))
                .toList(),
            usage);
      }
    };
  }

  private void askApprover(
      AgentId agentId,
      TurnId turnId,
      Effect.AskApprover ask,
      EffectId effectId,
      Map<String, String> carried) {
    ToolCall call = callOf(agentId, turnId, ask.callId());
    if (call == null) {
      completed(
          agentId,
          turnId,
          ask.callId(),
          ToolResult.error("the asking message is gone; the call was not made"),
          effectId,
          carried);
      return;
    }
    deps.bindings()
        .binding(call.name())
        .ifPresentOrElse(
            binding -> {
              // Rendered ONCE, here, and used for both the narration and the question. It used to
              // be rendered again from a Narrate effect, which meant reading the asking claim
              // back to find the call first.
              String action = deps.bindings().actionOf(binding, call.arguments());
              narrator(agentId)
                  .narrate(
                      new AgentEvent.ToolCallRequested(
                          Identifiers.next(), call.id(), call.name(), action));
              ApprovalRequest request =
                  new ApprovalRequest(
                      deps.agentType(),
                      agentId,
                      turnId,
                      call.id(),
                      call.name(),
                      call.arguments(),
                      action,
                      Instant.now(),
                      // Not minted unless somebody asks. An approver that answers on the spot —
                      // and most do — hands the address to nobody.
                      () -> deps.tokens().mint(deps.agentType(), agentId, turnId, call.id()));
              run(
                  () ->
                      deps.traces()
                          .inSpan(
                              "approval " + call.name(),
                              carried,
                              () -> deps.bindings().approve(binding, request)),
                  answer ->
                      switch (answer) {
                        case Awaited.Ready<ApprovalResult>(var result) -> {
                          // A DENIAL is a result, so it is claimed here like any other — the model
                          // is told it was refused and gets to decide what to do about that. The
                          // logic marks the call completed and cannot write anything itself, so
                          // without this the exchange reaches the transcript saying "no result was
                          // recorded", which reads to the model as a broken tool rather than a
                          // person saying no. Measured in the browser.
                          denialResult(result)
                              .ifPresent(denied -> hold(agentId, turnId, call.id(), denied));
                          yield new Input.ApprovalGiven(call.id(), call.name(), result);
                        }
                        case Awaited.Deferred<ApprovalResult>(var expiresAt) -> {
                          // Clamped BEFORE it is narrated or folded, so the desk, the narration
                          // and the alarm this eventually arms all agree on the SAME deadline --
                          // never the approver's raw, possibly-unbounded request.
                          Instant clamped = clampDeferral(agentId, call.id(), expiresAt);
                          // Narrated HERE and nowhere else: an ungated tool answers on the spot,
                          // and only a deferral means a person is actually being asked. The desk
                          // needs the deadline, which is knowable at exactly this moment.
                          narrator(agentId)
                              .narrate(
                                  new AgentEvent.ApprovalRequested(
                                      Identifiers.next(), call.id(), call.name(), action, clamped));
                          yield new Input.ToolParked(call.id(), clamped);
                        }
                      },
                  failure -> {
                    // R-AB: a thrown approver is NOT a denial -- do not synthesize one. The call
                    // simply failed, exactly like a thrown tool below, and the model is told so as
                    // a ToolResult.Failure, not handed an ApprovalResult nobody actually decided.
                    hold(
                        agentId,
                        turnId,
                        call.id(),
                        ToolResult.error("the approver failed: " + failure));
                    return new Input.ToolCompleted(call.id());
                  },
                  agentId,
                  effectId,
                  carried,
                  // R-AB: never retried. An approver has RUN the moment it throws -- exactly one
                  // attempt, always, whatever attempts/retryDelay the top of #perform computed for
                  // a stalled-worker pickup (see EffectStore#take / C1). Null here means a throw
                  // folds immediately through #run's own broke-path above, same as runTool below.
                  null);
            },
            () ->
                completed(
                    agentId,
                    turnId,
                    call.id(),
                    ToolResult.error("no such tool: " + call.name() + "; the call was not made"),
                    effectId,
                    carried));
  }

  private void runTool(
      AgentId agentId,
      TurnId turnId,
      Effect.RunTool run,
      EffectId effectId,
      Map<String, String> carried) {
    ToolCall call = callOf(agentId, turnId, run.callId());
    if (call == null) {
      completed(
          agentId,
          turnId,
          run.callId(),
          ToolResult.error("the asking message is gone; it was not run"),
          effectId,
          carried);
      return;
    }
    deps.bindings()
        .binding(call.name())
        .ifPresent(
            binding ->
                run(
                    () ->
                        deps.traces()
                            .inSpan(
                                "tool " + call.name(),
                                carried,
                                () ->
                                    deps.bindings()
                                        .run(binding, requestFor(agentId, turnId, call))),
                    answer ->
                        switch (answer) {
                          case Awaited.Ready<ToolResult>(var result) -> {
                            hold(agentId, turnId, call.id(), result);
                            yield new Input.ToolCompleted(call.id());
                          }
                          case Awaited.Deferred<ToolResult>(var expiresAt) ->
                              new Input.ToolParked(
                                  call.id(), clampDeferral(agentId, call.id(), expiresAt));
                        },
                    failure -> {
                      hold(
                          agentId,
                          turnId,
                          call.id(),
                          ToolResult.error(failure + "; it may have partially completed"));
                      return new Input.ToolCompleted(call.id());
                    },
                    agentId,
                    effectId,
                    carried,
                    // R-AB: never retried. A tool has RUN the moment it throws -- its outcome is a
                    // failure RESULT the model is entitled to see and reason about, not an
                    // unfinished obligation, and tools are not idempotent in general: re-invoking
                    // one that may already have had a side effect is worse than not retrying at
                    // all. Exactly one attempt, always -- null forces #run's broke-path above
                    // regardless of what attempts/retryDelay the top of #perform computed for a
                    // stalled-worker pickup (see EffectStore#take / C1).
                    null));
  }

  /**
   * The one place both kinds of deferral -- a parked approval and a parked tool alike -- are
   * clamped, because {@code actionable_at} for a parked effect IS its deadline (see the class
   * javadoc on {@code EngineConfig#maxDeferral}): an unbounded deferral is now an unbounded row
   * nothing will ever revisit, not merely an inert far-future timer the way it used to be. Warns
   * only when the clamp actually fires -- a tool that stayed inside its own bound never causes a
   * log line, because the clamp firing means a tool ignored a bound, and that is the case worth
   * being loud about.
   */
  private Instant clampDeferral(AgentId agentId, CallId callId, Instant requested) {
    Instant max = Instant.now().plus(deps.maxDeferral());
    if (requested.isAfter(max)) {
      LOG.warn(
          "[{}] deferral for {} clamped: asked for {}, granted {}",
          agentId.value(),
          callId.value(),
          requested,
          max);
      return max;
    }
    return requested;
  }

  /** What a denied call answers with, or empty when it was approved and will answer for itself. */
  static Optional<ToolResult> denialResult(ApprovalResult result) {
    if (result instanceof ApprovalResult.Denied(var reason)) {
      return Optional.of(ToolResult.error("denied: " + reason + "; the call was not made"));
    }
    return Optional.empty();
  }

  /** Writes a result and tells the agent — in that order, always. */
  private void completed(
      AgentId agentId,
      TurnId turnId,
      CallId callId,
      ToolResult result,
      EffectId effectId,
      Map<String, String> carried) {
    hold(agentId, turnId, callId, result);
    tell(agentId, new Input.ToolCompleted(callId), effectId, carried);
  }

  private void hold(AgentId agentId, TurnId turnId, CallId callId, ToolResult result) {
    deps.claims().put(agentId, turnId, resultKey(callId), resultCodec.encode(result));
  }

  /**
   * {@code state.observation()} is the CLAIM KEY the observation was rendered under, not
   * turn-scoped data itself — {@code BacklogStore} always writes it under the same constant key,
   * whichever turn is asking — so reading it off current state is safe even when {@code turnId}
   * names an earlier turn than the one {@code state} is currently in. The claims LOOKUP that key
   * addresses is what has to be turn-scoped, and that is what {@code turnId} fixes here.
   */
  private void rememberInput(AgentId agentId, AgentState state, TurnId turnId) {
    redeem(agentId, turnId, state.observation(), inputCodec)
        .ifPresent(input -> deps.memory().remember(agentId, input));
  }

  private void rememberAnswer(AgentId agentId, TurnId turnId) {
    redeem(agentId, turnId, ANSWER_KEY, answerCodec)
        .ifPresent(
            answer -> {
              narrator(agentId).narrate(new AgentEvent.Answered(Identifiers.next(), answer));
              deps.memory().remember(agentId, answer);
            });
  }

  /**
   * The asking message and every result, in ONE write.
   *
   * <p>An assistant turn naming unanswered calls is not a valid transcript, so it is held until
   * every call has settled and then written together with the message answering it. That is what
   * keeps a transcript from ever holding half an exchange, and therefore what makes re-driving
   * always safe.
   */
  private void rememberExchange(AgentId agentId, TurnId turnId) {
    redeem(agentId, turnId, ASKED_KEY, askedCodec)
        .ifPresent(
            asked -> {
              List<ToolResultBlock> answers = new ArrayList<>();
              for (ToolCall call : callsIn(asked)) {
                ToolResult result =
                    redeem(agentId, turnId, resultKey(call.id()), resultCodec)
                        .orElseGet(() -> ToolResult.error("no result was recorded"));
                answers.add(ToolResultBlock.of(call.id(), result));
              }
              deps.memory().remember(agentId, new ExchangeMessage(asked, answers));
            });
  }

  /**
   * Erases an agent: everything it remembered, everything waiting for it, everything it held, its
   * pending obligations, and finally the record that it existed.
   *
   * <p><b>Only ever issued when idle.</b> {@code AgentLogic} holds a busy agent's request until the
   * turn ends, so nothing here races work in flight.
   *
   * <p><b>Effects are deleted BEFORE the state row, and that order is deliberate.</b> A crash
   * between the two leaves a state row with no pending effects — which recovers cleanly, an agent
   * simply idle again — rather than effect rows pointing at a state row that is gone, which a
   * future claim would drain forever with nothing to fold their outcomes against.
   *
   * <p><b>{@link #deps}'s {@code store} is engine-owned SQL only.</b> {@code AgentActor}'s OWN
   * Pekko-persisted document — a separate store entirely, written through {@code
   * DurableStateBehavior} — is untouched here, exactly as before: that reference left this class
   * along with the {@code ActorSystem} it used to hold, and the row it recovers from survives until
   * Task 11 deletes the journal machinery that wrote it. An agent forgotten here and reached again
   * through {@code AgentActor} in the meantime still comes back holding its PRE-FORGET {@code
   * AgentState} set against memory, claims and backlog rows now gone underneath it — acceptable
   * only because that whole actor is deleted in Task 11 along with the store it recovers from.
   * {@code AgentRuntime}'s own read of "does this agent exist" (see {@code AgentStore#peek}) is
   * answered by exactly the row this method now deletes.
   */
  private void forget(AgentId agentId) {
    deps.memory().forget(agentId);
    deps.backlog().deleteAgent(agentId);
    deps.claims().deleteAgent(agentId);
    deps.effects().deleteAgent(deps.agentType(), agentId);
    deps.store().delete(deps.agentType(), agentId);
    // LAST, and that ordering is the whole recovery story: everything above is idempotent, so a
    // crash before this leaves the pill, the next incarnation takes it, and the same work runs
    // again to the same end. Swallowing it first would lose a half-finished forget in silence.
    deps.backlog().swallow(agentId);
    LOG.info("[{}] forgotten", agentId.value());
  }

  private void narrate(AgentId agentId, TurnId turnId, Effect.Narrate narrate) {
    switch (narrate) {
      case Effect.Narrate.TurnStarted(_) ->
          narrator(agentId).narrate(new AgentEvent.TurnStarted(Identifiers.next()));
      case Effect.Narrate.TurnEnded(var result, var usage) ->
          narrator(agentId).narrate(new AgentEvent.TurnEnded(Identifiers.next(), result, usage));
      case Effect.Narrate.ApprovalDecided(var callId, var result) ->
          narrator(agentId)
              .narrate(
                  new AgentEvent.ApprovalDecided(
                      Identifiers.next(), callId, nameOf(agentId, turnId, callId), result));
      case Effect.Narrate.ToolCallCompleted(var callId) ->
          narrator(agentId)
              .narrate(
                  new AgentEvent.ToolCallCompleted(
                      Identifiers.next(),
                      callId,
                      nameOf(agentId, turnId, callId),
                      redeem(agentId, turnId, resultKey(callId), resultCodec)
                          .orElseGet(() -> ToolResult.error("no result was recorded"))));
    }
  }

  private String nameOf(AgentId agentId, TurnId turnId, CallId callId) {
    ToolCall call = callOf(agentId, turnId, callId);
    return call == null ? "" : call.name();
  }

  private ToolCall callOf(AgentId agentId, TurnId turnId, CallId callId) {
    return redeem(agentId, turnId, ASKED_KEY, askedCodec)
        .flatMap(
            asked -> callsIn(asked).stream().filter(call -> call.id().equals(callId)).findFirst())
        .orElse(null);
  }

  private <T> Optional<T> redeem(AgentId agentId, TurnId turnId, String key, Codec<T> codec) {
    if (key == null) {
      return Optional.empty();
    }
    return deps.claims().get(agentId, turnId, key).map(codec::decode);
  }

  /**
   * Hands work to the blocking executor and reports the answer through the {@link Dispatcher}.
   *
   * <p>This is the whole safety property in one method: nothing here holds a reference to anything
   * that can go away, so work that outlives the process that started it is simply picked up by
   * whatever answers on {@code agentId}'s behalf next.
   *
   * <p><b>{@code retryDelay}</b> is what {@link #perform} already asked {@link RetryPolicy} for,
   * before this work even started -- {@code null} means the {@code -1}-sentinel legacy path, where
   * a failure ALWAYS folds into the agent via {@code broke}, exactly as before Task 7. A real delay
   * means a failure here goes through {@code EffectStore#retry} instead: the row goes back to
   * PENDING, {@code attempts} increments as part of that write-back, and the agent hears nothing
   * about it -- this obligation was retryable-subject-to-policy, which is not the agent's business
   * until the policy actually gives up.
   */
  private <T> void run(
      Supplier<T> work,
      Function<T, Input> answer,
      Function<String, Input> broke,
      AgentId agentId,
      EffectId effectId,
      Map<String, String> carried,
      Duration retryDelay) {
    CompletableFuture.supplyAsync(work, deps.blocking())
        .whenComplete(
            (value, failure) -> {
              if (failure == null) {
                tell(agentId, answer.apply(value), effectId, carried);
                return;
              }
              String description = describe(failure);
              if (retryDelay == null) {
                tell(agentId, broke.apply(description), effectId, carried);
                return;
              }
              LOG.warn(
                  "[{}] obligation {} failed, retrying in {}: {}",
                  agentId.value(),
                  effectId,
                  retryDelay,
                  description);
              deps.effects().retry(effectId, Instant.now().plus(retryDelay));
            });
  }

  /**
   * The propagation context, serialized to the JSON object {@code nessy_effect.observability}
   * stores.
   *
   * <p>Null when there is nothing to carry — an empty map is not a carrier that failed to
   * serialize, it is work with no ambient trace, which is legitimate and common.
   */
  private static String observabilityOf(Map<String, String> carried) {
    if (carried == null || carried.isEmpty()) {
      return null;
    }
    try {
      return EngineMapper.INSTANCE.writeValueAsString(carried);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("could not serialize the trace carrier", e);
    }
  }

  /** Painted as it arrives, on the thread draining the stream — narrating is a tell. */
  private void narrateChunk(AgentId agentId, ModelEvent event) {
    switch (event) {
      case ModelEvent.TextChunk(var text) ->
          narrator(agentId).narrate(new AgentEvent.TextDelta(Identifiers.next(), text));
      case ModelEvent.ReasoningChunk(var text) ->
          narrator(agentId).narrate(new AgentEvent.ReasoningDelta(Identifiers.next(), text));
      default -> {
        // Assembled into the message, or narrated by whoever owns the fact.
      }
    }
  }

  /**
   * Everything anyone answering this call needs, built once per ask or run.
   *
   * <p>One record rather than the two context objects this replaced: a tool and an approver were
   * handed different views of the same call, so the pair had to be kept in step and an approver
   * could not see what the tool would be given.
   */
  private ToolCallRequest<JsonNode> requestFor(AgentId agentId, TurnId turnId, ToolCall call) {
    return new ToolCallRequest(
        deps.agentType(),
        agentId,
        turnId,
        call.id(),
        call.name(),
        call.arguments(),
        // Not minted here: a token is a capability, and most calls are answered on the spot and
        // never hand one out. ToolCallRequest mints on the first replyToken() and remembers it.
        () -> deps.tokens().mint(deps.agentType(), agentId, turnId, call.id()));
  }

  static String resultKey(CallId callId) {
    return "result-" + callId;
  }

  private static List<ToolCall> callsIn(List<ExchangeContentBlock> content) {
    return content.stream()
        .filter(ToolCallBlock.class::isInstance)
        .map(block -> ((ToolCallBlock) block).call())
        .toList();
  }

  private static String describe(Throwable failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    String message = cause.getMessage();
    return message == null ? cause.getClass().getSimpleName() : message;
  }
}
