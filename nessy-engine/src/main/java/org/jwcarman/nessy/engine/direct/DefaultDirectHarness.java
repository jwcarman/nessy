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
package org.jwcarman.nessy.engine.direct;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPromptSource;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.core.ActionRequest;
import org.jwcarman.nessy.engine.core.AgentCommand;
import org.jwcarman.nessy.engine.core.AgentEvent;
import org.jwcarman.nessy.engine.core.AgentEvents;
import org.jwcarman.nessy.engine.core.AgentState;
import org.jwcarman.nessy.engine.core.Decision;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.effect.EffectTerms;
import org.jwcarman.nessy.engine.effect.EffectTermsSource;
import org.jwcarman.nessy.engine.history.EventStreamHistory;
import org.jwcarman.nessy.engine.history.Transcript;
import org.jwcarman.nessy.engine.inference.ContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceContextAssembler;
import org.jwcarman.nessy.engine.inference.InferenceInvocation;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.OutputSchema;
import org.jwcarman.nessy.inference.Seq;
import org.jwcarman.nessy.inference.Toolset;
import org.jwcarman.nessy.inference.TurnId;
import org.jwcarman.nessy.inference.block.Block;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.spi.lock.Locks;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.jwcarman.nessy.spi.store.Payloads;
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

  private final Locks locks;
  private final AgentType agentType;
  private final AgentEvents events;
  private final Payloads payloads;
  private final InferenceProvider provider;
  private final SystemPromptSource systemPrompt;
  private final InferenceOptions options;
  private final InputRenderer<I> renderer;
  private final Tools tools;

  /**
   * What is sent to the provider so it constrains the shape of every answer this harness gets --
   * empty for a harness that asked for none, which is what keeps that path free of a schema.
   */
  private final Optional<OutputSchema> outputSchema;

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

  /** What the model is shown, assembled the same way the queued door assembles it. */
  private final InferenceContextAssembler assembler;

  /**
   * The handle everything watching this agent is reached through.
   *
   * <p>The same abstraction the queued door talks to, and for the same reasons: telling listeners
   * in order, off this thread, isolated from each other. This door once kept its own list and told
   * them inline, which meant no listener an application registered could reach it at all, and a
   * slow one sat between the caller and their answer.
   */
  private final Narrator narrator;

  /** Built once: a tool's shape cannot change between calls, so neither can what is on offer. */
  private final Toolset toolset;

  /** What a deadline is measured from -- stepped in a test, wall-clock everywhere else. */
  private final Clock clock;

  /**
   * What each effect this harness performs is worth, resolved the same way the queued door does.
   */
  private final EffectTermsSource terms;

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
      InferenceProvider provider,
      SystemPromptSource systemPrompt,
      InferenceOptions options,
      InputRenderer<I> renderer,
      Tools tools,
      BiFunction<AgentId, AgentEvent.InferenceAnswered, Outcome<O>> reading,
      Optional<OutputSchema> outputSchema,
      List<Summarizer> summaries,
      int maxTail,
      List<AmbientSource> ambient,
      Narrator narrator,
      Clock clock,
      EffectTermsSource terms,
      ExecutorService effects) {
    this.locks = locks;
    this.agentType = agentType;
    this.events = events;
    this.payloads = payloads;
    this.provider = provider;
    this.systemPrompt = systemPrompt;
    this.options = options;
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
    this.tools = tools;
    this.reading = Objects.requireNonNull(reading, "reading must not be null");
    this.outputSchema = Objects.requireNonNull(outputSchema, "outputSchema must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.terms = Objects.requireNonNull(terms, "terms must not be null");
    this.effects = Objects.requireNonNull(effects, "effects must not be null");
    // The queued door's assembler, unchanged. Ambient, the tail window and summaries are one job
    // however the turn was started, and a second implementation of it would drift.
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
    this.assembler =
        new ContextAssembler(
            // Scoped as it reads: content belongs to an agent, so the projection of one agent's
            // turns resolves only that agent's payloads.
            (type, id) -> new EventStreamHistory(events, new Transcript(payloads.forAgent(id)), id),
            summaries,
            maxTail,
            ambient);
    this.toolset = Toolset.of(tools.offers());
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
    return locks.tryWithLock(agent.value().toString(), turn).orElse(new Outcome.Busy<>());
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
        pending.add(perform(agent, content, effect, history));
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
        agent.value().toString(),
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
   */
  private AgentCommand perform(
      AgentId agent, Payloads content, AgentEffect effect, List<AgentEvent> history) {
    return within(
        termsFor(effect),
        () ->
            switch (effect) {
              case AgentEffect.Infer _ -> new AgentCommand.CompleteInference(infer(agent, content));

              case AgentEffect.Approve approve -> approve(agent, content, approve, history);

              case AgentEffect.CallTool call -> callTool(agent, content, call, history);
            });
  }

  /** What this effect is worth, asked of the same resolver the queued door writes a row from. */
  private EffectTerms termsFor(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Infer infer -> terms.termsFor(infer);
      case AgentEffect.Approve approve -> terms.termsFor(approve);
      case AgentEffect.CallTool call -> terms.termsFor(call);
    };
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
   * Asks whoever guards this tool, now.
   *
   * <p>A person at a terminal is the case this door serves best: they are already waiting on the
   * answer, so asking them costs nothing that was not already being spent. What cannot cross is an
   * approver that parks -- a desk that replies tomorrow has nowhere to put the waiting here, so it
   * is a denial with a reason rather than a turn that never ends.
   */
  private AgentCommand approve(
      AgentId agent, Payloads content, AgentEffect.Approve approve, List<AgentEvent> history) {
    ToolBinding<?> binding = tools.find(approve.toolName()).orElse(null);
    if (binding == null) {
      return new AgentCommand.CompleteApproval(
          approve.callId(),
          new AgentCommand.ApprovalOutcome.Denied("no such tool", Optional.empty()));
    }
    ApprovalRequest question;
    try {
      question =
          binding.question(
              agentType,
              agent,
              turnOf(history),
              approve.callId(),
              argumentsOf(content, approve.callId(), history),
              clock.instant(),
              new ReplyToken(approve.callId().value()));
    } catch (RuntimeException unreadable) {
      // The sentence a person consents to is rendered from the tool's own input type, so a call
      // whose arguments will not read has no question to ask about it -- and could not run
      // whatever anybody answered. The queued door discharges the CALL here; this door is
      // answering an approval effect, and the core takes a tool outcome only for a call already
      // running, so it is discharged as a denial whose reason is the parse error. The model reads
      // it and can correct itself, which is the part that matters.
      return new AgentCommand.CompleteApproval(
          approve.callId(),
          new AgentCommand.ApprovalOutcome.Denied(
              "the arguments could not be read: " + unreadable.getMessage(), Optional.empty()));
    }
    // Before asking, not after: an approver that blocks on a person is exactly when a watcher
    // needs to know one is being asked, and after the answer it is too late to be worth saying.
    tell(agent, new Narration.ApprovalSought(approve.callId(), question.action()));
    Awaited<ApprovalResult> answer;
    try {
      answer = binding.approve(question);
    } catch (RuntimeException broken) {
      // An approver that throws is a gate that failed, and a gate that failed is a no. Never a
      // yes, and never an exception out of a turn the caller is blocked on.
      return new AgentCommand.CompleteApproval(
          approve.callId(),
          new AgentCommand.ApprovalOutcome.Denied(
              "the approver failed: " + broken.getMessage(), Optional.empty()));
    }
    return switch (answer) {
      case Awaited.Ready(ApprovalResult result) ->
          switch (result) {
            case ApprovalResult.Approved(var reference) ->
                new AgentCommand.CompleteApproval(
                    approve.callId(), new AgentCommand.ApprovalOutcome.Approved(reference));
            case ApprovalResult.Denied(String reason, var reference) ->
                new AgentCommand.CompleteApproval(
                    approve.callId(), new AgentCommand.ApprovalOutcome.Denied(reason, reference));
          };
      case Awaited.Deferred<ApprovalResult> _ ->
          new AgentCommand.CompleteApproval(
              approve.callId(),
              new AgentCommand.ApprovalOutcome.Denied(
                  "approval was deferred, and nothing here can wait for it", Optional.empty()));
    };
  }

  private AgentCommand callTool(
      AgentId agent, Payloads content, AgentEffect.CallTool call, List<AgentEvent> history) {
    ToolBinding<?> binding = tools.find(call.toolName()).orElse(null);
    if (binding == null) {
      return new AgentCommand.CompleteToolCall(
          call.callId(), new AgentCommand.ToolOutcome.Failed("no such tool"));
    }
    try {
      return switch (binding.call(
          agentType,
          agent,
          turnOf(history),
          call.callId(),
          call.toolName(),
          argumentsOf(content, call.callId(), history),
          clock.instant().plus(binding.timeout()),
          new ReplyToken(call.callId().value()))) {
        case Awaited.Ready(ToolResult result) -> completed(content, call, result);
        // A tool that wants to answer later has nowhere to put the answer on this door.
        case Awaited.Deferred<ToolResult> _ ->
            new AgentCommand.CompleteToolCall(
                call.callId(),
                new AgentCommand.ToolOutcome.Failed(
                    "the tool deferred, and nothing here can wait for it"));
      };
    } catch (RuntimeException broken) {
      // A sentence, because the model is going to read it. Names what went wrong, never the
      // values involved.
      return new AgentCommand.CompleteToolCall(
          call.callId(), new AgentCommand.ToolOutcome.Failed(broken.getMessage()));
    }
  }

  private AgentCommand completed(Payloads content, AgentEffect.CallTool call, ToolResult result) {
    return switch (result) {
      // Claim-checked on the way back, so a result crosses into the core as a reference and never
      // as content.
      case ToolResult.Success(var blocks) ->
          new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Succeeded(content.put(blocks)));
      case ToolResult.Failure(String message) ->
          new AgentCommand.CompleteToolCall(
              call.callId(), new AgentCommand.ToolOutcome.Failed(message));
    };
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

  /** The turn these events belong to: the last one started. */
  private TurnId turnOf(List<AgentEvent> history) {
    return history.reversed().stream()
        .filter(AgentEvent.TurnStarted.class::isInstance)
        .map(AgentEvent.TurnStarted.class::cast)
        .findFirst()
        .map(AgentEvent.TurnStarted::turn)
        .orElseThrow(() -> new IllegalStateException("a call outside any turn"));
  }

  private AgentCommand.InferenceOutcome infer(AgentId agent, Payloads content) {
    InferenceRequest request =
        new InferenceRequest(
            systemPrompt.forAgent(agent),
            assembler.assemble(new InferenceInvocation(agentType, agent, options)),
            toolset,
            options,
            outputSchema);

    tell(agent, new Narration.Thinking());
    return switch (provider.infer(request, narratorFor(agent))) {
      case InferenceResult.Answer(List<Block.AnswerContent> blocks, var _) ->
          new AgentCommand.InferenceOutcome.Answered(content.put(blocks));
      case InferenceResult.Refusal(String category, var _) ->
          new AgentCommand.InferenceOutcome.Refused(category);
      case InferenceResult.Fault(var failure, var _) ->
          new AgentCommand.InferenceOutcome.Failed(failure);
      case InferenceResult.Actions(List<Block.ActionRequestContent> blocks, var _) -> {
        commentary(agent, blocks);
        yield new AgentCommand.InferenceOutcome.RequestedActions(
            content.put(blocks), requested(blocks));
      }
    };
  }

  /**
   * What the model said while deciding to act, announced where the words still are.
   *
   * <p>Not from the event afterwards: by then it holds a reference, and narrating from it would
   * mean reading back content this method already has in its hand.
   */
  private void commentary(AgentId agent, List<Block.ActionRequestContent> blocks) {
    blocks.stream()
        .filter(Block.Commentary.class::isInstance)
        .map(Block.Commentary.class::cast)
        .forEach(said -> tell(agent, new Narration.Commentary(said.text())));
  }

  /** Which calls a request obliges an outcome for, in the order the model made them. */
  private static List<ActionRequest> requested(List<Block.ActionRequestContent> blocks) {
    return blocks.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(call -> (ActionRequest) new ActionRequest.ToolCall(call.id(), call.name()))
        .toList();
  }

  /**
   * What a provider says while it is still saying it, turned into events for whoever is watching.
   *
   * <p>Best-effort by contract: a listener that throws is not allowed to fail a turn the caller is
   * waiting on, and a watcher that misses every fragment still gets the answer.
   */
  private InferenceNarrator narratorFor(AgentId agent) {
    return narrator.forAgent(agentType, agent).forInference();
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
  private void tell(AgentId agent, org.jwcarman.nessy.api.Narration event) {
    narrator.narrate(agentType, agent, event);
  }

  private String argumentsOf(Payloads content, CallId callId, List<AgentEvent> history) {
    return history.reversed().stream()
        .filter(AgentEvent.ActionsRequested.class::isInstance)
        .map(AgentEvent.ActionsRequested.class::cast)
        .findFirst()
        .map(asked -> resolveCall(content, asked, callId))
        .orElseThrow(() -> new IllegalStateException("no request holds " + callId));
  }

  private String resolveCall(Payloads content, AgentEvent.ActionsRequested asked, CallId callId) {
    return switch (content.get(asked.request())) {
      case Payloads.Resolved.Found(List<Block> blocks) ->
          blocks.stream()
              .filter(Block.ToolCall.class::isInstance)
              .map(Block.ToolCall.class::cast)
              .filter(tc -> tc.id().equals(callId))
              .findFirst()
              .map(Block.ToolCall::arguments)
              .orElseThrow(() -> new IllegalStateException("no call " + callId));
      case Payloads.Resolved.Missing _ ->
          throw new IllegalStateException("no payload behind " + asked.request());
    };
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
