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
package org.jwcarman.nessy.engine.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.backend.effect.FailedAttempt;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.inference.Failure;

/**
 * What the harness asks of an {@link AgentState}. Five of them, and the same five whatever the
 * harness is.
 *
 * <p><b>Imperative name, past-tense payload.</b> {@code CompleteToolCall} is a request that the
 * state may decline; the {@code ToolSucceeded} inside it is a fact that already happened. Both
 * words are correct and both are in the right position.
 *
 * <p><b>Grouped by the effect they complete</b>, one apiece, rather than one command per outcome or
 * one envelope over all of them. An arm therefore knows from the command alone whether it is
 * concerned -- only the one that is looks inside -- and the switch it then does is real logic
 * rather than routing.
 *
 * <p><b>No payloads.</b> Content is claim-checked by the harness before it gets here.
 *
 * <p><b>A completion names the turn it answers.</b> Delivery is at-least-once and a door releases
 * its lock between steps, so an answer for a turn that has since closed can arrive while the agent
 * is busy on the next one. Without the turn on the command, the fold stamps that answer with
 * whatever turn it happens to be in -- and the caller driving that turn is handed an answer to a
 * question it never asked. Each accepting arm checks the turn is its own and ignores it otherwise.
 * {@code StartTurn} and {@code Terminate} carry none: they open a turn or end an agent rather than
 * answering anything.
 */
public sealed interface AgentCommand {

  /** Shared by every {@link InferenceOutcome} arm: none of them tolerates a missing cost. */
  String USAGE_MUST_NOT_BE_NULL = "usage must not be null";

  /**
   * Begin a turn on this input.
   *
   * <p>Accepted only by {@link AgentState.Idle}. Queuing is the harness's business: it holds work
   * while the agent is busy and asks when the agent is not. The state refusing when busy is not
   * redundant with the harness not asking -- two harnesses can both read an idle state and both
   * ask, and this is what makes the loser harmless.
   *
   * @param at when the turn is opening, stamped by whoever is asking rather than read inside the
   *     fold. The fold reads no clock: the same command has to decide the same way whenever it is
   *     applied, and an instant that arrives with it does, where one it fetched would not.
   */
  record StartTurn(PayloadRef input, Instant at) implements AgentCommand {}

  /** Accept nothing further. Work already in flight is still owed its outcome. */
  record Terminate() implements AgentCommand {}

  /**
   * An inference came back.
   *
   * @param priorAttempts what the attempts before this one learned, oldest first, and empty when
   *     the call succeeded first time -- which is every call an application has not asked to be
   *     retried. They ride the command because the row they were kept on is retired by the same
   *     delivery that carries this, and the fold is the last thing able to write them down.
   */
  record CompleteInference(TurnId turn, InferenceOutcome outcome, List<FailedAttempt> priorAttempts)
      implements AgentCommand {
    public CompleteInference {
      priorAttempts = priorAttempts == null ? List.of() : List.copyOf(priorAttempts);
    }

    /** A call that settled on its first attempt, which is all of them unless retries are on. */
    public CompleteInference(TurnId turn, InferenceOutcome outcome) {
      this(turn, outcome, List.of());
    }
  }

  /** An approval decision came back for one call. */
  record CompleteApproval(TurnId turn, CallId callId, ApprovalOutcome outcome)
      implements AgentCommand {}

  /** A tool call came back. */
  record CompleteToolCall(TurnId turn, CallId callId, ToolOutcome outcome)
      implements AgentCommand {}

  /**
   * What an inference produced.
   *
   * <p>Every arm carries what the call cost, because every arm IS a call that happened: a refusal
   * read the input before declining, a failure may have read it before timing out, and a request
   * for tools is an answer the model will be paid for like any other. Nothing normalises a null
   * here -- unlike the stored shapes, these never outlive the process that built them, so a missing
   * cost is a bug rather than an old row.
   */
  sealed interface InferenceOutcome {

    /** What this inference cost, as the vendor counted it. */
    Usage usage();

    record Answered(PayloadRef answer, Usage usage) implements InferenceOutcome {
      public Answered {
        Objects.requireNonNull(usage, USAGE_MUST_NOT_BE_NULL);
      }
    }

    record Refused(String category, Usage usage) implements InferenceOutcome {
      public Refused {
        Objects.requireNonNull(usage, USAGE_MUST_NOT_BE_NULL);
      }
    }

    record Failed(Failure failure, Usage usage) implements InferenceOutcome {
      public Failed {
        Objects.requireNonNull(usage, USAGE_MUST_NOT_BE_NULL);
      }
    }

    record RequestedActions(PayloadRef request, List<ActionRequest> actions, Usage usage)
        implements InferenceOutcome {
      public RequestedActions {
        actions = List.copyOf(actions);
        Objects.requireNonNull(usage, USAGE_MUST_NOT_BE_NULL);
      }
    }
  }

  /** What an approver decided. */
  sealed interface ApprovalOutcome {
    record Approved(Optional<String> reference) implements ApprovalOutcome {}

    record Denied(String reason, Optional<String> reference) implements ApprovalOutcome {}
  }

  /** What a tool produced. */
  sealed interface ToolOutcome {
    record Succeeded(PayloadRef result, String rendered) implements ToolOutcome {
      public Succeeded {
        Objects.requireNonNull(rendered, "rendered must not be null");
      }
    }

    /** Names what went wrong, never the values involved. See {@link AgentEvent.ToolFailed}. */
    record Failed(String message) implements ToolOutcome {}
  }
}
