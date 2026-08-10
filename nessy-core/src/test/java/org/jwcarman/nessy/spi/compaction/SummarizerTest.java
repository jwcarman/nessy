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
package org.jwcarman.nessy.spi.compaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.compaction.CompactionPolicy;
import org.jwcarman.nessy.api.compaction.CompactionTrigger;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.session.Usage;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Summarizer#usingProvider}, the production summarizer, over a hand-rolled fake provider.
 */
class SummarizerTest {

  private static final ModelSettings CONFIG =
      new ModelSettings("fake-model", "be helpful", 1_024, Set.of(), null);

  private static CompactionPolicy policy(int summaryMaxTokens) {
    return new CompactionPolicy(
        CompactionTrigger.atTokens(1), 0, summaryMaxTokens, "Summarize the conversation so far.");
  }

  /** Replays one scripted turn per call and records every request it was handed. */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    FakeProvider(List<ModelEvent> turn) {
      turns.add(turn);
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {}
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  @Nested
  class Request_shape {

    @Test
    void the_head_and_instructions_become_a_tool_free_request() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())));
      Summarizer summarizer = Summarizer.usingProvider(provider, CONFIG);
      Context head = Context.of(List.of(Message.user("hi")));
      CompactionPolicy policy = policy(500);

      summarizer.summarize(head, policy);

      ModelRequest request = provider.requests().getFirst();
      assertThat(request.tools()).isEmpty();
      assertThat(request.maxTokens()).isEqualTo(500);
      assertThat(request.context().messages())
          .containsExactly(Message.user("hi"), Message.user(policy.instructions()));
    }
  }

  @Nested
  class The_summary {

    @Test
    void the_summary_carries_text_and_spend() {
      Usage usage = new Usage(100, 20, 0);
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("the "),
                  new ModelEvent.TextChunk("gist"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, usage)));
      Summarizer summarizer = Summarizer.usingProvider(provider, CONFIG);

      Summarizer.Summary summary =
          summarizer.summarize(Context.of(List.of(Message.user("hi"))), policy(500));

      assertThat(summary.text()).isEqualTo("the gist");
      assertThat(summary.usage()).isEqualTo(usage);
    }

    @Test
    void a_blank_summary_is_a_failure() {
      FakeProvider provider =
          new FakeProvider(
              List.of(
                  new ModelEvent.TextChunk("   "),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())));
      Summarizer summarizer = Summarizer.usingProvider(provider, CONFIG);
      Context head = Context.of(List.of(Message.user("hi")));
      CompactionPolicy policy = policy(500);

      assertThatThrownBy(() -> summarizer.summarize(head, policy))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("no text");
    }
  }
}
