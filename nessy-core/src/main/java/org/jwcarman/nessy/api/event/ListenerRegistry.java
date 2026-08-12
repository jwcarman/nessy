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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jwcarman.nessy.api.conversation.ConversationId;

/**
 * One agent's whole delivery apparatus: the frozen set of build-time {@link ListenerRegistration}s
 * plus the one per-conversation dynamic view (design §17), with delivery built in.
 *
 * <p>Registration is polymorphic and type-bound: a registration for a supertype matches every
 * subtype emitted, so a registration at {@code ConversationEvent.class} sees every variant of that
 * sealed grammar, not just one. Delivery per emitted event is synchronous, in registration order:
 * conversation-local subscribers first — only for a {@link ConversationScoped} event, filtered to
 * its own {@link ConversationId} — then this frozen registry, in registration order (harness
 * registrations before the agent's own, since {@link #extendedWith} is expected to have already
 * merged them in that order). A throw from a sync registration, in either tier, propagates straight
 * out of {@link EventEmitter#emit} and stops delivery to everything after it — the veto is the
 * throw; an {@link ListenerRegistration#async async} registration never gets that power, since its
 * listener already runs off the emitting thread by the time delivery reaches it.
 *
 * <p>Built once, frozen at construction: nothing about the frozen chain is ever mutated afterward —
 * that is what "frozen at build" means; the only thing that ever changes at runtime is the live set
 * of per-conversation subscriptions each {@link #forConversation} view manages. An agent's registry
 * is built from its harness's via {@link #extendedWith}: {@code
 * harnessRegistry.extendedWith(agentRegistrations)} reads as "the harness's registrations, extended
 * with the agent's own."
 *
 * <p>Deliberately narrow: there is no general, agent-wide {@code subscribe} here. An agent-wide
 * observer is declared once, at build time, via {@code listen}/{@code listenAsync} on the builder;
 * the only thing left to attach at runtime is a single conversation's own traffic, through {@link
 * #forConversation}.
 */
public final class ListenerRegistry implements EventEmitter {

  private final List<ListenerRegistration> frozenChain;
  private final List<LocalRegistration<?>> localRegistrations = new CopyOnWriteArrayList<>();

  private ListenerRegistry(List<ListenerRegistration> frozenChain) {
    this.frozenChain = frozenChain;
  }

  /** A registry whose frozen tier is exactly {@code registrations}, in order. */
  public static ListenerRegistry of(List<ListenerRegistration> registrations) {
    return new ListenerRegistry(List.copyOf(registrations));
  }

  /**
   * A fresh registry whose frozen tier is this registry's own registrations followed by {@code
   * additional}, in order — the seeded-provider pattern applied to listeners (design §17).
   */
  public ListenerRegistry extendedWith(List<ListenerRegistration> additional) {
    List<ListenerRegistration> combined = new ArrayList<>(frozenChain.size() + additional.size());
    combined.addAll(frozenChain);
    combined.addAll(additional);
    return new ListenerRegistry(List.copyOf(combined));
  }

  @Override
  public void emit(Object event) {
    if (event instanceof ConversationScoped scoped) {
      for (LocalRegistration<?> registration : localRegistrations) {
        registration.deliverIfMatches(scoped.conversationId(), event);
      }
    }
    for (ListenerRegistration registration : frozenChain) {
      registration.deliverIfMatches(event);
    }
  }

  /**
   * A view over this registry already scoped to {@code conversationId}: {@link
   * ConversationEvents#subscribe} on the result only ever delivers events {@link
   * ConversationScoped} to that one id.
   */
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
   * A private, identity-equality class rather than a record: two registrations that look alike
   * (same conversation, type, and consumer) must still be independently closable — record equality
   * would make closing the second one silently remove the first.
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
