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
package org.jwcarman.nessy.engine.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * The event stream, read back as the conversation a model is shown.
 *
 * <p>Events carry identifiers, status and references; a provider wants {@link Turn}s full of
 * blocks. This is the one place the claim check is read, and the only place the two shapes meet.
 *
 * <p><b>Content arrays come back whole and in order.</b> A vendor signature may cover an entire
 * array -- {@code Block.Provider}'s javadoc is explicit that order is part of the payload and that
 * siblings must not be dropped -- so one reference resolves to one complete array, reassembled
 * exactly as it arrived. Nothing here filters by block kind.
 *
 * <p><b>Approvals do not appear.</b> {@code ToolApproved} is the agent's own bookkeeping: the model
 * asked for a call and either got a result or was refused, and that a human said yes in between is
 * not part of the conversation it is having.
 */
public final class Transcript {

  private final Payloads payloads;

  public Transcript(Payloads payloads) {
    this.payloads = payloads;
  }

  public List<Turn> of(List<AgentEvent> events) {
    // Every payload in the window, in one ask. Resolving as each event is reached would be a
    // round trip per block -- free against a map, and one query per turn against a database.
    Map<PayloadRef, Payloads.Resolved> resolved = payloads.get(referencedBy(events));

    List<Turn> turns = new ArrayList<>();
    Open open = null;

    for (AgentEvent event : events) {
      switch (event) {
        case AgentEvent.TurnStarted started ->
            open = Open.on(started, resolve(resolved, started.input()));

        case AgentEvent.ActionsRequested requested ->
            require(open, event)
                .ask(
                    requested.seq(),
                    cast(resolve(resolved, requested.request())),
                    requested.actions());

        case AgentEvent.ToolSucceeded done ->
            require(open, event)
                .succeeded(
                    new ToolOutcome.Succeeded(
                        done.callId(), cast(resolve(resolved, done.result()))),
                    done.rendered());

        case AgentEvent.ToolFailed done ->
            require(open, event).outcome(new ToolOutcome.Failed(done.callId(), done.message()));

        case AgentEvent.ToolDenied done ->
            require(open, event).outcome(new ToolOutcome.Denied(done.callId(), done.reason()));

        case AgentEvent.InferenceAnswered answered -> {
          turns.add(
              require(open, event)
                  .closed(new TurnResult.Answered(cast(resolve(resolved, answered.answer())))));
          open = null;
        }
        case AgentEvent.InferenceRefused refused -> {
          turns.add(require(open, event).closed(new TurnResult.Refused()));
          open = null;
        }
        case AgentEvent.InferenceFailed failed -> {
          turns.add(require(open, event).closed(new TurnResult.Failed()));
          open = null;
        }

        // Bookkeeping, not conversation.
        // A failed attempt is not part of the conversation. The model is not shown that a call
        // it never received was tried and failed -- from where it sits, the call it does receive
        // is the first one. Showing it would invite it to apologise for the engine's weather.
        // A turn ended by a policy likewise shows the model nothing: no call was made and
        // nothing came back, so there is no message in it to carry forward.
        case AgentEvent.ToolApproved _,
            AgentEvent.InferenceAttempted _,
            AgentEvent.TurnFailed _,
            AgentEvent.Terminated _ -> {}
      }
    }
    if (open != null) {
      turns.add(open.stillOpen());
    }
    return List.copyOf(turns);
  }

  /** Which payloads this window needs, in the order it will need them. */
  private static List<PayloadRef> referencedBy(List<AgentEvent> events) {
    List<PayloadRef> refs = new ArrayList<>();
    for (AgentEvent event : events) {
      switch (event) {
        case AgentEvent.TurnStarted started -> refs.add(started.input());
        case AgentEvent.ActionsRequested requested -> refs.add(requested.request());
        case AgentEvent.ToolSucceeded done -> refs.add(done.result());
        case AgentEvent.InferenceAnswered answered -> refs.add(answered.answer());
        case AgentEvent.ToolFailed _,
            AgentEvent.ToolDenied _,
            AgentEvent.ToolApproved _,
            AgentEvent.InferenceRefused _,
            AgentEvent.InferenceFailed _,
            AgentEvent.InferenceAttempted _,
            AgentEvent.TurnFailed _,
            AgentEvent.Terminated _ -> {
          // Nothing behind these but the words already in them.
        }
      }
    }
    return refs;
  }

  private static List<Block> resolve(Map<PayloadRef, Payloads.Resolved> resolved, PayloadRef ref) {
    return switch (resolved.get(ref)) {
      case Payloads.Resolved.Found(List<Block> content) -> content;
      // A reference with nothing behind it is a broken store, not a turn that went badly. Saying
      // so here beats handing a model a turn with a hole where its own words were.
      case null, default -> throw new IllegalStateException("no payload behind " + ref);
    };
  }

  @SuppressWarnings("unchecked")
  private static <T extends Block> List<T> cast(List<Block> blocks) {
    return (List<T>) blocks;
  }

  private static Open require(Open open, AgentEvent event) {
    if (open == null) {
      throw new IllegalStateException(
          event.getClass().getSimpleName() + " with no turn open at " + event.seq());
    }
    return open;
  }

  /** A turn being reassembled, and the exchange within it that is still taking outcomes. */
  private static final class Open {

    private final AgentEvent.TurnStarted started;
    private final Input input;
    private final List<Exchange> exchanges = new ArrayList<>();

    private Seq askedAt;
    private List<Block.ActionRequestContent> request;
    private List<ToolOutcome> outcomes;
    private Map<CallId, String> actions;
    private Map<CallId, String> results;

    private Open(AgentEvent.TurnStarted started, Input input) {
      this.started = started;
      this.input = input;
    }

    static Open on(AgentEvent.TurnStarted started, List<Block> content) {
      return new Open(started, new Input(started.seq(), cast(content)));
    }

    void ask(Seq at, List<Block.ActionRequestContent> blocks, List<ActionRequest> requested) {
      flush();
      askedAt = at;
      request = blocks;
      outcomes = new ArrayList<>();
      actions = new LinkedHashMap<>();
      for (ActionRequest action : requested) {
        switch (action) {
          case ActionRequest.ToolCall call -> actions.put(call.id(), call.action());
        }
      }
      results = new LinkedHashMap<>();
    }

    void succeeded(ToolOutcome.Succeeded outcome, String rendered) {
      outcome(outcome);
      results.put(outcome.callId(), rendered);
    }

    void outcome(ToolOutcome outcome) {
      if (outcomes == null) {
        throw new IllegalStateException(
            "an outcome for " + outcome.callId() + " with nothing asked");
      }
      outcomes.add(outcome);
    }

    Turn closed(TurnResult result) {
      flush();
      return new Turn(started.turn(), input, List.copyOf(exchanges), result, 0);
    }

    Turn stillOpen() {
      flush();
      return new Turn(started.turn(), input, List.copyOf(exchanges), null, 0);
    }

    private void flush() {
      if (request != null) {
        exchanges.add(new Exchange(askedAt, request, List.copyOf(outcomes), actions, results));
        request = null;
        outcomes = null;
        actions = null;
        results = null;
      }
    }
  }
}
