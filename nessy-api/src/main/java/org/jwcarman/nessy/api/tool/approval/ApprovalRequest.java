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
package org.jwcarman.nessy.api.tool.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.Key;
import org.jwcarman.nessy.api.tool.authorization.RiskAssessment;

/**
 * The question an approver answers: this call, on this agent, with these facts (approval-lifecycle
 * spec §1.2).
 *
 * <p>A JSON document by contract. Every field renders through the harness's pinned mapper,
 * deterministically, and the rendered document is the record of what was decided on: read by the
 * approver, parked with the computation when the approver defers, shown to the desk, and pointed at
 * by the answer's reference. Rendered once — the {@code action} line and every fact are fixed at
 * enrichment and never re-derived at read time.
 *
 * @param action the {@code ActionContributor}'s line, rendered at enrichment; empty when the grant
 *     rendered none
 */
public record ApprovalRequest(
    String agentType, String agentId, ToolCall call, String action, Facts facts) {

  /** The principal a call acts for, if a principal-resolving enricher deposited one. */
  public static final Key<String> PRINCIPAL = new Key<>(String.class, "principal");

  /** The risk a risk-assessing enricher deposited, if any. */
  public static final Key<RiskAssessment> RISK = new Key<>(RiskAssessment.class, "risk");

  public ApprovalRequest {
    Objects.requireNonNull(agentType, "agentType must not be null");
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(action, "action must not be null");
    Objects.requireNonNull(facts, "facts must not be null");
  }

  /**
   * A copy of this request whose {@link Facts} decode with {@code pinned} — the one attach site
   * every decoding path calls. A request that came off the wire has an unattached bag (Jackson
   * cannot hand a mapper to a creator), so typed fact reads would throw until this re-binds it.
   *
   * @param pinned the pinned mapper
   * @return this request, its facts attached
   */
  public ApprovalRequest attach(ObjectMapper pinned) {
    Objects.requireNonNull(pinned, "pinned mapper must not be null");
    return new ApprovalRequest(agentType, agentId, call, action, facts.attach(pinned));
  }

  /** What the harness starts from. Enrichment fills the rest; {@link Draft#freeze} ends it. */
  public static Draft draft(
      String agentType, String agentId, ToolCall call, Object input, ObjectMapper pinned) {
    return new Draft(agentType, agentId, call, input, pinned);
  }

  /** A codec over the pinned mapper; decoding re-attaches it so typed reads work. */
  public static Codec<ApprovalRequest> codec(ObjectMapper mapper) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    return new Codec<>() {
      @Override
      public byte[] encode(ApprovalRequest value) {
        try {
          return mapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("unencodable approval request", e);
        }
      }

      @Override
      public ApprovalRequest decode(byte[] bytes) {
        try {
          return mapper
              .readValue(new String(bytes, StandardCharsets.UTF_8), ApprovalRequest.class)
              .attach(mapper);
        } catch (JsonProcessingException e) {
          throw new IllegalArgumentException("undecodable approval request", e);
        }
      }
    };
  }

  /**
   * The request while it is being enriched. Mutable on purpose and short-lived: the harness hands
   * it to the contributor and each enricher in turn, then freezes it. Nothing outside enrichment
   * ever sees a Draft, and a Draft freezes once.
   */
  public static final class Draft {

    private final String agentType;
    private final String agentId;
    private final ToolCall call;
    private final Object input;
    private final Facts.Deposits deposits;
    private String action = "";
    private boolean frozen;

    private Draft(
        String agentType, String agentId, ToolCall call, Object input, ObjectMapper pinned) {
      this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
      this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
      this.call = Objects.requireNonNull(call, "call must not be null");
      this.input = Objects.requireNonNull(input, "input must not be null");
      this.deposits = Facts.deposits(pinned);
    }

    public String agentType() {
      return agentType;
    }

    public String agentId() {
      return agentId;
    }

    public ToolCall call() {
      return call;
    }

    /**
     * The bound tool input, as the record the tool author declared — the same information as {@code
     * call().arguments()}, typed. Transient: it is not part of the frozen document, because the
     * call's arguments already are. A mismatch throws {@link ClassCastException} naming both types.
     */
    public <T> T input(Class<T> type) {
      Objects.requireNonNull(type, "type must not be null");
      if (!type.isInstance(input)) {
        throw new ClassCastException(
            "draft input is " + input.getClass().getName() + ", not " + type.getName());
      }
      return type.cast(input);
    }

    public Draft action(String rendered) {
      requireOpen();
      this.action = Objects.requireNonNull(rendered, "action must not be null");
      return this;
    }

    /** Encodes {@code value} now — an unrenderable fact fails here, naming the key. */
    public <T> Draft deposit(Key<T> key, T value) {
      requireOpen();
      deposits.put(key, value);
      return this;
    }

    public ApprovalRequest freeze() {
      requireOpen();
      frozen = true;
      return new ApprovalRequest(agentType, agentId, call, action, deposits.freeze());
    }

    private void requireOpen() {
      if (frozen) {
        throw new IllegalStateException("this draft was already frozen");
      }
    }
  }
}
