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

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
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
 * <p>Four steps, and the order of the last two is the only subtle thing here: find the agent type's
 * registration, find the row the key names, <b>tell the agent</b>, and only then let go of the row.
 * Delivering before settling is what every other path in the engine already does, and for the same
 * reason -- if the fold commits and this crashes, the row survives, comes due at its deadline, and
 * the fold ignores the redelivery because the call is already discharged. The other order loses the
 * answer.
 *
 * <p>The answer is {@link ReplyOutcome.Applied} exactly when the fold wrote an event for it, and
 * {@link ReplyOutcome.Ignored} otherwise: no registration, no row, or a fold that took nothing -- a
 * second answer, an answer past the call's deadline, an answer for a turn that has closed. The row
 * is let go of whether or not the fold took the answer, because a row whose call is already
 * discharged has nothing left to wait for.
 *
 * <p>Registered per agent type as harnesses are built, because the caller names an agent type and
 * holds no harness.
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

  private final Map<String, Bound> byAgentType = new ConcurrentHashMap<>();
  private final Clock clock;

  /**
   * @param clock what says whether a row's deadline has passed. A row whose deadline is not after
   *     now is not waiting for an answer any more, whether or not the dispatcher has folded its
   *     expiry yet.
   */
  public DefaultReplies(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
  public ReplyOutcome approve(
      AgentType type, AgentId id, IdempotencyKey key, ApprovalResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        type,
        id,
        key,
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
  public ReplyOutcome complete(AgentType type, AgentId id, IdempotencyKey key, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        type,
        id,
        key,
        AgentEffect.CallTool.class,
        (callId, effect, tools, payloads) ->
            switch (result) {
              case ToolResult.Success success ->
                  new EffectOutcome.ToolSucceeded(
                      callId, payloads.put(success.blocks()), rendered(tools, effect, success));
              case ToolResult.Failure(String message) ->
                  new EffectOutcome.ToolFailed(
                      callId,
                      CallFailure.FAILED,
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
      AgentType type,
      AgentId agentId,
      IdempotencyKey key,
      Class<? extends AgentEffect> expected,
      Settlement outcome) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(agentId, "id must not be null");
    Objects.requireNonNull(key, "key must not be null");

    Bound bound = byAgentType.get(type.value());
    if (bound == null) {
      // For an agent type this process does not serve -- a rename, or a deployment that no
      // longer builds that harness.
      log.warn("a reply arrived for agent type {}, which is not configured here", type.value());
      return new ReplyOutcome.Ignored();
    }

    Optional<Awaiting> found = find(type, bound, agentId, key, expected);
    if (found.isEmpty()) {
      // Nothing awaits this answer: the call was answered already, its deadline passed, the key
      // is not one this engine knows, or something else settled it a moment sooner. A caller is
      // told the same thing in each case, because it is the same news.
      log.info(
          "[{}] a reply for key {} of agent {} found nothing awaiting it",
          type.value(),
          key.value(),
          agentId.value());
      return new ReplyOutcome.Ignored();
    }

    Attempt attempt = found.get().attempt();
    AgentEffect effect = found.get().effect();
    CallId callId = callOf(effect);
    boolean applied =
        bound
            .callback()
            .deliverOutcome(
                agentId,
                // The turn the row itself named when it was written, so an answer that arrives
                // after its turn has closed settles nothing rather than settling the current one.
                Optional.of(effect.turn()),
                // Likewise the request, which the row names: a call id can repeat across requests
                // of one turn, and the turn alone would not tell them apart.
                EffectOutcomes.requestOf(effect),
                outcome.of(callId, effect, bound.tools(), bound.payloads().forAgent(agentId)),
                attempt.traceContext(),
                // Nothing to carry. Only a model call keeps what a failed attempt learned -- a
                // tool knows it failed and nothing else -- and this is always a tool's answer.
                List.of());
    if (!bound.effects().complete(attempt.effectId(), attempt.attemptsMade())) {
      // The fence: the row moved on while this answer was being folded, so it is not ours
      // to retire. Harmless -- the call is discharged either way, and whatever holds the row
      // now will find the fold already ignores what it delivers.
      log.debug(
          "[{}] effect {} was taken over while its answer was being delivered",
          type.value(),
          attempt.effectId());
    }
    return applied ? new ReplyOutcome.Applied() : new ReplyOutcome.Ignored();
  }

  /**
   * The row holding the call this key names, if one still is.
   *
   * <p>Read and matched rather than queried by key, because the engine keeps no index from keys to
   * rows. The set is one agent's claimed effects -- a handful at the very most -- and an unreadable
   * payload is logged and is not a match: a row nobody can decode is one its own deadline will deal
   * with. So is a row whose deadline is not after now: the answer is ignored and the row is left as
   * it is for the dispatcher to expire.
   */
  private Optional<Awaiting> find(
      AgentType type,
      Bound bound,
      AgentId agentId,
      IdempotencyKey key,
      Class<? extends AgentEffect> expected) {
    List<Attempt> running = bound.effects().runningFor(agentId);
    Instant now = clock.instant();
    for (Attempt attempt : running) {
      AgentEffect effect;
      try {
        effect = bound.effects().effectOf(attempt);
      } catch (RuntimeException e) {
        log.warn(
            "[{}] effect {} of agent {} could not be decoded and is not a match for a reply",
            type.value(),
            attempt.effectId(),
            agentId.value(),
            e);
        continue;
      }
      if (expected.isInstance(effect) && names(effect, key)) {
        if (!attempt.deadline().isAfter(now)) {
          // Past its deadline, the call is the dispatcher's to expire: the answer lost the race,
          // and the row is left exactly as it is.
          log.info(
              "[{}] a reply for key {} of agent {} arrived after the call's deadline",
              type.value(),
              key.value(),
              agentId.value());
          return Optional.empty();
        }
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

  /** Whether an effect is for the call this key names. */
  private static boolean names(AgentEffect effect, IdempotencyKey key) {
    return switch (effect) {
      case AgentEffect.Approve(_, _, _, _, IdempotencyKey own) -> own.equals(key);
      case AgentEffect.CallTool(_, _, _, _, IdempotencyKey own) -> own.equals(key);
      // Nothing else can be deferred, so nothing else can be answered late.
      case AgentEffect.Infer _ -> false;
    };
  }

  /** The call an effect that can be answered late is for. */
  private static CallId callOf(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Approve(_, _, CallId callId, _, _) -> callId;
      case AgentEffect.CallTool(_, _, CallId callId, _, _) -> callId;
      case AgentEffect.Infer _ ->
          throw new IllegalStateException("an inference is never answered late");
    };
  }
}
