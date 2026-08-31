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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.persistence.typed.PersistenceId;
import org.apache.pekko.persistence.typed.state.javadsl.CommandHandler;
import org.apache.pekko.persistence.typed.state.javadsl.DurableStateBehavior;
import org.apache.pekko.persistence.typed.state.javadsl.Effect;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.block.ToolCallBlock;
import org.jwcarman.nessy.api.block.ToolResultBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AssistantMessage;
import org.jwcarman.nessy.api.message.ToolResultMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelReplies;
import org.jwcarman.nessy.spi.model.ModelRequest;

/**
 * One turn: one observation, worked until the model has nothing left to ask for.
 *
 * <p><b>Its life IS the turn's life</b> — seconds usually, days if a tool defers. That is why the
 * agent does not passivate while a turn is in flight: passivating would kill this actor while the
 * turn it represents is still alive, and the deadlines its phase actors hold would go with it.
 *
 * <p><b>Not generic.</b> The observation was rendered to a {@link UserMessage} before this actor
 * existed, so nothing from here down needs the application's vocabulary.
 *
 * <p><b>The model call happens off this actor's thread.</b> It blocks — sometimes for minutes — and
 * an actor that blocks its dispatcher starves every other actor sharing it. The answer arrives back
 * as a message like any other.
 */
public final class TurnActor extends DurableStateBehavior<TurnActor.Command, TurnState> {

  public sealed interface Command {}

  /** Self-sent once, on creation. */
  record Begin() implements Command {}

  /** The model answered. */
  record ModelAnswered(ModelResult result) implements Command {}

  /** The model call threw: a rate limit, a timeout, a context overflow. */
  record ModelFailed(String reason) implements Command {}

  /** From one of this turn's own tool calls. */
  record ToolSettled(String callId, ToolResult result) implements Command {}

  /** An answer from the world, for a call this turn parked. */
  record RelayApproval(String callId, org.jwcarman.nessy.api.tool.ApprovalResult result)
      implements Command {}

  /** An answer from the world, for a call this turn parked. */
  record RelayResult(String callId, ToolResult result) implements Command {}

  /** What a turn needs. {@link Turns} closes over this so an agent never sees it. */
  public record Dependencies(
      AgentType agentType,
      Memory memory,
      Model model,
      String systemPrompt,
      int maxTokens,
      ToolBindings bindings,
      Set<Capability> capabilities,
      Narrator narrator,
      Claims claims,
      ReplyTokens tokens,
      Executor blocking,
      Traces traces) {}

  private final ActorContext<Command> context;
  private final Dependencies deps;
  private final Map<String, String> carried;
  private final AgentId agentId;
  private final String turnId;
  private final UserMessage input;
  private final ActorRef<NessyMessage> agent;

  /** In flight, never persisted: the exchange being assembled. */
  private AssistantMessage asked;

  private final Map<String, ToolResult> settled = new LinkedHashMap<>();

  /**
   * The calls still in flight. A cache, not a directory: an entry means "no need to look it up",
   * and it is gone the moment the call settles.
   */
  private final Map<String, ActorRef<ToolCallActor.Command>> inFlightCalls = new LinkedHashMap<>();

  private static final org.jwcarman.codec.spi.Codec<AssistantMessage> ASKED =
      JsonCodec.of(EngineMapper.INSTANCE, AssistantMessage.class);

  private static final org.jwcarman.codec.spi.Codec<ToolResult> ANSWERED =
      JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class);

  private static final String ASKED_KEY = "asked";

  /** Summed across every model call this turn made, so the closing line can report it. */
  private long inputTokens;

  private long outputTokens;

  private TurnActor(
      ActorContext<Command> context,
      Dependencies deps,
      AgentId agentId,
      String turnId,
      UserMessage input,
      ActorRef<NessyMessage> agent,
      Map<String, String> carried) {
    // The agent type goes in the TYPE half, never glued to the id: Pekko reserves "|" as its own
    // separator inside a PersistenceId, and an entity id containing one is rejected outright.
    super(PersistenceId.of("turn-" + deps.agentType().name(), agentId.value()));
    this.context = context;
    this.deps = deps;
    this.carried = carried;
    this.agentId = agentId;
    this.turnId = turnId;
    this.input = input;
    this.agent = agent;
  }

  public static Behavior<Command> create(
      Dependencies deps,
      AgentId agentId,
      String turnId,
      UserMessage input,
      ActorRef<NessyMessage> agent,
      Map<String, String> carried) {
    Objects.requireNonNull(deps, "deps must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(turnId, "turnId must not be null");
    Objects.requireNonNull(input, "input must not be null");
    Objects.requireNonNull(agent, "agent must not be null");
    return Behaviors.setup(
        context -> {
          context.getSelf().tell(new Begin());
          return new TurnActor(context, deps, agentId, turnId, input, agent, carried);
        });
  }

  @Override
  public TurnState emptyState() {
    return TurnState.idle();
  }

  /**
   * Every command handled inside a span parented to the message that started this turn.
   *
   * <p><b>This is the hop that makes the tree.</b> A turn is a child ACTOR, and its commands are
   * not {@link NessyMessage} — they carry no headers of their own — so the context is captured once
   * when the turn is spawned and re-entered on every command. Everything the handler then does,
   * including handing the model call or a tool to the blocking executor, happens inside this scope;
   * a context-propagating executor carries it the rest of the way.
   */
  /** Same reason as {@code AgentActor}: an interceptor does not enclose a persistent handler. */
  @Override
  public CommandHandler<Command, TurnState> commandHandler() {
    CommandHandler<Command, TurnState> handler = traced();
    return (state, command) ->
        deps.traces()
            .inSpan(
                "turn " + command.getClass().getSimpleName(),
                carried,
                () -> {
                  deps.traces().detail("nessy.agent.id", agentId.value());
                  deps.traces().detail("nessy.turn.id", turnId);
                  return handler.apply(state, command);
                });
  }

  private CommandHandler<Command, TurnState> traced() {
    return newCommandHandlerBuilder()
        .forAnyState()
        .onCommand(Begin.class, this::onBegin)
        .onCommand(ModelAnswered.class, this::onModelAnswered)
        .onCommand(ModelFailed.class, this::onModelFailed)
        .onCommand(ToolSettled.class, this::onToolSettled)
        .onCommand(RelayApproval.class, this::onRelayApproval)
        .onCommand(RelayResult.class, this::onRelayResult)
        .build();
  }

  /**
   * Claim the turn, remember what it is answering, and ask the model.
   *
   * <p>Remembering BEFORE persisting is deliberate: a crash between the two leaves the input in the
   * transcript and no turn claiming it, which recovery re-drives — and {@code remember} tolerates
   * that. The other order would leave a turn claiming an input nothing recorded.
   */
  private Effect<TurnState> onBegin(TurnState state, Begin command) {
    if (state.describes(turnId)) {
      if (state.phase() instanceof Phase.WorkingTools working) {
        return Effect().none().thenRun(ignored -> resumeTools(working));
      }
      // Recovered, not started: this document is ours, so the input is already on the record and
      // remembering again would double it.
      //
      // Re-driving from the transcript is always safe, and that is a consequence of how the
      // exchange is written: an assistant turn and the message answering it go in TOGETHER or not
      // at all, so a transcript never holds half of one. Whatever the turn was doing, asking the
      // model again from what IS recorded is a correct continuation.
      return Effect().none().thenRun(ignored -> callModel());
    }
    deps.narrator().narrate(new AgentEvent.TurnStarted(Identifiers.next()));
    deps.memory().remember(agentId, input);
    return Effect()
        .persist(new TurnState(turnId, new Phase.CallingModel()))
        .thenRun(persisted -> callModel());
  }

  /**
   * Asks the model, INSIDE a scope re-entered from the turn's own context.
   *
   * <p>Re-entered rather than inherited, because every caller of this reaches it through a {@code
   * thenRun} — which Pekko runs after persistence commits, on whatever thread it likes, and always
   * after the command's scope has closed. A submit made out there captures nothing, and the chat
   * span it produces becomes a root instead of a child. Measured: the actor tree nested correctly
   * and every model call still opened its own trace, until this scope existed.
   */
  private void callModel() {
    deps.traces()
        .inSpan(
            "turn call model",
            carried,
            () -> {
              callModelInScope();
              return null;
            });
  }

  private void callModelInScope() {
    ModelRequest request =
        new ModelRequest(
            deps.memory().recall(agentId),
            deps.systemPrompt(),
            deps.maxTokens(),
            deps.bindings().tools(),
            deps.capabilities());
    context.pipeToSelf(
        CompletableFuture.supplyAsync(
            () -> ModelReplies.drain(deps.model().stream(request), this::narrateChunk),
            deps.blocking()),
        (result, failure) ->
            failure == null ? new ModelAnswered(result) : new ModelFailed(describe(failure)));
  }

  /**
   * Paints what is arriving, as it arrives.
   *
   * <p>Runs on the blocking thread draining the stream rather than on this actor's, which is safe
   * because narrating is a tell. Only the chunks are narrated here: a tool call is narrated by the
   * call's own actor, and the closing line by the turn, so nothing is said twice.
   */
  private void narrateChunk(ModelEvent event) {
    switch (event) {
      case ModelEvent.TextChunk chunk ->
          deps.narrator().narrate(new AgentEvent.TextDelta(Identifiers.next(), chunk.text()));
      case ModelEvent.ThinkingChunk chunk ->
          deps.narrator().narrate(new AgentEvent.ThinkingDelta(Identifiers.next(), chunk.text()));
      case ModelEvent.RedactedThinkingEmitted redacted ->
          deps.narrator()
              .narrate(new AgentEvent.RedactedThinking(Identifiers.next(), redacted.data()));
      default -> {
        // Assembled into the message, or narrated by whoever owns the fact.
      }
    }
  }

  /**
   * What the model said: an answer, a request for tools, or a refusal.
   *
   * <p>A reply asking for tools is NOT remembered here. An assistant turn naming unanswered calls
   * is not a valid transcript, so it is held until every call has settled and then written together
   * with the message answering it — one write, never a half-exchange.
   */
  private Effect<TurnState> onModelAnswered(TurnState state, ModelAnswered command) {
    if (command.result() instanceof ModelResult.Refused refused) {
      count(refused.usage().inputTokens(), refused.usage().outputTokens());
      return finish(new TurnResult.Refused(refused.category(), refused.explanation()));
    }
    ModelResult.Replied replied = (ModelResult.Replied) command.result();
    count(replied.usage().inputTokens(), replied.usage().outputTokens());
    deps.narrator().narrate(new AgentEvent.AssistantSaid(Identifiers.next(), replied.message()));
    List<ToolCall> calls = toolCallsIn(replied.message());
    if (calls.isEmpty()) {
      deps.memory().remember(agentId, replied.message());
      return finish(
          replied.stopReason() == StopReason.MAX_TOKENS
              ? new TurnResult.Truncated()
              : new TurnResult.Completed());
    }
    asked = replied.message();
    settled.clear();
    // Claimed before anything runs. The asking message is what pins the CALL IDS: without it a
    // recovered turn would have to ask the model again, get fresh ids, and re-run tools whose
    // answers it already had.
    deps.claims().put(agentId, turnId, ASKED_KEY, ASKED.encode(asked));
    calls.forEach(this::runCall);
    return Effect()
        .persist(state.at(new Phase.WorkingTools(calls.stream().map(ToolCall::id).toList())));
  }

  private void runCall(ToolCall call) {
    inFlightCalls.put(
        call.id(),
        context.spawn(
            ToolCallActor.create(
                deps.agentType(),
                agentId,
                call,
                deps.bindings(),
                deps.narrator(),
                deps.tokens(),
                deps.blocking(),
                context.getSelf(),
                deps.traces(),
                carried),
            "call-" + call.id()));
  }

  private Effect<TurnState> onRelayApproval(TurnState state, RelayApproval command) {
    ActorRef<ToolCallActor.Command> call = inFlightCalls.get(command.callId());
    if (call != null) {
      call.tell(new ToolCallActor.RelayApproval(command.result()));
    }
    return Effect().none();
  }

  private Effect<TurnState> onRelayResult(TurnState state, RelayResult command) {
    ActorRef<ToolCallActor.Command> call = inFlightCalls.get(command.callId());
    if (call != null) {
      call.tell(new ToolCallActor.RelayResult(command.result()));
    }
    return Effect().none();
  }

  /**
   * One call is done. When the last of them lands, the exchange goes to the transcript whole and
   * the model is asked again — which is what makes a turn a round trip rather than a single call.
   *
   * <p>Results are held in memory until then. A crash loses them and the turn re-drives, which is
   * correct but wasteful; parking them in claims is the outstanding piece.
   */
  private Effect<TurnState> onToolSettled(TurnState state, ToolSettled command) {
    settled.put(command.callId(), command.result());
    inFlightCalls.remove(command.callId());
    deps.claims()
        .put(agentId, turnId, resultKey(command.callId()), ANSWERED.encode(command.result()));
    if (!(state.phase() instanceof Phase.WorkingTools working)
        || !settled.keySet().containsAll(working.callIds())) {
      return Effect().none();
    }
    completeExchange(working);
    return Effect().persist(state.at(new Phase.CallingModel())).thenRun(persisted -> callModel());
  }

  /**
   * Every call has answered, so the exchange goes to the transcript WHOLE — the asking message and
   * the message answering it, in one write. That is what keeps a transcript from ever holding half
   * an exchange, and therefore what makes re-driving after a crash always safe.
   */
  private void completeExchange(Phase.WorkingTools working) {
    List<ToolResultBlock> blocks =
        working.callIds().stream().map(id -> ToolResultBlock.of(id, settled.get(id))).toList();
    deps.memory().remember(agentId, asked, new ToolResultMessage(blocks));
    asked = null;
    settled.clear();
  }

  private Effect<TurnState> onModelFailed(TurnState state, ModelFailed command) {
    return finish(new TurnResult.Failed(command.reason()));
  }

  private void count(long input, long output) {
    inputTokens += input;
    outputTokens += output;
  }

  /** The closing line, then the agent is told it may start another. */
  private static String resultKey(String callId) {
    return "result-" + callId;
  }

  /**
   * Picks up a turn that was working tools when it died.
   *
   * <p>Reads back what is already answered and re-runs only what is not — which is the whole point
   * of claiming: without it, recovery means asking the model again, getting fresh call ids, and
   * re-running tools that already did their work. For a tool with a side effect that is not merely
   * wasteful.
   */
  private void resumeTools(Phase.WorkingTools working) {
    byte[] claimed = deps.claims().get(agentId, turnId, ASKED_KEY).orElse(null);
    if (claimed == null) {
      // The asking message never landed, so nothing can have run against it. Start over.
      callModel();
      return;
    }
    asked = ASKED.decode(claimed);
    settled.clear();
    List<ToolCall> calls = toolCallsIn(asked);
    for (ToolCall call : calls) {
      deps.claims()
          .get(agentId, turnId, resultKey(call.id()))
          .ifPresentOrElse(
              answer -> settled.put(call.id(), ANSWERED.decode(answer)), () -> runCall(call));
    }
    if (settled.size() == calls.size()) {
      completeExchange(working);
    }
  }

  private Effect<TurnState> finish(TurnResult result) {
    // The turn is over, so its scratch space goes -- by KIND, which sweeps any orphan too.
    deps.claims().deleteTurn(agentId, turnId);
    deps.narrator()
        .narrate(
            new AgentEvent.TurnEnded(
                Identifiers.next(), result, new Usage(inputTokens, outputTokens)));
    return Effect()
        .none()
        .thenRun(ignored -> agent.tell(new NessyMessage.TurnFinished(turnId, Map.of())))
        .thenStop();
  }

  private static List<ToolCall> toolCallsIn(AssistantMessage message) {
    return message.content().stream()
        .filter(ToolCallBlock.class::isInstance)
        .map(block -> ((ToolCallBlock) block).call())
        .toList();
  }

  private static String describe(Throwable failure) {
    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
