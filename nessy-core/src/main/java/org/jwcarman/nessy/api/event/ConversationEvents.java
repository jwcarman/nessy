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
package org.jwcarman.nessy.api.event;

import java.util.function.Consumer;

/**
 * The one dynamic level of listening (design §17): a per-conversation, in-memory, non-durable
 * subscription — UI/SSE attachment, a debugging tap, anything that needs to attach and detach at
 * runtime rather than being declared once at {@code build()}.
 *
 * <p>Obtained from {@code Conversation#events()}. Every instance is already scoped to the one
 * conversation it came from: {@code subscribe} only ever delivers events self-attributed ({@link
 * ConversationScoped}) to that conversation's id, so nothing subscribed here ever sees another
 * conversation's traffic. Delivery is synchronous, in subscription order — the same veto-by-throw
 * contract every other sync listener in the framework carries.
 */
public interface ConversationEvents {

  <T> Subscription subscribe(Class<T> type, Consumer<T> listener);
}
