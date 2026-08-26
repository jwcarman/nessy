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

/**
 * Where one tool call's durable questions live (spec §10.9): stamped by the executor — the one
 * party that provably holds the scope — before the tool runs. {@link #digest()} below is the single
 * site the address formula exists at; anyone holding the coordinates re-derives the same key, which
 * is the submit-once discipline's foundation and lets external systems dedup on them.
 *
 * <p>The derivation digests (computation-identity spec §2): SHA-256 over a length-prefixed UTF-8
 * encoding of {@code (agentType, agentId, responseId, toolCallId)}, rendered lowercase hex — opaque
 * and one-way, carrying no extractable structure. The length prefix on every field closes the
 * concatenation-ambiguity hole a plain delimiter leaves open (e.g. {@code agentType="a:b"}
 * colliding with {@code agentType="a", agentId="b:..."}).
 *
 * <p>Purpose-free: with Continuum minting opaque computation ids, this digest no longer derives a
 * computation's identity — it is the stable, per-call key a remembrance is filed under and the
 * handle a tool sees as its own invocation id.
 *
 * @param agentType the recipe's name
 * @param agentId the scope
 * @param responseId the committed model response that produced this call (durable-deliveries spec
 *     §2) — closes the provider-uniqueness hole a bare {@code toolCallId} leaves open, since
 *     provider call ids are not contractually unique over an agent's lifetime
 * @param toolCallId the provider-assigned tool call id
 */
public record ToolCallAddress(
    String agentType, String agentId, String responseId, String toolCallId) {

  private static final String DIGEST_ALGORITHM = "SHA-256";

  public ToolCallAddress {
    requireText(agentType, "agentType");
    requireText(agentId, "agentId");
    requireText(responseId, "responseId");
    requireText(toolCallId, "toolCallId");
  }

  /**
   * This call's stable digest over the four coordinates — the same key wherever it is re-derived.
   *
   * @return the digest, lowercase hex
   */
  public String digest() {
    MessageDigest digest = newDigest();
    updateLengthPrefixed(digest, agentType);
    updateLengthPrefixed(digest, agentId);
    updateLengthPrefixed(digest, responseId);
    updateLengthPrefixed(digest, toolCallId);
    return HexFormat.of().formatHex(digest.digest());
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
