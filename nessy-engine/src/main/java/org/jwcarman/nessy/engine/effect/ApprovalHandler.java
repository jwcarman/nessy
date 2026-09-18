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

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
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
 * Asks whether one call may run.
 *
 * <p><b>Asking is not the verdict, and only one of them is worth repeating.</b> A denial is an
 * answer -- the approver was reached and said no -- so nothing about it is retried; asking again
 * until somebody relents is not a retry, it is pestering, and an engine that did it would
 * eventually get the answer it wanted. What can fail and deserve another attempt is the question
 * not arriving: an approval service unreachable, a notification never sent. Those throw, and the
 * binding's approval policy governs them. That separation is the whole reason this is its own
 * effect rather than a step inside the call.
 *
 * <p>Like {@link ToolCallHandler}, every path out of here ends the call's wait -- either by
 * dispatching it or by discharging it. A call left neither approved nor denied is a turn that never
 * closes.
 */
public class ApprovalHandler implements EffectHandler<AgentEffect.Approve> {

  private static final Logger log = LoggerFactory.getLogger(ApprovalHandler.class);

  private final AgentType agentType;
  private final Tools tools;
  private final ToolCalls calls;
  private final Narrator narrator;
  private final ReplyTokens replyTokens;
  private final Duration timeout;
  private final RetryPolicy retryPolicy;
  private final Clock clock;

  public ApprovalHandler(
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
   * The bound tool's approval terms, falling back to the harness-wide ones.
   *
   * <p>Generous by nature where a person is on the other end, and unrelated to what the tool itself
   * is worth waiting for: a build that takes five minutes may be waved through in milliseconds, and
   * a one-second lookup may wait an hour for somebody to read the question.
   */
  @Override
  public EffectTerms termsFor(AgentEffect.Approve effect) {
    return tools
        .find(effect.toolName())
        .<EffectTerms>map(
            binding ->
                new AskingTerms(
                    effect.callId(), binding.approvalTimeout(), binding.approvalRetryPolicy()))
        .orElseGet(() -> new AskingTerms(effect.callId(), timeout, retryPolicy));
  }

  @Override
  public Awaited<EffectOutcome> handle(AgentId agentId, AgentEffect.Approve effect) {
    CallId callId = effect.callId();
    Optional<ToolBinding<?>> bound = tools.find(effect.toolName());
    if (bound.isEmpty()) {
      // Nothing to approve, and nothing that could run if it were approved. Discharged here
      // rather than waved through to fail one hop later, which would run the whole call
      // lifecycle to reach a conclusion already available.
      log.warn(
          "[{}] agent {}: no tool named {} to approve",
          agentType.value(),
          agentId.value(),
          effect.toolName());
      return Awaited.ready(
          new EffectOutcome.ToolFailed(
              callId, "there is no tool named '" + effect.toolName().value() + "'"));
    }

    Optional<ToolCalls.ResolvedCall> found = calls.find(agentId, effect.requestSeq(), callId);
    if (found.isEmpty()) {
      log.error(
          "[{}] agent {}: call {} at seq {} is not in the story",
          agentType.value(),
          agentId.value(),
          callId,
          effect.requestSeq());
      return Awaited.ready(new EffectOutcome.ToolFailed(callId, "the call could not be found"));
    }
    ToolCalls.ResolvedCall resolved = found.get();

    ToolBinding<?> binding = bound.get();
    ApprovalRequest question;
    try {
      question =
          binding.question(
              agentType,
              agentId,
              resolved.turn(),
              callId,
              resolved.call().arguments(),
              clock.instant(),
              replyTokens.mint(agentType, agentId, effect.requestSeq(), callId));
    } catch (RuntimeException e) {
      // The sentence a person consents to is rendered from the tool's own input type, so a
      // call whose arguments will not read has no question to ask about it -- and could not
      // run whatever anybody answered. Discharged without asking: a gate exists to stop
      // execution, and there is no execution here to stop.
      log.warn(
          "[{}] agent {}: call {} of {} has unreadable arguments",
          agentType.value(),
          agentId.value(),
          callId,
          effect.toolName());
      return Awaited.ready(
          new EffectOutcome.ToolFailed(
              callId, "the arguments could not be read: " + e.getMessage()));
    }

    // Said before the approver is asked, because a question that never comes back must still
    // have been seen going out. A watcher told only about verdicts would see nothing at all
    // for the calls that matter most.
    narrator.narrate(agentType, agentId, new AgentEvent.ApprovalSought(callId, question.action()));

    return switch (binding.approve(question)) {
      case Awaited.Ready<ApprovalResult>(ApprovalResult result) ->
          Awaited.ready(
              switch (result) {
                case ApprovalResult.Approved(var reference) ->
                    new EffectOutcome.ToolApproved(callId, reference);
                case ApprovalResult.Denied(String reason, var reference) -> {
                  log.info(
                      "[{}] agent {}: {} was denied ({})",
                      agentType.value(),
                      agentId.value(),
                      question.action(),
                      reason);
                  yield new EffectOutcome.ToolDenied(callId, reason, reference);
                }
              });
      // The ordinary case for a person, not an exotic one. The approver has the reply
      // address and will come back against it; until then the effect row stays where it is,
      // due at its own deadline, and nothing here says anything to the agent. Silence is
      // the only honest answer: read as approval it runs a call somebody was deliberately
      // asked about, read as denial it puts words in an approver's mouth.
      case Awaited.Deferred<ApprovalResult> _ -> {
        log.info(
            "[{}] agent {}: {} is waiting on an answer until {}",
            agentType.value(),
            agentId.value(),
            question.action(),
            question.deadline());
        // The one thing the fold deliberately never learns, said to whoever is watching.
        narrator.narrate(
            agentType,
            agentId,
            new AgentEvent.ApprovalDeferred(callId, question.action(), question.deadline()));
        yield new Awaited.Deferred<>();
      }
    };
  }

  /**
   * One question's terms, and the two failures the call might be discharged with.
   *
   * <p>Both are {@code ToolFailed} rather than {@code ToolDenied}: nobody said no. Claiming a
   * denial when the question never arrived would tell the model it was refused by somebody who
   * never saw it.
   */
  private record AskingTerms(CallId callId, Duration timeout, RetryPolicy retryPolicy)
      implements EffectTerms {

    @Override
    public EffectOutcome undispatchable() {
      return new EffectOutcome.ToolFailed(
          callId, "the call could not be authorised, so it was not run");
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.ToolFailed(
          callId, "the call could not be authorised: " + cause.getMessage());
    }
  }
}
