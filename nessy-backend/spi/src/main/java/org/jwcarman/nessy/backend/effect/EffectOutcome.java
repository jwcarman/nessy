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
package org.jwcarman.nessy.backend.effect;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Truncator;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolConfig;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.inference.Failure;

/**
 * What performing an effect came to.
 *
 * <p>Free of {@code <I>}, and that is the point. A dispatcher holds rows, not input types; it could
 * not name {@code I} if it wanted to. When outcomes travelled as {@code AgentEvent<?>} the wildcard
 * was the design telling us the two had been conflated -- an input carries the caller's type into
 * the fold, an outcome carries the engine's own vocabulary back out, and only one of them can be
 * generic.
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

  // Nothing here carries content itself. Whatever produced this outcome -- the thing that called
  // the model, the thing that ran the tool -- put what it produced away before saying so, because
  // that is the moment the content exists and the only place that has both the content and
  // somewhere to put it. What crosses into the fold is a reference, a status, a decision or a
  // count, and, for a tool call, the one bounded line saying what it returned or why it failed.

  /**
   * The model said something.
   *
   * <p>Carries the blocks the model produced, not text and not a positioned message. Text would
   * discard whatever an answer holds that a string cannot; a message would mean a dispatcher
   * choosing where in the story the answer belongs, which only the fold can know.
   */
  record InferenceAnswered(PayloadRef answer, Usage usage) implements EffectOutcome {
    public InferenceAnswered {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The model declined to answer, and said so.
   *
   * <p>A third ending, distinct from both the others: the call succeeded and produced no answer.
   * Recording it as a failure would tell a later reader the provider was unreachable when it was
   * working perfectly; recording it as an answer would put words in the model's mouth, since a
   * refusal was measured to carry no content at all.
   */
  record InferenceRefused(String category, Usage usage) implements EffectOutcome {
    public InferenceRefused {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

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
  record InferenceFailed(Failure failure, Usage usage) implements EffectOutcome {
    public InferenceFailed {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The model asked for work before it would answer.
   *
   * <p>The one outcome of an inference that leaves the agent owing more than it did before. Carries
   * the whole of what came back rather than just the calls, because the prose and the vendor state
   * around them are part of the same message and are re-sent with it.
   */
  record InferenceRequestedActions(PayloadRef request, List<ActionRequest> actions, Usage usage)
      implements EffectOutcome {
    public InferenceRequestedActions {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * A tool ran and produced content.
   *
   * <p>The {@code callId} is not decoration: the fold uses it to know which of the calls it is
   * still waiting on has been discharged, and to recognise a redelivery of one already discharged.
   * An outcome that could not name its call could not do either.
   *
   * <p>{@code rendered} is what the call returned, in a line made by its binding: never null, and
   * empty when the binding had nothing to say. It goes to the event and no further.
   */
  record ToolSucceeded(CallId callId, PayloadRef result, String rendered) implements EffectOutcome {
    public ToolSucceeded {
      Objects.requireNonNull(rendered, "rendered must not be null");
    }
  }

  /**
   * A tool call did not produce content, and {@code kind} says why: the tool ran and failed or
   * could not be run ({@code FAILED}), the call did not finish before its deadline and whether it
   * ran is not known ({@code PAST_DEADLINE}), or permission was never given ({@code
   * NOT_AUTHORISED}, in which case the call never ran). Nobody refused it; a refusal is {@link
   * ToolDenied}.
   *
   * <p>Carries a sentence rather than a {@link Failure}, which is the opposite of {@link
   * InferenceFailed} and deliberately so. A failed inference is the engine's problem and what
   * matters is whether retrying could work; a failed tool call is the <em>model's</em> problem, it
   * is going to read this, and what matters is that it can tell what to do next. Whether the
   * attempt is worth repeating was settled before this was minted.
   *
   * <p>The {@code message} is at most {@link ToolConfig#LINE_CAP} (1,000) characters. A longer one
   * has its middle dropped and {@code ...} in the gap, whitespace untouched, and the shortened text
   * is what is stored and what the model reads back for the call. Every failure is made here, so
   * every failure is bounded wherever it was built.
   */
  record ToolFailed(CallId callId, CallFailure kind, String message) implements EffectOutcome {
    public ToolFailed {
      Objects.requireNonNull(callId, "callId must not be null");
      Objects.requireNonNull(kind, "kind must not be null");
      Objects.requireNonNull(message, "message must not be null");
      message = Truncator.dropMiddle().truncate(message, ToolConfig.LINE_CAP);
    }
  }

  /** A call was never run, because an approver said no. */
  record ToolDenied(CallId callId, String reason, Optional<String> decidedBy)
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
  record ToolApproved(CallId callId, Optional<String> decidedBy) implements EffectOutcome {

    /**
     * Allowed, with nothing standing behind it -- an ungated tool, or a rule that is its own
     * evidence.
     */
    public ToolApproved(CallId callId) {
      this(callId, Optional.empty());
    }
  }
}
