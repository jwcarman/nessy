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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.engine.tool.Tools;
import org.jwcarman.nessy.inference.Failure;

/**
 * What every kind of effect is worth, resolved from {@link Tools} and the harness-wide defaults
 * alone.
 *
 * <p>Pulled out of the three handlers because asking what an effect is worth and performing it are
 * different questions with different collaborators. Performing a tool call needs {@code ToolCalls},
 * a reply address to mint and somewhere to put the result; asking what it is worth needs only the
 * binding, if there is one, and a default if there is not. A door that only ever asks -- never
 * performs -- has no way to build the handlers, but it can build this.
 *
 * <p>Held by each handler and delegated to for {@link EffectHandler#termsFor}, so the handlers stay
 * the one place {@link EffectTerms} is asked for while keeping the collaborators the work itself
 * needs.
 *
 * <p>The three {@code termsFor} overloads are public rather than package-private: the direct door
 * lives in {@code engine.harness.direct} and asks this same question of the same code, so the
 * phase-to-timeout mapping stays out of both doors rather than being duplicated into a second
 * resolver.
 */
public final class EffectTermsSource {

  private final Tools tools;
  private final Duration toolTimeout;
  private final RetryPolicy toolRetryPolicy;
  private final Duration approvalTimeout;
  private final RetryPolicy approvalRetryPolicy;
  private final EffectTerms inferenceTerms;

  public EffectTermsSource(
      Tools tools,
      Duration toolTimeout,
      RetryPolicy toolRetryPolicy,
      Duration approvalTimeout,
      RetryPolicy approvalRetryPolicy,
      Duration inferenceTimeout,
      RetryPolicy inferenceRetryPolicy) {
    this.tools = Objects.requireNonNull(tools, "tools must not be null");
    this.toolTimeout = Objects.requireNonNull(toolTimeout, "tool timeout must not be null");
    this.toolRetryPolicy =
        Objects.requireNonNull(toolRetryPolicy, "tool retry policy must not be null");
    this.approvalTimeout =
        Objects.requireNonNull(approvalTimeout, "approval timeout must not be null");
    this.approvalRetryPolicy =
        Objects.requireNonNull(approvalRetryPolicy, "approval retry policy must not be null");
    this.inferenceTerms =
        new InferenceTerms(
            Objects.requireNonNull(inferenceTimeout, "inference timeout must not be null"),
            Objects.requireNonNull(
                inferenceRetryPolicy, "inference retry policy must not be null"));
  }

  /**
   * The terms the bound tool was given, falling back to the harness-wide ones.
   *
   * <p>Per tool rather than per kind, because a lookup and a build are not worth the same wait and
   * an application says which is which. A call for a tool that is not bound still needs terms -- it
   * is about to be discharged with a failure, and that discharge has to be written somewhere -- so
   * the defaults answer for it.
   */
  public EffectTerms termsFor(AgentEffect.CallTool effect) {
    return tools
        .find(effect.toolName())
        .<EffectTerms>map(
            binding -> new CallTerms(effect.callId(), binding.timeout(), binding.retryPolicy()))
        .orElseGet(() -> new CallTerms(effect.callId(), toolTimeout, toolRetryPolicy));
  }

  /**
   * The bound tool's approval terms, falling back to the harness-wide ones.
   *
   * <p>Generous by nature where a person is on the other end, and unrelated to what the tool itself
   * is worth waiting for: a build that takes five minutes may be waved through in milliseconds, and
   * a one-second lookup may wait an hour for somebody to read the question.
   */
  public EffectTerms termsFor(AgentEffect.Approve effect) {
    return tools
        .find(effect.toolName())
        .<EffectTerms>map(
            binding ->
                new AskingTerms(
                    effect.callId(), binding.approvalTimeout(), binding.approvalRetryPolicy()))
        .orElseGet(() -> new AskingTerms(effect.callId(), approvalTimeout, approvalRetryPolicy));
  }

  /** Uniform: one agent type calls one model on one set of terms. */
  public EffectTerms termsFor(AgentEffect.Infer effect) {
    Objects.requireNonNull(effect, "effect must not be null");
    return inferenceTerms;
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
          callId,
          CallFailure.PAST_DEADLINE,
          "the call did not complete before its deadline; whether it ran is not known");
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.ToolFailed(
          callId, CallFailure.FAILED, "the call failed: " + cause.getMessage());
    }
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
          callId,
          CallFailure.NOT_AUTHORISED,
          "the call could not be authorised, so it was not run");
    }

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      return new EffectOutcome.ToolFailed(
          callId,
          CallFailure.NOT_AUTHORISED,
          "the call could not be authorised: " + cause.getMessage());
    }
  }

  /**
   * One inference's terms. Uniform for the agent type, so one instance answers every call.
   *
   * <p>{@code undispatchable()} reports {@link Failure.Unknown}, which is the whole point of the
   * category: a deadline passing says only that no answer arrived in time, never that the model
   * declined to answer or that the call never reached it. The provider may have served the request
   * and the tokens may already be spent. {@code Failure.Permanent} would assert something nobody
   * here is in a position to know.
   *
   * <p>This is the category a recovering caller writes down for an abandoned turn, so it is also
   * what anyone reading the stream later sees for a turn whose process died mid-inference.
   */
  private record InferenceTerms(Duration timeout, RetryPolicy retryPolicy) implements EffectTerms {

    @Override
    public EffectOutcome failed(RuntimeException cause) {
      // Unreported rather than zero, and with no model: this failure is the engine's own account
      // of a call it never heard back from, so there is no vendor's count and nothing to price.
      return new EffectOutcome.InferenceFailed(
          new Failure.Unknown(String.valueOf(cause.getMessage())),
          Usage.unreported(),
          Optional.empty());
    }

    @Override
    public EffectOutcome undispatchable() {
      return new EffectOutcome.InferenceFailed(
          new Failure.Unknown(
              "the inference did not complete before its deadline; whether it ran is not known"),
          Usage.unreported(),
          Optional.empty());
    }
  }
}
