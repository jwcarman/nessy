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

import java.util.Map;
import java.util.Objects;
import org.apache.pekko.actor.typed.ActorRef;

/**
 * Everything an agent can be told.
 *
 * <p><b>Not generic, and it cannot be.</b> {@code EntityTypeKey.create(Class<T>, String)} is the
 * only factory sharding offers, and no class literal exists for a parameterised type — so a generic
 * message type would need an unchecked cast. The observation therefore crosses this boundary as
 * BYTES, and the agent decodes it on arrival into typed state. That is the single place erasure
 * costs anything: {@code AgentActor<O>} and {@code AgentState<O>} are both fine, because neither
 * {@code DurableStateBehavior} nor {@code Behaviors} needs a literal.
 *
 * <p>Headers carry W3C trace context, empty when the sender had none.
 */
public sealed interface NessyMessage {

  Map<String, String> headers();

  /** Something happened. The payload is an encoded observation, decoded on arrival. */
  record Observe(byte[] observation, Map<String, String> headers) implements NessyMessage {
    public Observe {
      Objects.requireNonNull(observation, "observation must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** What does this agent look like right now? For pages and for tests. */
  record Inspect(ActorRef<AgentState<?>> replyTo, Map<String, String> headers)
      implements NessyMessage {
    public Inspect {
      Objects.requireNonNull(replyTo, "replyTo must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** From this agent's own turn: it is over, and the agent may start another. */
  record TurnFinished(String turnId, Map<String, String> headers) implements NessyMessage {
    public TurnFinished {
      Objects.requireNonNull(turnId, "turnId must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /**
   * An answer to a tool call this agent parked, arriving from outside the process.
   *
   * <p>Carries a {@code replyTo} because whoever is answering — a webhook handler, say — must not
   * be told it landed until it actually has.
   */
  record AnswerToolCall(
      String callId,
      org.jwcarman.nessy.api.tool.ToolResult result,
      ActorRef<Ack> replyTo,
      Map<String, String> headers)
      implements NessyMessage {
    public AnswerToolCall {
      Objects.requireNonNull(callId, "callId must not be null");
      Objects.requireNonNull(result, "result must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** A person's answer to an approval this agent parked. */
  record AnswerApproval(
      String callId,
      org.jwcarman.nessy.api.tool.ApprovalResult result,
      ActorRef<Ack> replyTo,
      Map<String, String> headers)
      implements NessyMessage {
    public AnswerApproval {
      Objects.requireNonNull(callId, "callId must not be null");
      Objects.requireNonNull(result, "result must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** What an answerer is told once the answer has actually reached the call. */
  record Ack(boolean accepted, String detail) {}

  /**
   * From the shard, after this agent asked to be passivated. The only message that ends it.
   *
   * <p>Nothing else sends this: an agent decides for itself when it is done, and the shard only
   * ever confirms.
   */
  record Stop(Map<String, String> headers) implements NessyMessage {
    public Stop {
      headers = Map.copyOf(headers);
    }
  }

  /**
   * A parked call's deadline has passed, as noticed by the sweep.
   *
   * <p>Sent to the agent's LOGICAL address, so it reaches a passivated agent by reactivating it —
   * which is the whole reason a deadline can be a row instead of a resident actor's timer.
   *
   * <p>Idempotent by contract: at-least-once delivery plus a periodic sweep means this can arrive
   * twice, or for a call that settled a moment ago. An agent that does not recognise the call
   * shrugs; it is never an error.
   */
  record Expired(String callId, Map<String, String> headers) implements NessyMessage {
    public Expired {
      Objects.requireNonNull(callId, "callId must not be null");
      headers = Map.copyOf(headers);
    }
  }

  /** Bring this agent into memory so recovery can run, and start work if any is waiting. */
  record Wake(Map<String, String> headers) implements NessyMessage {
    public Wake {
      headers = Map.copyOf(headers);
    }
  }
}
