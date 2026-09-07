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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * Where the outside world answers a call that was parked.
 *
 * <p>A tool that defers hands a {@link ReplyToken} to whoever will answer — a vendor's webhook, a
 * person clicking Approve. This is the door that token leads back to. It lives in the engine rather
 * than on {@code Harness} because it is not one of the two things an application does with agents;
 * it is the return path for work an agent asked the world to do.
 *
 * <p><b>It resolves the token to coordinates and routes, holding no state of its own.</b> Nothing
 * is resident: an agent is a row, and routing wakes it by dispatching straight to a {@link
 * Dispatcher}, the same door any other outcome arrives through. That is why the token names logical
 * coordinates rather than an address.
 *
 * <p>Answering returns a stage that completes once the call is confirmed still open and the answer
 * handed to its agent's {@link Dispatcher} — a dispatch is fire-and-forget by design (see {@link
 * Dispatcher#dispatch}), so this is a weaker promise than the ask-pattern this replaced made: it
 * says the answer was accepted for delivery, not that the fold it produces has committed. An answer
 * arriving too late, for a call already settled or expired, is reported honestly rather than
 * silently dropped.
 */
public final class Replies {

  private static final Codec<ToolResult> RESULTS =
      JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class);

  private final Claims claims;
  private final ReplyTokens tokens;
  private final Map<AgentType, Dispatcher> dispatchers = new ConcurrentHashMap<>();
  private final EffectStore effects;

  private final Traces traces;

  Replies(ReplyTokens tokens, Traces traces, Claims claims, EffectStore effects) {
    this.effects = effects;
    this.claims = claims;
    this.tokens = tokens;
    this.traces = traces;
  }

  /** Called by the factory as each kind of agent gains a harness. */
  void serving(AgentType agentType, Dispatcher dispatcher) {
    dispatchers.put(agentType, dispatcher);
  }

  /** The answer a deferring tool promised. */
  public CompletionStage<Ack> answer(ReplyToken token, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    ReplyTokens.Coordinates where = tokens.read(token);
    if (!stillOpen(where)) {
      return CompletableFuture.completedFuture(SETTLED);
    }
    // Claimed BEFORE the agent hears about it, exactly as an in-process tool's result is. A vendor
    // answering on day three of a three-day term goes through the same door as one answering in two
    // milliseconds, which is the whole reason the agent has one message for both.
    claims.put(
        where.agentId(),
        where.turnId(),
        EffectWorker.resultKey(where.callId()),
        RESULTS.encode(result));
    dispatch(where, new Input.ToolCompleted(where.callId()));
    return CompletableFuture.completedFuture(new Ack(true, null));
  }

  /** A person's decision on a call that was waiting for one. */
  public CompletionStage<Ack> approve(ReplyToken token, ApprovalResult result) {
    Objects.requireNonNull(result, "result must not be null");
    ReplyTokens.Coordinates where = tokens.read(token);
    if (!stillOpen(where)) {
      return CompletableFuture.completedFuture(SETTLED);
    }
    // A denial arriving from a desk is claimed here for the same reason an immediate one is: it is
    // the call's RESULT, and the agent is only ever told an id.
    EffectWorker.denialResult(result)
        .ifPresent(
            denied ->
                claims.put(
                    where.agentId(),
                    where.turnId(),
                    EffectWorker.resultKey(where.callId()),
                    RESULTS.encode(denied)));
    String toolName =
        EffectWorker.askedToolName(claims, where.agentId(), where.turnId(), where.callId());
    dispatch(where, new Input.ApprovalGiven(where.callId(), toolName, result));
    return CompletableFuture.completedFuture(new Ack(true, null));
  }

  /**
   * Sends to an agent without waiting for it, routing by type the way an answer does.
   *
   * <p>For senders that are not answering a question and have nobody to tell if the agent is not
   * here — the reminder sweep above all. A type this process does not serve is skipped rather than
   * thrown about: in a cluster the sweep runs everywhere and most nodes will not serve most types,
   * which is ordinary rather than exceptional.
   *
   * @return whether it went anywhere
   */
  boolean tell(AgentType agentType, AgentId agentId, Input input) {
    Dispatcher dispatcher = dispatchers.get(agentType);
    if (dispatcher == null) {
      return false;
    }
    dispatcher.dispatch(agentId, input);
    return true;
  }

  /**
   * Whether that call is still open, WITHOUT waking the agent to ask.
   *
   * <p>The call's own effect row exists for exactly the window in which it can be answered from
   * outside: {@code AskApprover}/{@code RunTool} inserts it, and {@code Transition} deletes it the
   * moment the call settles, whichever route brought the news (see {@code EffectStore
   * #deleteForCall}). Present means open; absent means done.
   *
   * <p><b>Why this is worth a query.</b> Delivering to an agent CREATES a row for it. Without this,
   * an answer clicked minutes late — for a call the deadline already denied, or an agent since
   * forgotten — brings that agent back into being purely so it can refuse the message. A row lookup
   * refuses it without resurrecting anything.
   *
   * <p>Racy in one direction only, and deliberately so. A stale "open" costs a delivery {@code
   * AgentLogic} then drops as answering nothing it was waiting on, which is harmless. A wrong
   * "done" would swallow a real answer — so absence has to be conclusive.
   */
  private boolean stillOpen(ReplyTokens.Coordinates where) {
    return effects.existsForCall(
        where.agentType(), where.agentId(), where.turnId(), where.callId());
  }

  private static final Ack SETTLED = new Ack(false, "that call has already ended");

  private void dispatch(ReplyTokens.Coordinates where, Input input) {
    Dispatcher dispatcher = dispatchers.get(where.agentType());
    if (dispatcher == null) {
      throw new IllegalArgumentException(
          "no agent type \""
              + where.agentType()
              + "\" is served here: the token was issued by a"
              + " harness this process never created");
    }
    Map<String, String> carried =
        traces.capture(where.agentType().name(), where.agentId().value(), "Answer");
    // An answer from outside discharges no obligation of its own -- null names none.
    dispatcher.dispatch(where.agentId(), input, null, EffectWorker.observabilityOf(carried));
  }

  /** Whether an answer actually reached an open call. */
  public record Ack(boolean accepted, String detail) {}
}
