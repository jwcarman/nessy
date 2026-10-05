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
import java.time.Instant;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
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

  private static final String COULD_NOT_BE_DESCRIBED =
      "what the call would do could not be described, so it was not put to an approver";

  private final AgentType agentType;
  private final Tools tools;
  private final ToolCalls calls;
  private final Narrator narrator;
  private final ReplyTokens replyTokens;
  private final EffectTermsSource terms;
  private final Clock clock;

  /** Where a question is kept when its answer will come later. */
  private final Payloads payloads;

  public ApprovalHandler(
      AgentType agentType,
      Tools tools,
      ToolCalls calls,
      ReplyTokens replyTokens,
      Narrator narrator,
      EffectTermsSource terms,
      Clock clock,
      Payloads payloads) {
    this.agentType = agentType;
    this.tools = tools;
    this.calls = calls;
    this.replyTokens = replyTokens;
    this.narrator = narrator;
    this.terms = terms;
    this.clock = clock;
    this.payloads = payloads;
  }

  /**
   * The bound tool's approval terms, falling back to the harness-wide ones.
   *
   * <p>Delegated to {@link EffectTermsSource}, which is what the direct door will ask without
   * building a handler at all.
   */
  @Override
  public EffectTerms termsFor(AgentEffect.Approve effect) {
    return terms.termsFor(effect);
  }

  @Override
  public Handled handle(AgentId agentId, AgentEffect.Approve effect, Instant deadline) {
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
      return Handled.settled(
          new EffectOutcome.ToolFailed(
              callId,
              CallFailure.FAILED,
              "there is no tool named '" + effect.toolName().value() + "'"));
    }

    Optional<ToolCalls.ResolvedCall> found = calls.find(agentId, effect.requestSeq(), callId);
    if (found.isEmpty()) {
      log.error(
          "[{}] agent {}: call {} at seq {} is not in the story",
          agentType.value(),
          agentId.value(),
          callId,
          effect.requestSeq());
      return Handled.settled(
          new EffectOutcome.ToolFailed(callId, CallFailure.FAILED, "the call could not be found"));
    }
    ToolCalls.ResolvedCall resolved = found.get();

    ToolBinding<?> binding = bound.get();
    if (binding.couldNotBeSaid(resolved.action())) {
      // The call's stored action says what it would do could not be said. A person asked to
      // consent to a sentence there is none of would be consenting to nothing they could see, and
      // a yes would run the call. Discharged without asking, by the same road as a call whose
      // arguments do not read: a gate that cannot be shown what it gates refuses.
      log.warn(
          "[{}] agent {}: call {} of {} has no description of what it would do",
          agentType.value(),
          agentId.value(),
          callId,
          effect.toolName());
      return Handled.settled(
          new EffectOutcome.ToolFailed(callId, CallFailure.FAILED, COULD_NOT_BE_DESCRIBED));
    }
    ApprovalRequest question;
    try {
      question =
          binding.question(
              agentType,
              agentId,
              resolved.turn(),
              callId,
              effect.idempotencyKey(),
              resolved.call().arguments(),
              resolved.action(),
              clock.instant(),
              deadline,
              replyTokens.mint(agentType, agentId, effect.requestSeq(), callId));
    } catch (RuntimeException e) {
      // A call whose arguments will not read into the tool's input type has no question to
      // ask about it -- and could not run whatever anybody answered. Discharged without asking: a
      // gate exists to stop execution, and there is no execution here to stop. The same road for
      // whatever else building the question threw, an enricher included: the call is not put to an
      // approver, so nothing says yes to it. The exception's message may be null; the text is
      // built, never passed on.
      log.warn(
          "[{}] agent {}: call {} of {} has unreadable arguments",
          agentType.value(),
          agentId.value(),
          callId,
          effect.toolName());
      return Handled.settled(
          new EffectOutcome.ToolFailed(
              callId, CallFailure.FAILED, "the arguments could not be read: " + e.getMessage()));
    }

    // Said before the approver is asked, because a question that never comes back must still
    // have been seen going out. A watcher told only about verdicts would see nothing at all
    // for the calls that matter most.
    narrator.narrate(
        Narrated.live(agentType, agentId, new Narration.ApprovalSought(callId, question.action())));

    return switch (binding.approve(question)) {
      case Awaited.Ready<ApprovalResult>(ApprovalResult result) ->
          Handled.settled(
              switch (result) {
                case ApprovalResult.Approved(var decidedBy) ->
                    new EffectOutcome.ToolApproved(callId, decidedBy);
                case ApprovalResult.Denied(String reason, var decidedBy) -> {
                  log.info(
                      "[{}] agent {}: {} was denied ({})",
                      agentType.value(),
                      agentId.value(),
                      question.action(),
                      reason);
                  yield new EffectOutcome.ToolDenied(callId, reason, decidedBy);
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
        yield new Handled.Deferred(storedQuestion(agentId, callId, question));
      }
    };
  }

  /**
   * What the approver was shown, kept where somebody reading the story later can find it.
   *
   * <p>Failing to keep it must not fail the deferral: the approver has already been asked and may
   * have told a person, so an exception here would be read as a failed ask and the retry policy
   * might ask again. The call stays parked on its row, but its deferral will not be recorded.
   */
  private Optional<PayloadRef> storedQuestion(
      AgentId agentId, CallId callId, ApprovalRequest question) {
    try {
      return Optional.of(
          payloads.forAgent(agentId).putDocument(ApprovalQuestions.document(question)));
    } catch (RuntimeException e) {
      log.warn(
          "[{}] agent {}: the question for call {} could not be stored; the call stays parked on its row, but its deferral will not be recorded",
          agentType.value(),
          agentId.value(),
          callId,
          e);
      return Optional.empty();
    }
  }
}
