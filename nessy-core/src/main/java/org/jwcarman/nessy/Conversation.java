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
package org.jwcarman.nessy;

import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.event.ConversationEvents;
import org.jwcarman.nessy.api.event.ListenerRegistry;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.turn.TurnObserver;
import org.jwcarman.nessy.internal.ConversationLoop;

/**
 * One session. Sugar over {@code loop.run} — no semantics of its own.
 *
 * @param <I> this conversation's input vocabulary — what {@link #tell} accepts
 */
public final class Conversation<I> {

  private final ConversationLoop loop;
  private final ConversationId conversationId;
  private final ListenerRegistry events;
  private final InputRenderer<I> renderer;

  Conversation(
      ConversationLoop loop,
      ConversationId conversationId,
      ListenerRegistry events,
      InputRenderer<I> renderer) {
    this.loop = Objects.requireNonNull(loop, "loop must not be null");
    this.conversationId = Objects.requireNonNull(conversationId, "conversationId must not be null");
    this.events = Objects.requireNonNull(events, "events must not be null");
    this.renderer = Objects.requireNonNull(renderer, "renderer must not be null");
  }

  /**
   * Tells this conversation something from its input vocabulary, watched by no one ({@link
   * TurnObserver#noop()}). {@code input} is rendered into content blocks by this agent's {@link
   * InputRenderer} and carried into the loop as an {@code AgentTold} event — typing dissolves at
   * the wire, the sealed {@link ConversationEvent} grammar never changes shape.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped — a renderer is the
   *     application's own code, failing on its own thread, with the caller present to see it
   */
  public RunOutcome tell(I input) {
    return tell(input, TurnObserver.noop());
  }

  /**
   * Tells this conversation something from its input vocabulary, narrating this one segment to
   * {@code observer} as it happens — the model speaking and thinking, homework requested, the
   * gate's verdict, homework settled. {@code observer} sees only this call's segment, in {@code
   * TurnEvent} order.
   *
   * @throws IllegalArgumentException if the renderer produces a null or empty block list
   * @throws RuntimeException whatever the renderer itself throws, unwrapped — a renderer is the
   *     application's own code, failing on its own thread, with the caller present to see it
   */
  public RunOutcome tell(I input, TurnObserver observer) {
    Objects.requireNonNull(observer, "observer must not be null");
    ConversationEvent.AgentTold event = render(input);
    return loop.run(conversationId, event, observer);
  }

  /**
   * This conversation's one dynamic listening level (design §17): in-memory, per-handle,
   * non-durable, and already scoped to {@link #conversationId()} — nothing subscribed through the
   * result ever sees another conversation's traffic. Reach for a build-time {@code
   * AgentConfig#listen}/{@code listenAsync} declaration instead when a listener needs to watch
   * every conversation this agent ever runs, not just this one.
   */
  public ConversationEvents events() {
    return events.forConversation(conversationId);
  }

  /**
   * Renders {@code input} and fails loud, before the loop ever sees it, if the renderer did not
   * hold up its end: a null or empty block list is rejected here; anything the renderer itself
   * throws simply propagates, since it is the caller's own code running on the caller's own thread
   * — there is no session state to protect yet.
   */
  private ConversationEvent.AgentTold render(I input) {
    List<ContentBlock> blocks = renderer.render(input);
    if (blocks == null || blocks.isEmpty()) {
      throw new IllegalArgumentException(
          "InputRenderer produced no content blocks for input: " + input);
    }
    return new ConversationEvent.AgentTold(conversationId, blocks);
  }

  public ConversationId conversationId() {
    return conversationId;
  }
}
