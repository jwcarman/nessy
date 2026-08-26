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
package org.jwcarman.nessy.agent.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jwcarman.nessy.agent.Phase;
import org.jwcarman.nessy.agent.ToolCallState;
import org.jwcarman.nessy.api.tool.approval.ApprovalRequest;

/**
 * Internal storage machinery: renders {@link Phase} to and from the JSON the byte-payload substrate
 * persists as the {@code state} document's payload (spec §7). Not API vocabulary — the scope
 * version lives in the substrate's own document version (see the {@code state} recipe), not in this
 * payload.
 *
 * <p>{@link Phase} carries its own Jackson annotations (spec §7); this codec is the mapper-binding
 * boundary. {@code AwaitingTools} round-trips through its canonical constructor, so its
 * calls-non-empty and calls-subset-of-the-turn invariants are re-checked on every read — a
 * violation surfaces as a Jackson failure this codec translates into an {@link
 * IllegalArgumentException} naming the offense, same as a malformed payload or an unrecognized
 * discriminator.
 *
 * <p>Wraps one caller-supplied, already-pinned {@link ObjectMapper} (spec §7) — no static mapper
 * survives here.
 */
public final class StateCodec {

  private final Codecs codecs;
  private final ObjectMapper mapper;

  public StateCodec(ObjectMapper mapper) {
    this.codecs = new Codecs(mapper);
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  public String toJson(Phase phase) {
    Objects.requireNonNull(phase, "phase must not be null");
    return codecs.write(phase);
  }

  public Phase phase(String json) {
    Objects.requireNonNull(json, "json must not be null");
    JsonNode root = codecs.readTree(json, "phase");
    return attach(codecs.bind(root, Phase.class, "phase"));
  }

  /**
   * Re-attaches the pinned mapper to every parked {@link ApprovalRequest} in a decoded phase.
   * Jackson cannot hand a mapper to a creator, so a request read back from storage carries an
   * unattached facts bag and would throw on the first typed read — which is exactly what the desk
   * does when it shows a human the parked question.
   */
  private Phase attach(Phase phase) {
    if (!(phase instanceof Phase.AwaitingTools awaiting)) {
      return phase;
    }
    Map<String, ToolCallState> attached = new TreeMap<>();
    awaiting.calls().forEach((callId, status) -> attached.put(callId, attach(status)));
    return new Phase.AwaitingTools(awaiting.assistantTurn(), attached, awaiting.responseId());
  }

  private ToolCallState attach(ToolCallState status) {
    return status instanceof ToolCallState.AwaitingApproval(var approval, var request)
        ? new ToolCallState.AwaitingApproval(approval, request.attach(mapper))
        : status;
  }
}
