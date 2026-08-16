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
package org.jwcarman.nessy.model.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.model.anthropic.AnthropicRequests.ThinkingConfig;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * Pins spec §4.5's "consecutive USER messages are legal" ruling against the real wire mapping, not
 * just the reducer: a failed call followed by a recovering tell leaves {@code user(A), user(B)} in
 * history (design §3's documented recovery shape), and {@link AnthropicRequests#toParams} — the
 * mapping a live 400 would come from if the shape were actually wire-illegal — accepts that full
 * message list without throwing. Scout produces this same shape live daily; this is the offline
 * regression that keeps it pinned rather than merely hoped for.
 */
class RecoveryShapeTest {

  private static final ThinkingConfig THINKING_DISABLED = new ThinkingConfig(false, 0);

  @Test
  void the_recovery_shape_after_a_failed_call_is_wire_legal() {
    FailFirstThenDelegate provider =
        new FailFirstThenDelegate(new RuntimeException("403: no credits"));
    Agent<String> agent =
        Nessy.harness(h -> h.provider(provider))
            .agent(a -> a.name("recovery-shape").model("claude-sonnet"));
    Conversation<String> conversation = agent.converse();

    RunOutcome first = conversation.tell("hello");
    RunOutcome second = conversation.tell("hello again");

    assertThat(first.state().status()).isEqualTo(ConversationStatus.FAILED);
    assertThat(second.state().status()).isEqualTo(ConversationStatus.COMPLETE);

    Context context = agent.contextFor(conversation.conversationId());
    List<Message> userMessages =
        context.messages().stream().filter(m -> m.role() == Role.USER).toList();
    assertThat(userMessages).hasSizeGreaterThanOrEqualTo(2);
    assertThat(userMessages.get(0).content()).isNotEmpty();
    assertThat(userMessages.get(1).content()).isNotEmpty();

    ModelRequest request =
        new ModelRequest(context, "be helpful", "claude-sonnet", 1024, List.of(), Set.of(), null);

    assertThatCode(() -> AnthropicRequests.toParams(request, THINKING_DISABLED))
        .doesNotThrowAnyException();
  }

  /** A provider that throws on its first call, then answers normally on every later call. */
  private static final class FailFirstThenDelegate implements ModelProvider {

    private final RuntimeException firstCallFailure;
    private boolean calledOnce;

    FailFirstThenDelegate(RuntimeException firstCallFailure) {
      this.firstCallFailure = firstCallFailure;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      if (!calledOnce) {
        calledOnce = true;
        throw firstCallFailure;
      }
      return scriptedStream(
          new ModelEvent.TextChunk("hi"),
          new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    private static ModelStream scriptedStream(ModelEvent... events) {
      Iterator<ModelEvent> iterator = List.of(events).iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return iterator;
        }

        @Override
        public void close() {
          // scripted stream owns no resource; nothing to release.
        }
      };
    }
  }
}
