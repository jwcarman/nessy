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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * The default {@link EventSpine}: conversation-local subscribers see an event first (if it is
 * {@link ConversationScoped} to their conversation), then the frozen chain, in order — both tiers
 * delivered synchronously, on the emitting thread. See {@link EventSpines#of} for the full
 * contract.
 */
final class SynchronousEventSpine implements EventSpine {

  private final List<ListenerDeclaration> frozenChain;
  private final List<LocalRegistration<?>> localRegistrations = new CopyOnWriteArrayList<>();

  SynchronousEventSpine(List<ListenerDeclaration> frozenChain) {
    this.frozenChain = frozenChain;
  }

  @Override
  public void emit(Object event) {
    if (event instanceof ConversationScoped scoped) {
      for (LocalRegistration<?> registration : localRegistrations) {
        registration.deliverIfMatches(scoped.conversationId(), event);
      }
    }
    for (ListenerDeclaration declaration : frozenChain) {
      declaration.deliverIfMatches(event);
    }
  }

  @Override
  public ConversationEvents forConversation(ConversationId conversationId) {
    return new LocalView(conversationId);
  }

  private final class LocalView implements ConversationEvents {

    private final ConversationId conversationId;

    LocalView(ConversationId conversationId) {
      this.conversationId = conversationId;
    }

    @Override
    public <T> Subscription subscribe(Class<T> type, Consumer<T> listener) {
      LocalRegistration<T> registration = new LocalRegistration<>(conversationId, type, listener);
      localRegistrations.add(registration);
      return () -> localRegistrations.remove(registration);
    }
  }

  /**
   * A private, identity-equality class rather than a record — see the retired {@code
   * SynchronousEventHub}'s javadoc for why: two registrations that look alike (same conversation,
   * type, and consumer) must still be independently closable.
   */
  private static final class LocalRegistration<E> {

    private final ConversationId conversationId;
    private final Class<E> type;
    private final Consumer<E> listener;

    LocalRegistration(ConversationId conversationId, Class<E> type, Consumer<E> listener) {
      this.conversationId = conversationId;
      this.type = type;
      this.listener = listener;
    }

    void deliverIfMatches(ConversationId eventConversationId, Object event) {
      if (conversationId.equals(eventConversationId) && type.isInstance(event)) {
        listener.accept(type.cast(event));
      }
    }
  }
}
