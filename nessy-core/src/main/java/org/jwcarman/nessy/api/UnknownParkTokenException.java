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
 * {@code token} names no wait a {@link org.jwcarman.nessy.Harness} can resume — either the store
 * has never heard of it, or it named a park that has already settled (every real transport is
 * at-least-once, but a token this store no longer recognizes at all is not the same as one it still
 * recognizes but has already consumed — see {@link org.jwcarman.nessy.Harness#resume(ParkToken,
 * ToolResolution, org.jwcarman.nessy.api.turn.TurnObserver)}).
 *
 * <p>Distinct from {@link IllegalArgumentException}, which everywhere else in this package still
 * covers a caller's own argument misuse — this is a named rejection over a specific token, not a
 * hand-rolled wiring desync.
 */
public final class UnknownParkTokenException extends RuntimeException {

  public UnknownParkTokenException(ParkToken token) {
    super("unknown or settled park token: " + token);
  }
}
