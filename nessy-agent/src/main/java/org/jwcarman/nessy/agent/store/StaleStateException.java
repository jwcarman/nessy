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
package org.jwcarman.nessy.agent.store;

/** Another writer advanced the scope first. Reload, re-handle, retry (spec §3.4). */
public final class StaleStateException extends RuntimeException {

  private final long expected;
  private final long found;

  public StaleStateException(long expected, long actual) {
    super("expected version " + expected + " but store holds " + actual);
    this.expected = expected;
    this.found = actual;
  }

  public long expected() {
    return expected;
  }

  public long found() {
    return found;
  }
}
