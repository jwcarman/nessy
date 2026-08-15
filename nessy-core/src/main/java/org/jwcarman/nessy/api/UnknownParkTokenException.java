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
 * {@code token} names no wait an {@link org.jwcarman.nessy.Agent} can resume — the registry has
 * never heard of it at all. Registry entries survive resolution (design §5): a token this registry
 * still recognizes but whose call has already settled does not throw this — a redelivered resume
 * (every real transport is at-least-once) drains quietly instead, the fold's own
 * is-this-call-still-outstanding check telling a genuine replay from a live wait (see {@link
 * org.jwcarman.nessy.Agent#resume(ParkToken, ToolResolution,
 * org.jwcarman.nessy.api.turn.TurnObserver)}).
 *
 * <p>Distinct from {@link IllegalArgumentException}, which everywhere else in this package still
 * covers a caller's own argument misuse — this is a named rejection over a specific token, not a
 * hand-rolled wiring desync.
 */
public final class UnknownParkTokenException extends RuntimeException {

  public UnknownParkTokenException(ParkToken token) {
    super("unknown park token: " + token.value());
  }
}
