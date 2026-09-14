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
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.effect.AgentEffectCallback;
import org.jwcarman.nessy.engine.store.Attempt;
import org.jwcarman.nessy.engine.store.EffectStore;
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

  /** One agent type's two halves: where its rows live, and how to reach its fold. */
  public record Bound(EffectStore effects, AgentEffectCallback callback) {}

  private final ReplyTokens tokens;
  private final Map<String, Bound> byAgentType = new ConcurrentHashMap<>();

  public DefaultReplies(ReplyTokens tokens) {
    this.tokens = Objects.requireNonNull(tokens, "tokens must not be null");
  }

  /** Called as each harness is built. An agent type answered before that is simply unknown. */
  public void register(AgentType agentType, EffectStore effects, AgentEffectCallback callback) {
    byAgentType.put(agentType.value(), new Bound(effects, callback));
  }

  @Override
  public ReplyOutcome approve(ReplyToken token, ApprovalResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        token,
        AgentEffect.Approve.class,
        (callId, _) ->
            switch (result) {
              case ApprovalResult.Approved(var reference) ->
                  new EffectOutcome.ToolApproved(callId, reference);
              case ApprovalResult.Denied(String reason, var reference) ->
                  new EffectOutcome.ToolDenied(callId, reason, reference);
            });
  }

  @Override
  public ReplyOutcome complete(ReplyToken token, ToolResult result) {
    Objects.requireNonNull(result, "result must not be null");
    return settle(
        token,
        AgentEffect.CallTool.class,
        (callId, _) ->
            switch (result) {
              case ToolResult.Success(var blocks) ->
                  new EffectOutcome.ToolSucceeded(callId, blocks);
              case ToolResult.Failure(String message) ->
                  new EffectOutcome.ToolFailed(callId, message);
            });
  }

  /**
   * @param expected which kind of effect this answer may settle. A verdict cannot settle a call
   *     that is running, and a result cannot settle one still awaiting permission -- that would run
   *     past the gate rather than through it.
   */
  private ReplyOutcome settle(
      ReplyToken token,
      Class<? extends AgentEffect> expected,
      java.util.function.BiFunction<CallId, Attempt, EffectOutcome> outcome) {
    Objects.requireNonNull(token, "token must not be null");

    ReplyTokens.Coordinates where;
    try {
      where = tokens.read(token);
    } catch (IllegalArgumentException e) {
      // Forged, edited, or minted under a key since dropped. Nothing distinguishes those
      // from here, and nothing should: all three mean this is not an address we honour.
      log.info("a reply arrived on an address this engine did not issue");
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
    Optional<Attempt> found = find(bound, agentId, where, expected);
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

    Attempt attempt = found.get();
    bound.callback().deliverOutcome(agentId, outcome.apply(where.callId(), attempt));
    if (!bound.effects().complete(attempt.effectId(), attempt.attemptsMade())) {
      // The fence: the row moved on while this answer was being folded, so it is not ours
      // to retire. Harmless -- the call is discharged either way, and whatever holds the row
      // now will find the fold already ignores what it delivers.
      log.info(
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
  private Optional<Attempt> find(
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
        return Optional.of(attempt);
      }
    }
    return Optional.empty();
  }

  /** Whether an effect is for the call these coordinates name. */
  private static boolean names(AgentEffect effect, ReplyTokens.Coordinates where) {
    return switch (effect) {
      case AgentEffect.Approve(Seq requestSeq, CallId callId, ToolName _) ->
          requestSeq.equals(where.requestSeq()) && callId.equals(where.callId());
      case AgentEffect.CallTool(Seq requestSeq, CallId callId, ToolName _) ->
          requestSeq.equals(where.requestSeq()) && callId.equals(where.callId());
      // Nothing else can be deferred, so nothing else can be answered late.
      case AgentEffect.Infer _ -> false;
    };
  }
}
