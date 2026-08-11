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
package org.jwcarman.nessy.spi.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.turn.TurnEvent;
import org.jwcarman.nessy.spi.memory.ListMemory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ContextOverflowException;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;
import org.jwcarman.nessy.spi.model.ModelStream;

class ProviderModelCallExecutorTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState state = ConversationState.newConversation(id);
  private final ListMemory memory = new ListMemory();
  private final List<TurnEvent> observed = new ArrayList<>();

  @Test
  void mergesDeltasIntoOneSettledMessageAndYieldsOneFact() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ThinkingChunk("let me"),
            new ModelEvent.ThinkingChunk(" think"),
            new ModelEvent.TextChunk("hel"),
            new ModelEvent.TextChunk("lo"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, new Usage(5, 2, 0)));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content())
        .containsExactly(new ThinkingBlock("let me think", ""), new TextBlock("hello"));
    assertThat(fact.reason()).isEqualTo(StopReason.END_TURN);
  }

  @Test
  void narratesDeltasToTheObserverAsTheyArrive() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.TextChunk("hi"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));

    executor.execute(state, observed::add);

    assertThat(observed).isNotEmpty();
    assertThat(observed.getFirst()).isEqualTo(new TurnEvent.TextDelta("hi"));
  }

  @Test
  void narratesRequestedHomeworkMidStream() {
    ToolCall call = new ToolCall("call-1", "search", JsonNodeFactory.instance.objectNode());
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ToolUseEmitted(call),
            new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    assertThat(observed).contains(new TurnEvent.ToolCallRequested(call));
    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content()).hasSize(1); // the tool-use block rides the message
  }

  @Test
  void recallsTheContextFromMemoryNotFromState() {
    // remember something; the fake provider asserts the request context matches the recall
    memory.remember(id, Message.user(List.of(new TextBlock("hi"))));
    List<ModelRequest> seen = new ArrayList<>();
    ProviderModelCallExecutor executor =
        new ProviderModelCallExecutor(
            recordingProvider(seen, new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero())),
            settings(),
            ToolRegistry.of(),
            memory);

    executor.execute(state, observed::add);

    assertThat(seen).hasSize(1);
    assertThat(seen.getFirst().context().messages()).isEqualTo(memory.recall(id).messages());
  }

  @Test
  void contextOverflowBecomesTheFailureFactNotAnException() {
    ProviderModelCallExecutor executor =
        new ProviderModelCallExecutor(overflowingProvider(), settings(), ToolRegistry.of(), memory);

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelCallFailed fact =
        (ConversationEvent.ModelCallFailed) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.reason()).contains("too long");
  }

  @Test
  void aSignedThinkingBlockIsClosedSoALaterDeltaStartsAFreshOne() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ThinkingChunk("a"),
            new ModelEvent.ThinkingSigned("sig"),
            new ModelEvent.ThinkingChunk("b"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content())
        .containsExactly(new ThinkingBlock("a", "sig"), new ThinkingBlock("b", ""));
  }

  @Test
  void aSignatureWithNothingTrailingToSignIsANoOp() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.ThinkingSigned("sig"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content()).isEmpty();
  }

  @Test
  void redactedThinkingIsNarratedAndRidesTheMessageInPosition() {
    ProviderModelCallExecutor executor =
        executorStreaming(
            new ModelEvent.RedactedThinkingEmitted("opaque"),
            new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));

    Awaited<ConversationEvent> outcome = executor.execute(state, observed::add);

    assertThat(observed).contains(new TurnEvent.RedactedThinking("opaque"));
    ConversationEvent.ModelResponded fact =
        (ConversationEvent.ModelResponded) ((Awaited.Ready<ConversationEvent>) outcome).value();
    assertThat(fact.message().content()).containsExactly(new RedactedThinkingBlock("opaque"));
  }

  @Test
  void aStreamThatEndsWithoutTurnEndedThrows() {
    ProviderModelCallExecutor executor = executorStreaming(new ModelEvent.TextChunk("hi"));

    assertThatThrownBy(() -> executor.execute(state, observed::add))
        .isInstanceOf(IllegalStateException.class);
  }

  // --- fakes ---

  private ProviderModelCallExecutor executorStreaming(ModelEvent... events) {
    return new ProviderModelCallExecutor(
        recordingProvider(new ArrayList<>(), events), settings(), ToolRegistry.of(), memory);
  }

  private static ModelProvider recordingProvider(List<ModelRequest> seen, ModelEvent... events) {
    return new ModelProvider() {
      @Override
      public ModelStream stream(ModelRequest request) {
        seen.add(request);
        return scriptedStream(events);
      }

      @Override
      public Set<Capability> capabilities() {
        return Set.of();
      }
    };
  }

  private static ModelProvider overflowingProvider() {
    return new ModelProvider() {
      @Override
      public ModelStream stream(ModelRequest request) {
        throw new ContextOverflowException("too long");
      }

      @Override
      public Set<Capability> capabilities() {
        return Set.of();
      }
    };
  }

  private static ModelStream scriptedStream(ModelEvent... events) {
    Iterator<ModelEvent> iterator = List.of(events).iterator();
    return new ModelStream() {
      @Override
      public Iterator<ModelEvent> iterator() {
        return iterator;
      }

      @Override
      public void close() {}
    };
  }

  private static ModelSettings settings() {
    return new ModelSettings("fake-model", "be helpful", 1024, Set.of(), null);
  }
}
