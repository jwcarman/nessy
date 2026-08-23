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
 * closing after the subscribed-to scope is long gone, is a no-op either way; a closed subscription
 * leaks nothing at all, not even an empty routing entry — only an unclosed one leaks (exactly the
 * one entry it holds, never a thread).
 *
 * <p>{@code close()} stops future emissions from being handed to the observer; it does not
 * synchronize with one already in flight on another thread — an event dispatch that started just
 * before {@code close()} runs may still reach the observer after {@code close()} returns.
 *
 * <p>The observer behind this subscription hears a turn's whole grammar — {@code TextDelta}, {@code
 * ThinkingDelta}, {@code RedactedThinking}, {@code ToolCallRequested}, {@code ToolCallCompleted},
 * {@code ToolCallProgressed}, {@code AssistantSaid}, and {@code TurnEnded} (front-ends spec §1,
 * Task 3: the last two now ride this same channel, not a second one alongside it) — see the
 * subscribing door's own javadoc.
 */
public interface Subscription extends AutoCloseable {

  @Override
  void close();
}
