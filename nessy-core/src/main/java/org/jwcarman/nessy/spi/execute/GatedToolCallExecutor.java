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
package org.jwcarman.nessy.spi.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.event.ApprovalRequested;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.PolicyDecision;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.internal.EngineObservations;
import org.jwcarman.nessy.internal.ToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one door into a tool's execution: gate, then invoke. Ported from the retired in-process
 * engine's {@code decide}/{@code requestApproval}/{@code executeTool} trio, now living at the
 * executor seam so a call's authority question and its performance are answered by the same method
 * instead of two effects the loop has to sequence itself.
 *
 * <p>A grant's {@link UsagePolicy} is consulted first, fail-closed on a broken policy; only {@link
 * PolicyDecision.RequireApproval} ever reaches {@link #approver}. A call to a tool this executor
 * does not know at all becomes the one model-visible "No such tool" error, unnarrated as a gate
 * verdict since none happened. A call to a tool that is registered but carries no grant — reachable
 * only through an exotic {@link ToolRegistry} whose {@code find} resolves beyond its own {@code
 * specs()} — is denied outright rather than run ungated.
 */
public final class GatedToolCallExecutor implements ToolCallExecutor {

  private static final Logger LOGGER = LoggerFactory.getLogger(GatedToolCallExecutor.class);
  private static final String DENIED_PREFIX = "Denied: ";

  private final ToolRegistry tools;
  private final Map<String, ToolGrant> grants;
  private final Approver approver;
  private final ToolInvoker invoker;
  private final EventEmitter emitter;
  private final ObservationRegistry observations;

  public GatedToolCallExecutor(
      ToolRegistry tools,
      Map<String, ToolGrant> grants,
      Approver approver,
      ObjectMapper mapper,
      EventEmitter emitter,
      ObservationRegistry observations) {
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.grants = Map.copyOf(Objects.requireNonNull(grants, "grants must not be null"));
    requireEveryRegisteredToolIsGranted(this.tools, this.grants);
    this.approver = Objects.requireNonNull(approver, "approver must not be null");
    this.invoker = new ToolInvoker(Objects.requireNonNull(mapper, "mapper must not be null"));
    this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
    this.observations = Objects.requireNonNull(observations, "observations must not be null");
  }

  /**
   * The wiring-time belt for the {@code tools}/{@code grants} pair: every tool {@code
   * tools.specs()} advertises to the model must have a grant, or the model could be offered a tool
   * whose authority was never decided. Ported unchanged from the retired in-process engine.
   */
  private static void requireEveryRegisteredToolIsGranted(
      ToolRegistry tools, Map<String, ToolGrant> grants) {
    for (ToolSpec spec : tools.specs()) {
      if (!grants.containsKey(spec.name())) {
        throw new IllegalArgumentException("no grant for tool: " + spec.name());
      }
    }
  }

  @Override
  public Awaited<ConversationEvent> execute(
      ToolCall call, ConversationState state, TurnObserver observer) {
    ToolGrant grant = grants.get(call.name());
    if (grant == null) {
      if (tools.find(call.name()).isPresent()) {
        String reason = "no grant for tool: " + call.name();
        return finished(call, state, ToolResult.error(reason), observer, new Decision.Deny(reason));
      }
      return invoke(call, state, observer);
    }
    PolicyDecision decision = evaluate(grant.policy(), call, state);
    return switch (decision) {
      case PolicyDecision.Allow _ -> {
        observer.on(new TurnEvent.ToolCallDecided(call, Decision.allow()));
        yield invoke(call, state, observer);
      }
      case PolicyDecision.Deny(String reason) ->
          finished(
              call,
              state,
              ToolResult.error(DENIED_PREFIX + reason),
              observer,
              new Decision.Deny(reason));
      case PolicyDecision.RequireApproval _ -> gate(grant, call, state, observer);
    };
  }

  @Override
  public Awaited<ConversationEvent> resume(
      ToolCall call, ToolResolution resolution, ConversationState state, TurnObserver observer) {
    return switch (resolution) {
      case ToolResolution.Decided(Decision decision) ->
          switch (decision) {
            case Decision.Allow _ -> {
              observer.on(new TurnEvent.ToolCallDecided(call, decision));
              yield invoke(call, state, observer);
            }
            case Decision.Deny(String reason) ->
                finished(call, state, ToolResult.error(DENIED_PREFIX + reason), observer, decision);
          };
      case ToolResolution.Completed(ToolResult result) ->
          finished(call, state, result, observer, null);
    };
  }

  /**
   * Consults {@link #approver} for a call whose policy deferred, inside the {@code
   * nessy.approval.wait} span. An {@link ApprovalRequested} system event is emitted first, so a
   * listener can see the question was asked before it is known whether the approver will answer or
   * park.
   */
  private Awaited<ConversationEvent> gate(
      ToolGrant grant, ToolCall call, ConversationState state, TurnObserver observer) {
    Tool<?> tool = grant.tool();
    ApprovalRequest request =
        new ApprovalRequest(state.id(), call, describeForApproval(tool, call));
    emitter.emit(new ApprovalRequested(state.id(), request));
    Observation observation = EngineObservations.approvalWait(observations, tool.name());
    Awaited<Decision> decision;
    try (var _ = observation.openScope()) {
      decision = approver.approve(request);
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
    return switch (decision) {
      case Awaited.Ready<Decision>(Decision.Allow()) -> {
        observer.on(new TurnEvent.ToolCallDecided(call, Decision.allow()));
        yield invoke(call, state, observer);
      }
      case Awaited.Ready<Decision>(Decision.Deny(String reason)) ->
          finished(
              call,
              state,
              ToolResult.error(DENIED_PREFIX + reason),
              observer,
              new Decision.Deny(reason));
      case Awaited.Parked<Decision>(var token) -> Awaited.parked(token);
    };
  }

  /**
   * Runs one policy, fail-closed: a policy is supposed to be pure and total, but a broken or
   * incomplete one must never become an allow. Ported unchanged from the retired in-process engine.
   */
  private static PolicyDecision evaluate(
      UsagePolicy policy, ToolCall call, ConversationState state) {
    try {
      PolicyDecision decision = policy.evaluate(call, state);
      return decision != null ? decision : new PolicyDecision.Deny("policy returned no decision");
    } catch (RuntimeException e) {
      return new PolicyDecision.Deny(describe(e));
    }
  }

  /**
   * Renders a call for the approval prompt without letting malformed arguments blow up the session.
   * Ported unchanged from the retired in-process engine.
   */
  private String describeForApproval(Tool<?> tool, ToolCall call) {
    try {
      return invoker.describe(tool, call);
    } catch (RuntimeException _) {
      return call.name() + "(" + call.arguments() + ")";
    }
  }

  /**
   * Runs a cleared call. {@link TurnEvent.ToolCallCompleted} is narrated exactly when a {@code
   * ToolFinished} fact is yielded — every outcome except a park, since a parked tool has not
   * finished; {@link #resume} narrates it later once the slow completion arrives. An unknown tool
   * yields the one model-visible "No such tool" error without opening a span or narrating a gate
   * verdict — no verdict happened.
   */
  private Awaited<ConversationEvent> invoke(
      ToolCall call, ConversationState state, TurnObserver observer) {
    Optional<Tool<?>> found = tools.find(call.name());
    if (found.isEmpty()) {
      return finished(
          call, state, ToolResult.error("No such tool: " + call.name()), observer, null);
    }
    Observation observation = EngineObservations.toolCall(observations, call.name(), call.id());
    try (var _ = observation.openScope()) {
      return invokeAndRecord(state, call, found.get(), observer, observation);
    } catch (RuntimeException e) {
      observation.error(e);
      throw e;
    } finally {
      observation.stop();
    }
  }

  /**
   * Invokes one tool and turns its outcome into the model-visible fact {@link #invoke} returns —
   * extracted so {@link #invoke}'s own {@code try} is never nested (S1141).
   */
  private Awaited<ConversationEvent> invokeAndRecord(
      ConversationState state,
      ToolCall call,
      Tool<?> tool,
      TurnObserver observer,
      Observation observation) {
    Awaited<ToolResult> awaited;
    try {
      awaited = invoker.invoke(tool, call, new ToolContext(state.id(), teed(call, observer)));
    } catch (RuntimeException e) {
      // Factor 9: the model sees a compact error and gets to recover. It
      // never sees a stack trace, and the loop never dies on a bad tool. The
      // span still has to say so: without this, every tool failure reports
      // as a success to anything watching the span.
      observation.error(e);
      ToolResult result = ToolResult.error(describe(e));
      EngineObservations.recordOutcome(observation, result);
      return finished(call, state, result, observer, null);
    }
    return switch (awaited) {
      case Awaited.Ready<ToolResult>(ToolResult result) -> {
        EngineObservations.recordOutcome(observation, result);
        yield finished(call, state, result, observer, null);
      }
      case Awaited.Parked<ToolResult>(var token) -> Awaited.parked(token);
    };
  }

  /**
   * The emitter a running tool is handed: everything passes through to {@link #emitter} untouched,
   * but a {@link ToolProgress} is also narrated to {@code observer} as {@link
   * TurnEvent.ToolCallProgressed}, carrying the executor's own authoritative {@code call} rather
   * than trusting whatever the tool self-reported. A throwing observer here is the ruled exception
   * to fail-loud (texture never alters the record): the throw is caught, logged, and dropped, so a
   * bug in a UI's narration can never turn a succeeding tool call into a failed one.
   */
  private EventEmitter teed(ToolCall call, TurnObserver observer) {
    return event -> {
      emitter.emit(event);
      if (event instanceof ToolProgress(var _, var _, String message)) {
        try {
          observer.on(new TurnEvent.ToolCallProgressed(call, message));
        } catch (RuntimeException e) {
          LOGGER.warn("turn observer failed during tool-progress narration; narration dropped", e);
        }
      }
    };
  }

  /**
   * Narrates {@link TurnEvent.ToolCallDecided} (only when a gate verdict actually happened) then
   * {@link TurnEvent.ToolCallCompleted}, in that order, and returns the settled {@code
   * ToolFinished} fact.
   */
  private Awaited<ConversationEvent> finished(
      ToolCall call,
      ConversationState state,
      ToolResult result,
      TurnObserver observer,
      Decision decision) {
    if (decision != null) {
      observer.on(new TurnEvent.ToolCallDecided(call, decision));
    }
    observer.on(new TurnEvent.ToolCallCompleted(call, result));
    return Awaited.ready(new ConversationEvent.ToolFinished(state.id(), call, result));
  }

  private static String describe(RuntimeException e) {
    String message = e.getMessage();
    return message == null
        ? e.getClass().getSimpleName()
        : e.getClass().getSimpleName() + ": " + message;
  }
}
