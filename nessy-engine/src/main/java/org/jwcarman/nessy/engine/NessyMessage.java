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
package org.jwcarman.nessy.engine;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.pekko.actor.typed.ActorRef;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * Everything an agent can be told.
 *
 * <p><b>One arm per {@link Input}, plus the two that are not facts about a turn.</b> The agent's
 * shell translates a message into an input and the logic decides from there, so a message that has
 * no input to become is a message nothing can act on.
 *
 * <p><b>Ids and small statuses only.</b> Whatever produces content claims it BEFORE sending, so no
 * mailbox and no shard hop ever carries a megabyte of {@code docker logs}. That is also what makes
 * a persisted state safe to reference: a state saying a call completed cannot point at a result
 * that is not there.
 *
 * <p><b>Not generic, and it cannot be.</b> {@code EntityTypeKey.create(Class<T>, String)} is the
 * only factory sharding offers, and no class literal exists for a parameterised type. That used to
 * cost something — an observation crossed as BYTES and the agent decoded it — but the backlog is a
 * store now, so nothing here was ever going to be typed anyway.
 *
 * <p>Headers carry W3C trace context, empty when the sender had none.
 */
public sealed interface NessyMessage {

  Map<String, String> headers();

  /**
   * The backlog changed.
   *
   * <p>Carries nothing on purpose. A busy agent drops it, because going idle always ends with a
   * take; duplicates are free, because a take against an empty backlog is a no-op. Give it a
   * payload and it stops being either.
   */
  record BacklogUpdated(Map<String, String> headers) implements NessyMessage {
    public BacklogUpdated {
      headers = Map.copyOf(headers);
    }
  }

  /** The store handed over a row. Its id is the turn id; its claim holds the rendered input. */
  record WorkTaken(TurnId turnId, String observationClaim, Map<String, String> headers)
      implements NessyMessage {
    public WorkTaken {
      Objects.requireNonNull(turnId, "turnId must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** Nothing was waiting. */
  record NoWork(Map<String, String> headers) implements NessyMessage {
    public NoWork {
      headers = Map.copyOf(headers);
    }
  }

  /** The agent tells itself this on every activation. Recovery is not a mode. */
  record Recovered(Map<String, String> headers) implements NessyMessage {
    public Recovered {
      headers = Map.copyOf(headers);
    }
  }

  /** The model answered in prose. The message itself is claimed under {@code answer}. */
  record ModelAnswered(StopReason stopReason, Usage usage, Map<String, String> headers)
      implements NessyMessage {
    public ModelAnswered {
      headers = Map.copyOf(headers);
    }
  }

  /** The model asked for tools. The asking message is claimed under {@code asked}. */
  record ModelAsked(List<Input.CallSummary> calls, Usage usage, Map<String, String> headers)
      implements NessyMessage {
    public ModelAsked {
      calls = List.copyOf(calls);
      headers = Map.copyOf(headers);
    }
  }

  /** A safety classifier declined. Short prose, written to be read. */
  record ModelRefused(String category, String explanation, Usage usage, Map<String, String> headers)
      implements NessyMessage {
    public ModelRefused {
      headers = Map.copyOf(headers);
    }
  }

  /** The call did not happen — a rate limit, a timeout, a connection reset. */
  record ModelFailed(String reason, Map<String, String> headers) implements NessyMessage {
    public ModelFailed {
      headers = Map.copyOf(headers);
    }
  }

  /**
   * The approver answered.
   *
   * <p>The same message whether the approver answered on the spot or a person clicked three days
   * later on a reply token. The agent has no reason to care which, and giving it two names was how
   * the old engine ended up relaying everything down a hierarchy.
   */
  record ApprovalGiven(
      CallId callId, String toolName, ApprovalResult result, Map<String, String> headers)
      implements NessyMessage {
    public ApprovalGiven {
      headers = Map.copyOf(headers);
    }
  }

  /** The answer will come later; someone holds a reply token. */
  record ToolParked(CallId callId, Instant expiresAt, Map<String, String> headers)
      implements NessyMessage {
    public ToolParked {
      headers = Map.copyOf(headers);
    }
  }

  /** A call is done, however long it took. Its result is claimed. */
  record ToolCompleted(CallId callId, Map<String, String> headers) implements NessyMessage {
    public ToolCompleted {
      headers = Map.copyOf(headers);
    }
  }

  /**
   * Time ran out on a call.
   *
   * <p>Distinct from {@link ToolCompleted} deliberately: the sweep knows time passed and does not
   * get to decide what that means.
   */
  record DeadlinePassed(CallId callId, Map<String, String> headers) implements NessyMessage {
    public DeadlinePassed {
      headers = Map.copyOf(headers);
    }
  }

  /**
   * A deferring tool's answer, arriving from outside on a reply token.
   *
   * <p>The result was CLAIMED by whoever accepted the reply, before this was sent — so this carries
   * an id like every other message and the agent's handling of it is identical to a tool that
   * answered in two milliseconds.
   */
  record ToolAnswered(CallId callId, ActorRef<Ack> replyTo, Map<String, String> headers)
      implements NessyMessage {
    public ToolAnswered {
      Objects.requireNonNull(replyTo, "replyTo must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** A person's decision, arriving from outside on a reply token. */
  record ApprovalAnswered(
      CallId callId, ApprovalResult result, ActorRef<Ack> replyTo, Map<String, String> headers)
      implements NessyMessage {
    public ApprovalAnswered {
      Objects.requireNonNull(replyTo, "replyTo must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** Whether the agent took the answer, and why not when it did not. */
  record Ack(boolean accepted, String detail) {}

  /** What is this agent doing? Answered from the document, never from a lock. */
  record Inspect(ActorRef<AgentState> replyTo, Map<String, String> headers)
      implements NessyMessage {
    public Inspect {
      Objects.requireNonNull(replyTo, "replyTo must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /**
   * An application is finished with this agent instance.
   *
   * <p>Cooperative: this sets a flag rather than deleting anything. An idle agent acts at once; a
   * busy one finishes its turn first.
   */
  record Forget(Map<String, String> headers) implements NessyMessage {
    public Forget {
      Objects.requireNonNull(headers, "headers must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** The shard is unloading this agent. */
  record Stop(Map<String, String> headers) implements NessyMessage {
    public Stop {
      headers = Map.copyOf(headers);
    }
  }
}
