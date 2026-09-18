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
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.RetryPolicy;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.inference.InferenceInvocation;
import org.jwcarman.nessy.engine.inference.InferenceService;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns one inference into something the fold can read.
 *
 * <p>That is now the whole job. Reading the story, choosing what to send and talking to a provider
 * all moved behind {@link InferenceService}, so this holds one collaborator instead of three and
 * the translation is the only thing left to get wrong.
 *
 * <p><b>No {@code try}.</b> {@link InferenceService} is total for expected conditions, so a failed
 * call arrives as {@link InferenceResult.Fault} like any other arm. That matters more than it
 * looks: an agent mid-turn is waiting on this call and only something reaching the fold ends the
 * turn, so an escaping exception would leave it waiting on an answer nothing will ever bring. What
 * used to be a catch block one frame from the throw is now a case label the compiler checks.
 */
public class InferenceHandler implements EffectHandler<AgentEffect.Infer>, EffectTerms {

  private static final Logger log = LoggerFactory.getLogger(InferenceHandler.class);

  private final AgentType agentType;
  private final InferenceService inference;
  private final InferenceOptions options;
  private final Duration timeout;
  private final RetryPolicy retryPolicy;

  public InferenceHandler(
      AgentType agentType,
      InferenceService inference,
      InferenceOptions options,
      Duration timeout,
      RetryPolicy retryPolicy) {
    this.agentType = agentType;
    this.inference = inference;
    this.options = options;
    this.timeout = timeout;
    this.retryPolicy = retryPolicy;
  }

  /** Uniform: one agent type calls one model on one set of terms. */
  @Override
  public EffectTerms termsFor(AgentEffect.Infer effect) {
    return this;
  }

  @Override
  public Duration timeout() {
    return timeout;
  }

  /**
   * Widened by most applications. A provider's 503 is the canonical retryable failure, and an
   * inference that never reached one changed nothing by being repeated.
   */
  @Override
  public RetryPolicy retryPolicy() {
    return retryPolicy;
  }

  /**
   * Unknown, and honestly so. An inference that failed on its own terms never reaches here --
   * {@link InferenceService} is total for those and they arrive as {@link InferenceResult.Fault}
   * below. What lands here is anything else that threw: a fold that would not commit, a bug in this
   * class. Nobody found out whether the call happened.
   */
  @Override
  public EffectOutcome failed(RuntimeException cause) {
    return new EffectOutcome.InferenceFailed(
        new Failure.Unknown(String.valueOf(cause.getMessage())));
  }

  @Override
  public EffectOutcome undispatchable() {
    return new EffectOutcome.InferenceFailed(
        new Failure.Permanent("the inference could not be dispatched"));
  }

  @Override
  public Awaited<EffectOutcome> handle(AgentId agentId, AgentEffect.Infer effect) {
    InferenceResult result = inference.infer(new InferenceInvocation(agentType, agentId, options));
    // Always ready. A provider call blocks until it answers or fails, and there is nobody who
    // could come back about it afterwards -- so the one thing this cannot return is the one
    // thing the wrapper makes explicit.
    return Awaited.ready(
        switch (result) {
          case InferenceResult.Answer(var blocks, _) -> {
            log.debug("model answered agent {} with {} block(s)", agentId.value(), blocks.size());
            yield new EffectOutcome.InferenceAnswered(blocks);
          }
          case InferenceResult.Refusal(var category, _) -> {
            log.info("model declined for agent {} ({})", agentId.value(), category);
            yield new EffectOutcome.InferenceRefused(category);
          }
          case InferenceResult.Actions(var blocks, _) -> {
            log.debug("model asked agent {} for {} action(s)", agentId.value(), blocks.size());
            yield new EffectOutcome.InferenceRequestedActions(blocks);
          }
          case InferenceResult.Fault(var failure, _) -> {
            log.warn("inference failed for agent {}: {}", agentId.value(), failure.reason());
            yield new EffectOutcome.InferenceFailed(failure);
          }
        });
  }
}
