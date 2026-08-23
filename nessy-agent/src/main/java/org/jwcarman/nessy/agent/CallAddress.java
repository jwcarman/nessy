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
package org.jwcarman.nessy.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.ComputationId;

/**
 * Where one tool call's durable questions live (spec §10.9): stamped by the executor — the one
 * party that provably holds the scope — before the tool runs. The two derivations below are the
 * single site the address formulas exist at; anyone holding the coordinates re-derives the same
 * ids, which is the submit-once discipline's foundation and lets external systems dedup on them.
 *
 * <p>Both derivations digest (computation-identity spec §2): SHA-256 over a length-prefixed UTF-8
 * encoding of {@code (purpose, agentType, agentId, responseId, callId)}, rendered lowercase hex —
 * opaque and one-way, carrying no extractable structure. The length prefix on every field closes
 * the concatenation-ambiguity hole a plain delimiter leaves open (e.g. {@code agentType="a:b"}
 * colliding with {@code agentType="a", agentId="b:..."}); {@code purpose} is the one differentiator
 * between the two derivations below, so an approval and an execution over the identical remaining
 * tuple never collide.
 *
 * <p>Lives in {@code nessy-agent}, public (computation-identity spec §4 addendum, the whittle
 * ruling): {@code nessy-api}'s {@code ToolContext} no longer carries this type (it exposes only the
 * opaque {@link ComputationId} it derives), and {@link
 * org.jwcarman.nessy.spi.approval.ApprovalRequest} never did — so nothing in {@code nessy-api} or
 * {@code nessy-spi} forces it there anymore. It stays public here because {@link
 * org.jwcarman.nessy.agent.spi.ToolCallExecutor#executeGrantedToolNow} and {@link
 * org.jwcarman.nessy.agent.spi.DeferredToolCallPolicy#onDeferred}/{@code pendingComputation} — both
 * in the cross-package {@code agent.spi} — carry it across the package line.
 *
 * @param agentType the recipe's name
 * @param agentId the scope
 * @param responseId the committed model response that produced this call (durable-deliveries spec
 *     §2) — closes the provider-uniqueness hole a bare {@code callId} leaves open, since provider
 *     call ids are not contractually unique over an agent's lifetime
 * @param callId the provider-assigned tool call id
 */
public record CallAddress(String agentType, String agentId, String responseId, String callId) {

  private static final String DIGEST_ALGORITHM = "SHA-256";
  private static final String PURPOSE_APPROVAL = "approval";
  private static final String PURPOSE_EXECUTION = "execution";

  public CallAddress {
    requireText(agentType, "agentType");
    requireText(agentId, "agentId");
    requireText(responseId, "responseId");
    requireText(callId, "callId");
  }

  /** The address of "may it run?" — completed with a {@code Decision} by the approval desk. */
  public ComputationId approval() {
    return digest(PURPOSE_APPROVAL);
  }

  /** The address of "what did it return?" — completed with a {@code ToolResult}. */
  public ComputationId execution() {
    return digest(PURPOSE_EXECUTION);
  }

  private ComputationId digest(String purpose) {
    MessageDigest digest = newDigest();
    updateLengthPrefixed(digest, purpose);
    updateLengthPrefixed(digest, agentType);
    updateLengthPrefixed(digest, agentId);
    updateLengthPrefixed(digest, responseId);
    updateLengthPrefixed(digest, callId);
    return ComputationId.of(HexFormat.of().formatHex(digest.digest()));
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(DIGEST_ALGORITHM + " must be available on every JVM", e);
    }
  }

  /** {@code field}'s UTF-8 byte length (4-byte big-endian), then the bytes themselves. */
  private static void updateLengthPrefixed(MessageDigest digest, String field) {
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update((byte) (bytes.length >>> 24));
    digest.update((byte) (bytes.length >>> 16));
    digest.update((byte) (bytes.length >>> 8));
    digest.update((byte) bytes.length);
    digest.update(bytes);
  }

  private static void requireText(String value, String name) {
    Objects.requireNonNull(value, name + " must not be null");
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
