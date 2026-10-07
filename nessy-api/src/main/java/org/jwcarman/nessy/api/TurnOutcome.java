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
package org.jwcarman.nessy.api;

/**
 * How a turn ended, as the trajectory fingerprint classifies it.
 *
 * <p>Not {@link AskOutcome}: that is what a caller gets back, and this is what the turn did. The
 * two differ where it matters for behaviour: a policy stop and an inference failure both surface to
 * a caller as failed, and are two different ways for a turn to go. A truncated answer is its own
 * class because the caller got a stump and the cause is the output limit, not the provider.
 *
 * <p>The tag is the byte the canonical trajectory encoding writes; it never changes once assigned.
 */
public enum TurnOutcome {
  /** The model answered in full. */
  ANSWERED((byte) 1),
  /** The model answered and was cut off at its output limit. */
  TRUNCATED((byte) 2),
  /** The model refused. */
  REFUSED((byte) 3),
  /** Inference failed, after whatever retries the application asked for. */
  FAILED((byte) 4),
  /** The turn policy stopped the turn. */
  STOPPED((byte) 5);

  private final byte tag;

  TurnOutcome(byte tag) {
    this.tag = tag;
  }

  /** The byte that stands for this outcome in the canonical trajectory encoding. */
  public byte tag() {
    return tag;
  }
}
