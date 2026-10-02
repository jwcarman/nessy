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
package org.jwcarman.nessy.backend.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.inference.Failure;

/**
 * What happened. Facts, in order, and the only thing that moves an {@code AgentState}.
 *
 * <p><b>Mostly identifiers, and a little text.</b> An event carries identifiers, status, a human
 * decision, a count, and a {@link PayloadRef} where content would otherwise be. For a tool call it
 * also carries two bounded lines of text, each at most 1,000 characters: {@link
 * ActionRequest.ToolCall#action()}, made when the call is requested, and {@link
 * ToolSucceeded#rendered()}, made from the result. Usually the binding's stringifier makes the
 * action from the call's arguments. When it gives nothing the action is the tool's name; when the
 * arguments do not parse, or the stringifier throws, it is the name and a note that the arguments
 * could not be read; when no tool of that name is bound it is the name and "(no such tool)". So an
 * agent's content is in three places: its payload rows, those two lines in its events, and the
 * summaries of its chapters.
 *
 * <p>What keeps the stream small enough to replay on every command is the references, which stand
 * in for the content, and the bound on those two lines. Every type in it is one of Nessy's own.
 *
 * <p>Two scopes live here. Most events belong to a turn and carry its id; {@link Terminated}
 * belongs to the agent's life and sits between turns.
 *
 * <p><b>This grammar is public backend SPI.</b> {@link
 * org.jwcarman.nessy.backend.event.AgentEvents} is what a backend implements, and it is typed on
 * this interface -- a backend cannot store what it cannot see, and {@code JdbcAgentEvents}
 * genuinely inspects arms (it writes a {@code starts_turn} column by asking whether an event is a
 * {@link TurnStarted}). So from here on, adding an arm to this sealed interface is a public API
 * change, not an internal one; treat it with the same care as any other change to a published type.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = AgentEvent.TurnStarted.class, name = "turn-started"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceAnswered.class, name = "inference-answered"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceRefused.class, name = "inference-refused"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceFailed.class, name = "inference-failed"),
  @JsonSubTypes.Type(value = AgentEvent.InferenceAttempted.class, name = "inference-attempted"),
  @JsonSubTypes.Type(value = AgentEvent.TurnFailed.class, name = "turn-failed"),
  @JsonSubTypes.Type(value = AgentEvent.ActionsRequested.class, name = "actions-requested"),
  @JsonSubTypes.Type(value = AgentEvent.ToolApproved.class, name = "tool-approved"),
  @JsonSubTypes.Type(value = AgentEvent.ToolDenied.class, name = "tool-denied"),
  @JsonSubTypes.Type(value = AgentEvent.ToolSucceeded.class, name = "tool-succeeded"),
  @JsonSubTypes.Type(value = AgentEvent.ToolFailed.class, name = "tool-failed"),
  @JsonSubTypes.Type(value = AgentEvent.Terminated.class, name = "terminated")
})
public sealed interface AgentEvent {

  /** Where this event sits. Strictly increasing, and what {@code apply} checks. */
  Seq seq();

  /**
   * A turn opened on an input. Its {@link TurnId} is this event's own position.
   *
   * <p><b>The one event that carries an instant.</b> A turn's age is something a policy decides on
   * and something a reader wants, and it has to come from somewhere -- so the moment the turn
   * opened is written down here, once, as a fact. That is not the same as the fold reading a clock:
   * a recorded instant replays identically forever, where a duration worked out at replay time
   * would depend on when the replay happened. {@code AgentEvents.writtenAt} is the store's own
   * clock and answers a different question, which is when the row was written rather than when the
   * turn began.
   */
  record TurnStarted(Seq seq, TurnId turn, PayloadRef input, Instant startedAt)
      implements AgentEvent {}

  /**
   * The model answered, and the turn is over.
   *
   * <p>Carries what the call cost, as the vendor counted it. {@link Usage#unreported()} stands for
   * an entry written before this event recorded one, and for a vendor that did not say -- the same
   * reading, because neither counted.
   */
  record InferenceAnswered(Seq seq, TurnId turn, PayloadRef answer, Usage usage)
      implements AgentEvent {
    public InferenceAnswered {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The model declined, and would decline again.
   *
   * <p>A refusal still costs: the model read the input before deciding not to answer it.
   */
  record InferenceRefused(Seq seq, TurnId turn, String category, Usage usage)
      implements AgentEvent {
    public InferenceRefused {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The model was not reached, or did not answer.
   *
   * <p>Carries the {@link Failure} rather than a sentence: a failed inference is the engine's
   * problem and what matters is whether trying again could work.
   *
   * <p>It may still have cost something -- a call that timed out after the model read a long
   * transcript is billed for reading it -- and often nothing was counted at all, because a call
   * that never reached a vendor has no vendor's count.
   */
  record InferenceFailed(Seq seq, TurnId turn, Failure failure, Usage usage) implements AgentEvent {
    public InferenceFailed {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The turn was ended on purpose, and this is why.
   *
   * <p><b>Not an inference that failed.</b> That arm exists for a call that was made and produced
   * nothing; this one is for a turn stopped by a policy, where no call was made at all. Reusing
   * {@link InferenceFailed} would have meant filling its usage with "nobody counted", which claims
   * a call happened that nothing measured -- and no call happened.
   *
   * <p><b>It carries no count, because deciding not to ask costs nothing.</b> What the turn really
   * spent is already recorded on the events that spent it, and an arm with no field for a count
   * cannot say otherwise.
   *
   * <p>A plain reason rather than a {@link Failure}: those arms are statements about whether a
   * request would fail again, and there was no request.
   */
  record TurnFailed(Seq seq, TurnId turn, String reason) implements AgentEvent {}

  /**
   * A model call failed and was tried again.
   *
   * <p><b>Told apart from {@link InferenceFailed} by finality, which is the difference that
   * matters.</b> That one ends a turn; this one is a turn carrying on. A reader who treats them
   * alike will count a turn that stumbled twice and answered as three failures.
   *
   * <p><b>It exists because the attempt cost something and nothing else records it.</b> A retried
   * call reaches the fold once, when it finally settles, carrying the last attempt's count -- so
   * without this the tokens spent on the attempts before it are invisible to anything asking what a
   * turn has spent. That is the reading a budget most needs, because a turn that is thrashing is
   * spending precisely where nobody is looking.
   *
   * <p>One event per attempt rather than a list on the closing event: an attempt is a fact, and a
   * fact hidden inside another event's collection is one no projection over the story will find.
   *
   * <p>Two classifications reach here, by two routes. {@link Failure.Transient} is a provider
   * saying the call might work next time, returned as a value. {@link Failure.Unknown} is an
   * attempt that threw, which the engine classifies itself because a call it never heard back from
   * is exactly what nothing is known about -- and a throw has no vendor's count, so its usage is
   * always unreported. The rest never appear: they end the work rather than repeat it.
   */
  record InferenceAttempted(Seq seq, TurnId turn, Failure failure, Usage usage)
      implements AgentEvent {
    public InferenceAttempted {
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /**
   * The model asked for work before it would answer.
   *
   * <p>The calls are in the spine because the state must know what it is waiting for; their
   * arguments are behind {@code request}, because only the tool ever reads those.
   *
   * <p>This is an answer from the model like any other and costs like one. A turn that calls three
   * tools before answering pays for four inferences, and an accounting that counted only the last
   * would miss most of what a tool-using agent spends.
   */
  record ActionsRequested(
      Seq seq, TurnId turn, PayloadRef request, List<ActionRequest> actions, Usage usage)
      implements AgentEvent {
    public ActionsRequested {
      actions = List.copyOf(actions);
      usage = usage == null ? Usage.unreported() : usage;
    }
  }

  /** A call was allowed to run. */
  record ToolApproved(Seq seq, TurnId turn, CallId callId, Optional<String> reference)
      implements AgentEvent {}

  /** A call was refused and never ran. */
  record ToolDenied(Seq seq, TurnId turn, CallId callId, String reason, Optional<String> reference)
      implements AgentEvent {}

  /**
   * A call ran and produced something.
   *
   * <p>{@code rendered} is what the call returned, in a line its binding made when the result was
   * recorded: never null, empty when there was nothing to say, and at most 1,000 characters. It is
   * fixed when written and never worked out again.
   */
  record ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result, String rendered)
      implements AgentEvent {
    public ToolSucceeded {
      Objects.requireNonNull(rendered, "rendered must not be null");
    }
  }

  /**
   * A call ran and did not produce content.
   *
   * <p>Carries a sentence rather than a {@link Failure}, which is the opposite of {@link
   * InferenceFailed} and deliberately so: a failed tool call is the <em>model's</em> problem, it is
   * going to read this, and what matters is that it can tell what to do next.
   *
   * <p>This is free text, kept in the clear for a long time, so it names what went wrong and never
   * the values involved. It is at most 1,000 characters: a longer message has its middle dropped
   * and {@code ...} in the gap before it is stored, and what is stored is the text the model reads
   * back for the call.
   */
  record ToolFailed(Seq seq, TurnId turn, CallId callId, String message) implements AgentEvent {}

  /**
   * The agent will accept nothing further.
   *
   * <p>Agent-scoped, so it carries no {@link TurnId}: it sits between turns rather than inside one.
   * Applying it yields {@code AgentState.Terminal}, which refuses everything -- so termination is
   * irreversible by construction rather than by a flag somebody must remember to check.
   */
  record Terminated(Seq seq) implements AgentEvent {}
}
