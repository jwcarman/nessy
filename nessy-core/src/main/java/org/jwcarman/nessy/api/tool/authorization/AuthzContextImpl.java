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
package org.jwcarman.nessy.api.tool.authorization;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.ToolCall;

/**
 * The sole {@link AuthzContext} implementation — package-private, so the interface stays the only
 * reachable shape (design of record 2026-08-16-authorization §3). Deposits live in an immutable
 * map; {@link #with} copies rather than mutates, which is what makes an earlier enricher's own
 * context reference stay exactly what it was after a later one extends it.
 */
final class AuthzContextImpl implements AuthzContext {

  private final ConversationId conversationId;
  private final String agentName;
  private final ToolCall call;
  private final ConversationState state;
  private final Map<Key<?>, Object> deposits;

  AuthzContextImpl(
      ConversationId conversationId, String agentName, ToolCall call, ConversationState state) {
    this(conversationId, agentName, call, state, Map.of());
  }

  private AuthzContextImpl(
      ConversationId conversationId,
      String agentName,
      ToolCall call,
      ConversationState state,
      Map<Key<?>, Object> deposits) {
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
    this.agentName = Objects.requireNonNull(agentName, "agentName must not be null");
    this.call = Objects.requireNonNull(call, "call must not be null");
    this.state = Objects.requireNonNull(state, "state must not be null");
    this.deposits = deposits;
  }

  @Override
  public ConversationId conversationId() {
    return conversationId;
  }

  @Override
  public String agentName() {
    return agentName;
  }

  @Override
  public ToolCall call() {
    return call;
  }

  @Override
  public ConversationState state() {
    return state;
  }

  @Override
  public <T> Optional<T> get(Key<T> key) {
    Objects.requireNonNull(key, "key must not be null");
    return Optional.ofNullable(deposits.get(key)).map(key.type()::cast);
  }

  @Override
  public <T> AuthzContext with(Key<T> key, T value) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(value, "value must not be null");
    Map<Key<?>, Object> extended = new LinkedHashMap<>(deposits);
    extended.put(key, value);
    return new AuthzContextImpl(conversationId, agentName, call, state, Map.copyOf(extended));
  }
}
