package org.jwcarman.nessy.engine.effect;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;

/**
 * One agent type's handlers, one per kind of effect.
 *
 * <p>Two exhaustive switches over the sealed {@link AgentEffect} rather than a map keyed by class.
 * A map would need an unchecked cast on every dispatch to get from {@code EffectHandler<?>} to the
 * one that takes this effect; a switch narrows the effect for us, so {@link #perform} type-checks
 * with no cast at all. It also means a new kind of effect stops both methods compiling until it has
 * somewhere to go -- which a map would discover at runtime, on a row already claimed.
 *
 * <p>Held by both halves of the engine: {@code EffectStore} asks for terms while writing a row, the
 * dispatcher asks for the work once it has decoded one. Same instance, so the two cannot describe
 * different sets of effects.
 */
public final class EffectHandlers {

  private final EffectHandler<AgentEffect.Infer> inference;
  private final EffectHandler<AgentEffect.Approve> approvals;
  private final EffectHandler<AgentEffect.CallTool> toolCalls;

  public EffectHandlers(
      EffectHandler<AgentEffect.Infer> inference,
      EffectHandler<AgentEffect.Approve> approvals,
      EffectHandler<AgentEffect.CallTool> toolCalls) {
    this.inference = Objects.requireNonNull(inference, "inference handler must not be null");
    this.approvals = Objects.requireNonNull(approvals, "approval handler must not be null");
    this.toolCalls = Objects.requireNonNull(toolCalls, "tool call handler must not be null");
  }

  /**
   * What this effect is worth, for the fold that is writing it down.
   *
   * <p>No type parameter escapes: the caller has an {@link AgentEffect} and wants numbers, not the
   * handler.
   */
  public EffectTerms termsFor(AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Infer infer -> inference.termsFor(infer);
      case AgentEffect.Approve approve -> approvals.termsFor(approve);
      case AgentEffect.CallTool call -> toolCalls.termsFor(call);
    };
  }

  /**
   * Performs it.
   *
   * <p>The switch is what makes this type-safe: it narrows {@code effect} to the kind each handler
   * declares, so the call needs no cast and no wildcard.
   */
  public Awaited<EffectOutcome> perform(AgentId agentId, AgentEffect effect) {
    return switch (effect) {
      case AgentEffect.Infer infer -> inference.handle(agentId, infer);
      case AgentEffect.Approve approve -> approvals.handle(agentId, approve);
      case AgentEffect.CallTool call -> toolCalls.handle(agentId, call);
    };
  }
}
