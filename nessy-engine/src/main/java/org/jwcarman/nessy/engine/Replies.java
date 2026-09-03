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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * Where the outside world answers a call that was parked.
 *
 * <p>A tool that defers hands a {@link ReplyToken} to whoever will answer — a vendor's webhook, a
 * person clicking Approve. This is the door that token leads back to. It lives in the engine rather
 * than on {@code Harness} because it is not one of the two things an application does with agents;
 * it is the return path for work an agent asked the world to do.
 *
 * <p><b>It resolves the token to coordinates and routes, holding no state of its own.</b> The
 * actors that were waiting need not still exist: routing wakes the agent, which respawns its turn
 * from the record, which respawns the call. That is why the token names logical coordinates rather
 * than an address.
 *
 * <p>Answering returns a stage that completes when the answer has actually REACHED the call, not
 * when it was sent — so an HTTP handler can wait before returning 200. An answer arriving too late,
 * for a call already settled or expired, is reported honestly rather than silently dropped.
 */
public final class Replies {

  private static final Codec<ToolResult> RESULTS =
      JsonCodec.of(EngineMapper.INSTANCE, ToolResult.class);

  private final Claims claims;
  private final ActorSystem<?> system;
  private final Duration patience;
  private final ReplyTokens tokens;
  private final Map<AgentType, EntityTypeKey<NessyMessage>> agentTypes = new ConcurrentHashMap<>();
  private final Reminders reminders;

  private final Traces traces;

  Replies(
      ActorSystem<?> system,
      Duration patience,
      ReplyTokens tokens,
      Traces traces,
      Claims claims,
      Reminders reminders) {
    this.reminders = reminders;
    this.claims = claims;
    this.system = system;
    this.patience = patience;
    this.tokens = tokens;
    this.traces = traces;
  }

  /** Called by the factory as each kind of agent gains a harness. */
  void serving(AgentType agentType, EntityTypeKey<NessyMessage> key) {
    agentTypes.put(agentType, key);
  }

  /** The answer a deferring tool promised. */
  public CompletionStage<NessyMessage.Ack> answer(ReplyToken token, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    ReplyTokens.Coordinates where = tokens.read(token);
    if (!stillOpen(where)) {
      return java.util.concurrent.CompletableFuture.completedFuture(SETTLED);
    }
    // Claimed BEFORE the agent hears about it, exactly as an in-process tool's result is. A vendor
    // answering on day three of a three-day term goes through the same door as one answering in two
    // milliseconds, which is the whole reason the agent has one message for both.
    claims.put(
        where.agentId(),
        where.turnId(),
        Instructions.resultKey(where.callId()),
        RESULTS.encode(result));
    return ask(
        where,
        replyTo ->
            new NessyMessage.ToolAnswered(
                where.callId(),
                replyTo,
                traces.capture(where.agentType().name(), where.agentId().value(), "Answer")));
  }

  /** A person's decision on a call that was waiting for one. */
  public CompletionStage<NessyMessage.Ack> approve(ReplyToken token, ApprovalResult result) {
    Objects.requireNonNull(result, "result must not be null");
    ReplyTokens.Coordinates where = tokens.read(token);
    if (!stillOpen(where)) {
      return java.util.concurrent.CompletableFuture.completedFuture(SETTLED);
    }
    // A denial arriving from a desk is claimed here for the same reason an immediate one is: it is
    // the call's RESULT, and the agent is only ever told an id.
    Instructions.denialResult(result)
        .ifPresent(
            denied ->
                claims.put(
                    where.agentId(),
                    where.turnId(),
                    Instructions.resultKey(where.callId()),
                    RESULTS.encode(denied)));
    return ask(
        where,
        replyTo ->
            new NessyMessage.ApprovalAnswered(
                where.callId(),
                result,
                replyTo,
                traces.capture(where.agentType().name(), where.agentId().value(), "Answer")));
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
  boolean tell(AgentType agentType, AgentId agentId, NessyMessage message) {
    EntityTypeKey<NessyMessage> key = agentTypes.get(agentType);
    if (key == null) {
      return false;
    }
    ClusterSharding.get(system).entityRefFor(key, agentId.value()).tell(message);
    return true;
  }

  /**
   * Whether that call is still open, WITHOUT waking the agent to ask.
   *
   * <p>A reminder exists for exactly the window in which a call can be answered from outside: it is
   * armed in one place (a parked call) and cancelled in one place (that call settling), and the
   * sweep re-arms rather than deletes, so it survives an expiry nobody has settled yet. Present
   * means open; absent means done.
   *
   * <p><b>Why this is worth a query.</b> Delivering to a sharded entity CREATES it. Without this,
   * an answer clicked minutes late — for a call the deadline already denied, or an agent since
   * forgotten — brings that agent back into memory purely so it can refuse the message. A row
   * lookup refuses it without resurrecting anything.
   *
   * <p>Racy in one direction only, and deliberately so. A stale "open" costs a delivery the agent
   * then rejects, which is harmless. A wrong "done" would swallow a real answer — so absence has to
   * be conclusive, and the agent's own Ack remains the authority for everything this lets through.
   */
  private boolean stillOpen(ReplyTokens.Coordinates where) {
    return reminders.find(where.agentType(), where.agentId(), where.callId()).isPresent();
  }

  private static final NessyMessage.Ack SETTLED =
      new NessyMessage.Ack(false, "that call has already ended");

  private CompletionStage<NessyMessage.Ack> ask(
      ReplyTokens.Coordinates where,
      java.util.function.Function<
              org.apache.pekko.actor.typed.ActorRef<NessyMessage.Ack>, NessyMessage>
          message) {
    EntityTypeKey<NessyMessage> key = agentTypes.get(where.agentType());
    if (key == null) {
      throw new IllegalArgumentException(
          "no agent type \""
              + where.agentType()
              + "\" is served here: the token was issued by a"
              + " harness this process never created");
    }
    return AskPattern.ask(
        ClusterSharding.get(system).entityRefFor(key, where.agentId().value()),
        message::apply,
        patience,
        system.scheduler());
  }
}
