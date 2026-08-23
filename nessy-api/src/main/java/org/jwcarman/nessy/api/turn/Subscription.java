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
package org.jwcarman.nessy.api.turn;

/**
 * What subscribing a {@link TurnObserver} hands back (front-ends spec §2) — the ONLY closeable in
 * the API, because it is the only thing that ever holds a routing entry. {@code close()} narrows
 * {@link AutoCloseable}'s checked {@code throws Exception} away entirely: dropping a routing entry
 * cannot fail, so there is nothing here to throw. Close is also idempotent — closing twice, or
 * closing after the subscribed-to scope is long gone, is a no-op either way.
 *
 * <p>The routing entry this holds lives inside the harness, scoped to one agent id; dropping a
 * {@code Subscription} unclosed leaks exactly that one entry, never a thread.
 */
public interface Subscription extends AutoCloseable {

  @Override
  void close();
}
