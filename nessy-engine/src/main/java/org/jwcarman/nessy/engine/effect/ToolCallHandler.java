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
package org.jwcarman.nessy.engine.effect;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.tool.ReplyTokens;
import org.jwcarman.nessy.engine.tool.ToolBinding;
import org.jwcarman.nessy.engine.tool.ToolCalls;
import org.jwcarman.nessy.engine.tool.Tools;
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

  /** Where a tool result goes, so the fold is told a reference rather than an answer. */
  private final Payloads payloads;

  private final Tools tools;
  private final ToolCalls calls;
  private final Narrator narrator;
  private final ReplyTokens replyTokens;
  private final EffectTermsSource terms;

  public ToolCallHandler(
      AgentType agentType,
      Tools tools,
      ToolCalls calls,
      ReplyTokens replyTokens,
      Narrator narrator,
      EffectTermsSource terms,
      Payloads payloads) {
    this.agentType = agentType;
    this.payloads = payloads;
    this.tools = tools;
    this.calls = calls;
    this.replyTokens = replyTokens;
    this.narrator = narrator;
    this.terms = terms;
  }

  /**
   * The terms the bound tool was given, falling back to the harness-wide ones.
   *
   * <p>Delegated to {@link EffectTermsSource}, which is what the direct door will ask without
   * building a handler at all. What a call of this tool is worth does not depend on anything this
   * handler holds for the sake of running one.
   */
  @Override
  public EffectTerms termsFor(AgentEffect.CallTool effect) {
    return terms.termsFor(effect);
  }

  @Override
  public Awaited<EffectOutcome> handle(
      AgentId agentId, AgentEffect.CallTool effect, Instant deadline) {
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
      return Awaited.ready(
          new EffectOutcome.ToolFailed(callId, CallFailure.FAILED, "the call could not be found"));
    }
    ToolCalls.ResolvedCall resolved = found.get();
    Block.ToolCall call = resolved.call();

    Optional<ToolBinding<?>> bound = tools.find(call.name());
    if (bound.isEmpty()) {
      // Ordinary, not exceptional: models ask for tools that do not exist, and telling one
      // so is how it picks a different one.
      log.warn("[{}] agent {}: no tool named {}", agentType.value(), agentId.value(), call.name());
      return Awaited.ready(
          new EffectOutcome.ToolFailed(
              callId, CallFailure.FAILED, "there is no tool named '" + call.name().value() + "'"));
    }

    ToolBinding<?> binding = bound.get();
    return outcomeOf(
        agentId,
        callId,
        binding,
        deadline,
        binding.call(
            agentType,
            agentId,
            resolved.turn(),
            callId,
            effect.idempotencyKey(),
            call.name(),
            call.arguments(),
            deadline,
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
      ToolBinding<?> binding,
      Instant until,
      Awaited<ToolResult> awaited) {
    return switch (awaited) {
      case Awaited.Ready<ToolResult>(ToolResult result) ->
          Awaited.ready(
              switch (result) {
                // Put away where it was produced. A tool's result is content; what the fold is
                // told is that the call succeeded, where the result went, and the one bounded
                // line the binding makes of it.
                case ToolResult.Success success ->
                    new EffectOutcome.ToolSucceeded(
                        callId,
                        payloads.forAgent(agentId).put(success.blocks()),
                        binding.rendered(success));
                case ToolResult.Failure(String message) ->
                    new EffectOutcome.ToolFailed(
                        callId,
                        CallFailure.FAILED,
                        Objects.requireNonNullElse(message, "the tool failed and gave no message"));
              });
      case Awaited.Deferred<ToolResult> _ -> {
        // Same reason as a deferred approval: the fold does not learn that anything is
        // waiting, so this is the only place a watcher can.
        narrator.narrate(
            Narrated.live(
                agentType, agentId, new Narration.CallDeferred(callId, binding.name(), until)));
        yield new Awaited.Deferred<>();
      }
    };
  }
}
