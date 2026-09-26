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

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.Narrator;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.engine.agent.AgentEffect;
import org.jwcarman.nessy.engine.agent.EffectOutcome;
import org.jwcarman.nessy.engine.inference.InferenceInvocation;
import org.jwcarman.nessy.engine.inference.InferenceService;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.block.Block;
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
public class InferenceHandler implements EffectHandler<AgentEffect.Infer> {

  private static final Logger log = LoggerFactory.getLogger(InferenceHandler.class);

  private final AgentType agentType;
  private final InferenceService inference;
  private final InferenceOptions options;
  private final EffectTermsSource terms;

  /** Where what the model said goes, so that what reaches the fold is a reference to it. */
  private final Payloads payloads;

  private final Narrator narrator;

  public InferenceHandler(
      AgentType agentType,
      InferenceService inference,
      InferenceOptions options,
      EffectTermsSource terms,
      Payloads payloads,
      Narrator narrator) {
    this.agentType = agentType;
    this.inference = inference;
    this.options = options;
    this.terms = terms;
    this.payloads = payloads;
    this.narrator = Objects.requireNonNull(narrator, "narrator must not be null");
  }

  /**
   * Uniform: one agent type calls one model on one set of terms.
   *
   * <p>Delegated to {@link EffectTermsSource}, which is what the direct door will ask without
   * building a handler at all.
   */
  @Override
  public EffectTerms termsFor(AgentEffect.Infer effect) {
    return terms.termsFor(effect);
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
            // Put away here, where the content exists and there is somewhere to put it. What
            // reaches the fold is where it went.
            yield new EffectOutcome.InferenceAnswered(payloads.forAgent(agentId).put(blocks));
          }
          case InferenceResult.Refusal(var category, _) -> {
            log.info("model declined for agent {} ({})", agentId.value(), category);
            yield new EffectOutcome.InferenceRefused(category);
          }
          case InferenceResult.Actions(var blocks, _) -> {
            log.debug("model asked agent {} for {} action(s)", agentId.value(), blocks.size());
            // The calls come out beside the reference: which calls are outstanding is the one
            // thing about a request the fold cannot take on trust from a claim check.
            commentary(agentId, blocks);
            yield new EffectOutcome.InferenceRequestedActions(
                payloads.forAgent(agentId).put(blocks), requested(blocks));
          }
          case InferenceResult.Fault(var failure, _) -> {
            log.warn("inference failed for agent {}: {}", agentId.value(), failure.reason());
            yield new EffectOutcome.InferenceFailed(failure);
          }
        });
  }

  /**
   * What the model said while deciding to act, announced where the words still are.
   *
   * <p>Here rather than from the event, which by then holds a reference: narrating from that would
   * read back content this method is holding. The same reason its two sibling handlers announce
   * what they are doing rather than leaving it to be reconstructed.
   */
  private void commentary(AgentId agentId, List<Block.ActionRequestContent> blocks) {
    blocks.stream()
        .filter(Block.Commentary.class::isInstance)
        .map(Block.Commentary.class::cast)
        .forEach(
            said -> narrator.narrate(agentType, agentId, new Narration.Commentary(said.text())));
  }

  /** Which calls a request obliges an outcome for, in the order the model made them. */
  private static List<ActionRequest> requested(List<Block.ActionRequestContent> blocks) {
    return blocks.stream()
        .filter(Block.ToolCall.class::isInstance)
        .map(Block.ToolCall.class::cast)
        .map(call -> (ActionRequest) new ActionRequest.ToolCall(call.id(), call.name()))
        .toList();
  }
}
