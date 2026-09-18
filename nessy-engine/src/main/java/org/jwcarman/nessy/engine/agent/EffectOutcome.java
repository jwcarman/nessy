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
package org.jwcarman.nessy.engine.agent;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Optional;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.spi.inference.Failure;

/**
 * What performing an effect came to.
 *
 * <p>Free of {@code <O>}, and that is the point. A dispatcher holds rows, not observation types; it
 * could not name {@code O} if it wanted to. When outcomes travelled as {@code AgentEvent<?>} the
 * wildcard was the design telling us the two had been conflated -- an observation carries the
 * caller's type into the fold, an outcome carries the engine's own vocabulary back out, and only
 * one of them can be generic.
 *
 * <p>Almost never stored. An outcome normally lives from the moment a handler returns it to the
 * moment the fold consumes it, inside one process; what survives a crash is the effect row, which
 * becomes actionable again and is performed again.
 *
 * <p>The exception is the failure response written alongside an effect when it is emitted, for the
 * case where the effect itself cannot be decoded and so nothing can be routed or minted. That one
 * is stored, which is why this carries a discriminator.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = EffectOutcome.InferenceAnswered.class, name = "inference-answered"),
  @JsonSubTypes.Type(value = EffectOutcome.InferenceFailed.class, name = "inference-failed"),
  @JsonSubTypes.Type(value = EffectOutcome.InferenceRefused.class, name = "inference-refused"),
  @JsonSubTypes.Type(
      value = EffectOutcome.InferenceRequestedActions.class,
      name = "inference-requested-actions"),
  @JsonSubTypes.Type(value = EffectOutcome.ToolSucceeded.class, name = "tool-succeeded"),
  @JsonSubTypes.Type(value = EffectOutcome.ToolFailed.class, name = "tool-failed"),
  @JsonSubTypes.Type(value = EffectOutcome.ToolApproved.class, name = "tool-approved"),
  @JsonSubTypes.Type(value = EffectOutcome.ToolDenied.class, name = "tool-denied")
})
public sealed interface EffectOutcome {

  /**
   * The model said something.
   *
   * <p>Carries the blocks the model produced, not text and not a positioned message. Text would
   * discard whatever an answer holds that a string cannot; a message would mean a dispatcher
   * choosing where in the story the answer belongs, which only the fold can know.
   */
  record InferenceAnswered(List<Block.AnswerContent> blocks) implements EffectOutcome {}

  /**
   * The model declined to answer, and said so.
   *
   * <p>A third ending, distinct from both the others: the call succeeded and produced no answer.
   * Recording it as a failure would tell a later reader the provider was unreachable when it was
   * working perfectly; recording it as an answer would put words in the model's mouth, since a
   * refusal was measured to carry no content at all.
   */
  record InferenceRefused(String category) implements EffectOutcome {}

  /**
   * The model could not be made to answer.
   *
   * <p>Carries the {@link Failure} rather than a sentence, because what matters about a failure is
   * not how it reads but what is <em>known</em>: whether the identical request would fail
   * identically. A provider adapter works that out from a status code it alone understands, and
   * flattening it to a message here would compute the answer and throw it away one line later.
   *
   * <p>Nothing acts on the distinction yet. What it unlocks is the difference between an agent that
   * had a blip and one that can never speak again -- today those are indistinguishable, so an agent
   * whose every future turn will fail returns to idle looking perfectly healthy.
   */
  record InferenceFailed(Failure failure) implements EffectOutcome {}

  /**
   * The model asked for work before it would answer.
   *
   * <p>The one outcome of an inference that leaves the agent owing more than it did before. Carries
   * the whole of what came back rather than just the calls, because the prose and the vendor state
   * around them are part of the same message and are re-sent with it.
   */
  record InferenceRequestedActions(List<Block.ActionRequestContent> blocks)
      implements EffectOutcome {}

  /**
   * A tool ran and produced content.
   *
   * <p>The {@code callId} is not decoration: the fold uses it to know which of the calls it is
   * still waiting on has been discharged, and to recognise a redelivery of one already discharged.
   * An outcome that could not name its call could not do either.
   */
  record ToolSucceeded(CallId callId, List<Block.ToolResultContent> blocks)
      implements EffectOutcome {}

  /**
   * A tool was run and did not produce content.
   *
   * <p>Carries a sentence rather than a {@link Failure}, which is the opposite of {@link
   * InferenceFailed} and deliberately so. A failed inference is the engine's problem and what
   * matters is whether retrying could work; a failed tool call is the <em>model's</em> problem, it
   * is going to read this, and what matters is that it can tell what to do next. Whether the
   * attempt is worth repeating was settled before this was minted.
   */
  record ToolFailed(CallId callId, String message) implements EffectOutcome {}

  /** A call was never run, because an approver said no. */
  record ToolDenied(CallId callId, String reason, Optional<String> reference)
      implements EffectOutcome {

    public ToolDenied(CallId callId, String reason) {
      this(callId, reason, Optional.empty());
    }
  }

  /**
   * A call may run.
   *
   * <p>The only outcome in this vocabulary that discharges nothing. Every other one ends an
   * obligation; this one advances it, and the call it names is still owed a result. That is why the
   * fold checks the call's phase rather than merely its presence -- a redelivered approval must not
   * dispatch a second attempt at a tool that is already running.
   */
  record ToolApproved(CallId callId, Optional<String> reference) implements EffectOutcome {

    /**
     * Allowed, with nothing standing behind it -- an ungated tool, or a rule that is its own
     * evidence.
     */
    public ToolApproved(CallId callId) {
      this(callId, Optional.empty());
    }
  }
}
