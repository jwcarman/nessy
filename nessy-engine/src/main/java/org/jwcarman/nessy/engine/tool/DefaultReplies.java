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
package org.jwcarman.nessy.engine.tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.effect.EffectOutcomes;
import org.jwcarman.nessy.engine.store.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a late answer into the settling of the call that was waiting for it.
 *
 * <p>Four steps, and the order of the last two is the only subtle thing here: read the token, find
 * the row it names, <b>tell the agent</b>, and only then let go of the row. Delivering before
 * settling is what every other path in the engine already does, and for the same reason -- if the
 * fold commits and this crashes, the row survives, comes due at its deadline, and the fold ignores
 * the redelivery because the call is already discharged. The other order loses the answer.
 *
 * <p>Registered per agent type as harnesses are built, because a token names an agent type and a
 * caller holding one cannot read it.
 */
public final class DefaultReplies implements Replies {

  private static final Logger log = LoggerFactory.getLogger(DefaultReplies.class);

  /**
   * One agent type's halves: where its rows live, how to reach its fold, and the tools it bound,
   * which a late result is put into words with.
   */
  public record Bound(
      Outbox effects, AgentEffectCallback callback, Payloads payloads, Tools tools) {}

  /**
   * How a late answer becomes an outcome.
   *
   * <p>Takes the store because a reply that carries content -- a tool result from a desk answering
   * hours later -- has to put it away before saying so, exactly as the executor would have done had
   * it answered on the spot. An outcome carries no content, whenever it arrives: a reference to it,
   * and the one bounded line a successful result leaves. It is given the effect it settles because
   * that names the tool, and the tool's binding is what says in a line what the result was.
   */
  @FunctionalInterface
  private interface Settlement {
    EffectOutcome of(CallId callId, AgentEffect effect, Tools tools, Payloads payloads);
  }

  private final ReplyTokens tokens;
  private final Map<String, Bound> byAgentType = new ConcurrentHashMap<>();

  public DefaultReplies(ReplyTokens tokens) {
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
  }

  /** Called as each harness is built. An agent type answered before that is simply unknown. */
  public void register(
      AgentType agentType,
      Outbox effects,
      AgentEffectCallback callback,
      Payloads payloads,
      Tools tools) {
    byAgentType.put(agentType.value(), new Bound(effects, callback, payloads, tools));
  }

  @Override
  public ReplyOutcome approve(ReplyToken token, ApprovalResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        token,
        AgentEffect.Approve.class,
        (callId, _, _, _) ->
            switch (result) {
              case ApprovalResult.Approved(var decidedBy) ->
                  new EffectOutcome.ToolApproved(callId, decidedBy);
              case ApprovalResult.Denied(String reason, var decidedBy) ->
                  new EffectOutcome.ToolDenied(callId, reason, decidedBy);
            });
  }

  @Override
  public ReplyOutcome complete(ReplyToken token, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        token,
        AgentEffect.CallTool.class,
        (callId, effect, tools, payloads) ->
            switch (result) {
              case ToolResult.Success success ->
                  new EffectOutcome.ToolSucceeded(
                      callId, payloads.put(success.blocks()), rendered(tools, effect, success));
              case ToolResult.Failure(String message) ->
                  new EffectOutcome.ToolFailed(
                      callId,
                      Objects.requireNonNullElse(message, "the tool failed and gave no message"));
            });
  }

  /**
   * What the result says, as its binding would have said it had the tool answered on the spot.
   *
   * <p>A tool that is no longer bound -- the configuration changed while it worked -- is said the
   * way an unconfigured binding says it, so the call still gets a line and the answer is not lost.
   */
  private static String rendered(Tools tools, AgentEffect effect, ToolResult.Success success) {
    if (effect instanceof AgentEffect.CallTool call) {
      Optional<ToolBinding<?>> binding = tools.find(call.toolName());
      if (binding.isPresent()) {
        return binding.get().rendered(success);
      }
    }
    return SettledLines.result(Optional.empty()).stringify(success);
  }

  /**
   * @param expected which kind of effect this answer may settle. A verdict cannot settle a call
   *     that is running, and a result cannot settle one still awaiting permission -- that would run
   *     past the gate rather than through it.
   */
  private ReplyOutcome settle(
      ReplyToken token, Class<? extends AgentEffect> expected, Settlement outcome) {
    Objects.requireNonNull(token, "token must not be null");

    ReplyTokens.Coordinates where;
    try {
      where = tokens.read(token);
    } catch (IllegalArgumentException _) {
      // Forged, edited, or minted under a key since dropped. Nothing distinguishes those
      // from here, and nothing should: all three mean this is not an address we honour.
      log.warn("a reply arrived on an address this engine did not issue");
      return new ReplyOutcome.Unreadable();
    }

    Bound bound = byAgentType.get(where.agentType());
    if (bound == null) {
      // Authentic, but for an agent type this process does not serve -- a token minted
      // before a rename, or a deployment that no longer builds that harness.
      log.warn(
          "a reply arrived for agent type {}, which is not configured here", where.agentType());
      return new ReplyOutcome.NotAwaiting();
    }

    AgentId agentId = new AgentId(where.agentId());
    Optional<Awaiting> found = find(bound, agentId, where, expected);
    if (found.isEmpty()) {
      // Answered already, expired at its deadline, or settled a moment sooner by something
      // else. One answer for a caller, because the three are the same news.
      log.info(
          "[{}] a reply for call {} of agent {} found nothing awaiting it",
          where.agentType(),
          where.callId(),
          agentId.value());
      return new ReplyOutcome.NotAwaiting();
    }

    Attempt attempt = found.get().attempt();
    bound
        .callback()
        .deliverOutcome(
            agentId,
            // The turn the row itself named when it was written, so an answer that arrives after
            // its turn has closed settles nothing rather than settling the current one.
            Optional.of(found.get().effect().turn()),
            // Likewise the request, which the token named and the row confirmed: a call id can
            // repeat across requests of one turn, and the turn alone would not tell them apart.
            EffectOutcomes.requestOf(found.get().effect()),
            outcome.of(
                where.callId(),
                found.get().effect(),
                bound.tools(),
                bound.payloads().forAgent(agentId)),
            attempt.traceContext(),
            // Nothing to carry. Only a model call keeps what a failed attempt learned -- a tool
            // knows it failed and nothing else -- and this is always a tool's answer.
            List.of());
    if (!bound.effects().complete(attempt.effectId(), attempt.attemptsMade())) {
      // The fence: the row moved on while this answer was being folded, so it is not ours
      // to retire. Harmless -- the call is discharged either way, and whatever holds the row
      // now will find the fold already ignores what it delivers.
      log.debug(
          "[{}] effect {} was taken over while its answer was being delivered",
          where.agentType(),
          attempt.effectId());
    }
    return new ReplyOutcome.Settled();
  }

  /**
   * The row holding this call, if one still is.
   *
   * <p>Read and matched rather than queried by id, because a token names a call and the engine
   * keeps no index from calls to rows. The set is one agent's claimed effects -- a handful at the
   * very most -- and an unreadable payload is simply not a match: a row nobody can decode is one
   * its own deadline will deal with.
   */
  private Optional<Awaiting> find(
      Bound bound,
      AgentId agentId,
      ReplyTokens.Coordinates where,
      Class<? extends AgentEffect> expected) {
    List<Attempt> running = bound.effects().runningFor(agentId);
    for (Attempt attempt : running) {
      AgentEffect effect;
      try {
        effect = bound.effects().effectOf(attempt);
      } catch (RuntimeException _) {
        continue;
      }
      if (expected.isInstance(effect) && names(effect, where)) {
        return Optional.of(new Awaiting(attempt, effect));
      }
    }
    return Optional.empty();
  }

  /**
   * The row this answer settles, and what it was decoded to say.
   *
   * <p>Both, because the row carries the trace and the fence while only the decoded effect carries
   * the turn -- and decoding it twice to get the second would be a second chance to disagree.
   */
  private record Awaiting(Attempt attempt, AgentEffect effect) {}

  /** Whether an effect is for the call these coordinates name. */
  private static boolean names(AgentEffect effect, ReplyTokens.Coordinates where) {
    return switch (effect) {
      case AgentEffect.Approve(_, Seq requestSeq, CallId callId, _, _) ->
          requestSeq.equals(where.requestSeq()) && callId.equals(where.callId());
      case AgentEffect.CallTool(_, Seq requestSeq, CallId callId, _, _) ->
          requestSeq.equals(where.requestSeq()) && callId.equals(where.callId());
      // Nothing else can be deferred, so nothing else can be answered late.
      case AgentEffect.Infer _ -> false;
    };
  }
}
