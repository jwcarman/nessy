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
package org.jwcarman.nessy.api.tool;

/**
 * The durable computation's stable identity (durable spec §7; computation-identity spec §1, §2):
 * opaque and one-way — a digest over the identity tuple {@code ToolCallAddress.approval()}/{@code
 * ToolCallAddress.execution()} (the durable-wiring module) derive it from, carrying no extractable
 * structure. Deterministic ids are the caller's duty: derive them from the work's coordinates,
 * never mint fresh ones per attempt (preamble ruling 4). Nothing anywhere parses a {@code value()}
 * back apart — the system only ever needs address → id, never the reverse, because a continuation
 * carries the address as data.
 */
public record ComputationId(String value) {

  public ComputationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("computation id must not be blank");
    }
  }

  public static ComputationId of(String value) {
    return new ComputationId(value);
  }
}
