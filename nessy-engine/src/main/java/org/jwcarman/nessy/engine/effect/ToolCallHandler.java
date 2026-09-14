package org.jwcarman.nessy.engine.effect;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.spi.narration.Narrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one call the model asked for, and turns whatever happens into something the fold can read.
 *
 * <p><b>Every path out of here discharges the call.</b> There is no arm that leaves without an
 * outcome, and that is the whole design rather than defensiveness: a call with no result makes the
 * conversation unsendable to any provider, so an agent that loses one is not degraded, it is stuck.
 * An unknown tool, unreadable arguments, a missing entry -- each becomes a failure the model reads
 * and can act on.
 *
 * <p>That is the opposite of {@link InferenceHandler}'s posture, which is free to fail an inference
 * and let the turn end. Nothing is owed there; here, three things might be.
 */
public class ToolCallHandler implements EffectHandler<AgentEffect.CallTool> {

  private static final Logger log = LoggerFactory.getLogger(ToolCallHandler.class);

  private final AgentType agentType;
  private final Tools tools;
  private final ToolCalls calls;
  private final Narrator narrator;
  private final ReplyTokens replyTokens;
  private final Duration timeout;
  private final RetryPolicy retryPolicy;
  private final Clock clock;

  public ToolCallHandler(
      AgentType agentType,
      Tools tools,
      ToolCalls calls,
      ReplyTokens replyTokens,
      Narrator narrator,
      Duration timeout,
      RetryPolicy retryPolicy,
      Clock clock) {
    this.agentType = agentType;
    this.tools = tools;
    this.calls = calls;
    this.replyTokens = replyTokens;
    this.narrator = narrator;
    this.timeout = timeout;
    this.retryPolicy = retryPolicy;
    this.clock = clock;
  }

  /**
   * The terms the bound tool was given, falling back to the harness-wide ones.
   *
   * <p>Per tool rather than per kind, because a lookup and a build are not worth the same wait and
   * an application says which is which. A call for a tool that is not bound still needs terms -- it
   * is about to be discharged with a failure, and that discharge has to be written somewhere -- so
   * the defaults answer for it.
   */
  @Override
  public EffectTerms termsFor(AgentEffect.CallTool effect) {
    return tools
        .find(effect.toolName())
        .<EffectTerms>map(
            binding -> new CallTerms(effect.callId(), binding.timeout(), binding.retryPolicy()))
        .orElseGet(() -> new CallTerms(effect.callId(), timeout, retryPolicy));
  }

  /**
   * One call's terms, and the two failures it might be discharged with.
   *
   * <p>The call id is here so those failures can name it. An outcome that could not say which call
   * it answers discharges nothing: the fold matches on the id, so an anonymous failure leaves the
   * call outstanding and the turn unable to close -- the exact state this handler exists to make
   * impossible.
   */
  private record CallTerms(CallId callId, Duration timeout, RetryPolicy retryPolicy)
      implements EffectTerms {

    /**
     * What the agent is told when the deadline arrives and nothing else has been said.
     *
     * <p><b>It does not claim the tool did not run</b>, and that is the whole of the wording. This
     * one blob covers two situations the row cannot tell apart, because nothing about a deferral is
     * recorded: a call whose deadline passed while it was still queued, which genuinely never ran,
     * and a call that ran, deferred, and was never reported back on -- which may have charged a
     * card or started a rebuild. Saying "was not run" is right for the first and dangerously wrong
     * for the second, and wrong in the direction that invites a model to do it again.
     *
     * <p>So it says only what is known in both: time ran out, and what happened is not known. A
     * model can act on that -- check, ask, or choose something else -- where it cannot safely act
     * on a false reassurance.
     */
    @Override
    public EffectOutcome undispatchable() {
      return new EffectOutcome.ToolFailed(
          callId, "the call did not complete before its deadline; whether it ran is not known");
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.ToolFailed(callId, "the call failed: " + cause.getMessage());
    }
  }

  @Override
  public Awaited<EffectOutcome> handle(AgentId agentId, AgentEffect.CallTool effect) {
    CallId callId = effect.callId();
    Optional<ToolCalls.ResolvedCall> found = calls.find(agentId, effect.requestSeq(), callId);
    if (found.isEmpty()) {
      // The effect row and the story disagree, which no attempt can repair. The obligation
      // is still real, so it is discharged with the truth rather than held.
      log.error(
          "[{}] agent {}: call {} at seq {} is not in the story",
          agentType.value(),
          agentId.value(),
          callId,
          effect.requestSeq());
      return Awaited.ready(new EffectOutcome.ToolFailed(callId, "the call could not be found"));
    }
    Block.ToolCall call = found.get().call();

    Optional<ToolBinding<?>> bound = tools.find(call.name());
    if (bound.isEmpty()) {
      // Ordinary, not exceptional: models ask for tools that do not exist, and telling one
      // so is how it picks a different one.
      log.info("[{}] agent {}: no tool named {}", agentType.value(), agentId.value(), call.name());
      return Awaited.ready(
          new EffectOutcome.ToolFailed(
              callId, "there is no tool named '" + call.name().value() + "'"));
    }

    ToolBinding<?> binding = bound.get();
    Instant until = clock.instant().plus(binding.timeout());
    return outcomeOf(
        agentId,
        callId,
        call.name(),
        until,
        binding.call(
            call.arguments(),
            until,
            replyTokens.mint(agentType, agentId, effect.requestSeq(), callId)));
  }

  /**
   * A tool's answer, as the dispatcher reads it.
   *
   * <p>A deferral here means the same thing it means for an approval: the work is genuinely
   * elsewhere and will be answered against the reply address. The effect row stays, due at its own
   * deadline, and if nobody answers by then the stored failure discharges the call.
   */
  private Awaited<EffectOutcome> outcomeOf(
      AgentId agentId,
      CallId callId,
      ToolName toolName,
      Instant until,
      Awaited<ToolResult> awaited) {
    return switch (awaited) {
      case Awaited.Ready<ToolResult>(ToolResult result) ->
          Awaited.ready(
              switch (result) {
                case ToolResult.Success(var blocks) ->
                    new EffectOutcome.ToolSucceeded(callId, blocks);
                case ToolResult.Failure(String message) ->
                    new EffectOutcome.ToolFailed(callId, message);
              });
      case Awaited.Deferred<ToolResult> _ -> {
        // Same reason as a deferred approval: the fold does not learn that anything is
        // waiting, so this is the only place a watcher can.
        narrator.narrate(agentType, agentId, new AgentEvent.CallDeferred(callId, toolName, until));
        yield new Awaited.Deferred<>();
      }
    };
  }
}
