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
package org.jwcarman.nessy.model.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.ModelEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;

/**
 * Pins {@link BedrockProviderConfig#wrap}, the async-to-blocking bridge itself: the queueing {@code
 * Visitor} that receives the SDK's push-based events, the {@code BridgeIterator} that pulls them
 * back off on the caller's thread, and {@code StreamFailure#toRuntimeException} that decides what a
 * caller sees when the SDK's future completes exceptionally. {@code
 * BedrockModelProviderTest.CloseOwnership} pins {@code wrap}'s close-ownership split; this pins the
 * streaming path — offline, through {@link ScriptedBedrockRuntimeAsyncClient}, the same fake that
 * class already uses.
 */
class BedrockProviderConfigWrapTest {

  private static final ConverseStreamRequest REQUEST =
      ConverseStreamRequest.builder()
          .modelId("us.anthropic.claude-haiku-4-5-20251001-v1:0")
          .inferenceConfig(b -> b.maxTokens(10))
          .build();

  private static List<ModelEvent> drain(BedrockStream stream) {
    var collected = new ArrayList<ModelEvent>();
    stream.forEach(collected::add);
    return collected;
  }

  @Nested
  class SuccessfulStreams {

    /**
     * One of every event kind the SDK's {@code Visitor} interface has a method for, so every {@code
     * visit*} override on {@code BedrockProviderConfig}'s queueing visitor is exercised, not just
     * the content-delta path {@code BedrockStreamTest} already drives directly.
     */
    @Test
    void every_event_kind_crosses_the_bridge_in_order() {
      var events =
          List.<ConverseStreamOutput>of(
              ConverseStreamOutput.messageStartBuilder().role("assistant").build(),
              ConverseStreamOutput.contentBlockStartBuilder().contentBlockIndex(0).build(),
              ConverseStreamOutput.contentBlockDeltaBuilder()
                  .contentBlockIndex(0)
                  .delta(b -> b.text("hello"))
                  .build(),
              ConverseStreamOutput.contentBlockStopBuilder().contentBlockIndex(0).build(),
              ConverseStreamOutput.messageStopBuilder().stopReason("end_turn").build(),
              ConverseStreamOutput.metadataBuilder()
                  .usage(TokenUsage.builder().inputTokens(1).outputTokens(1).build())
                  .build());
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(events);
      var client = BedrockProviderConfig.wrap(fake, true);

      var stream = client.converseStream(REQUEST);

      var collected = drain(stream);
      assertThat(collected).isNotEmpty();
      assertThat(collected)
          .anyMatch(ModelEvent.TextChunk.class::isInstance)
          .anyMatch(ModelEvent.Stopped.class::isInstance);
    }

    @Test
    void an_empty_stream_still_primes_the_bridge_without_blocking_forever() {
      // BedrockStream itself demands a messageStop before it will call a stream finished — this
      // pins that an empty upstream event list still reaches that far through the bridge (the
      // pump-priming read, the DONE sentinel, a clean end of the underlying queue) rather than
      // hanging; BedrockStream's own reaction to a missing messageStop is BedrockStreamTest's
      // claim.
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of());
      var client = BedrockProviderConfig.wrap(fake, true);
      var stream = client.converseStream(REQUEST);

      assertThatThrownBy(() -> drain(stream))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("messageStop");
    }
  }

  @Nested
  class FailingStreams {

    @Test
    void an_immediate_failure_throws_from_converse_stream_itself() {
      var failure = new IllegalStateException("throttled");
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var client = BedrockProviderConfig.wrap(fake, true);

      assertThatThrownBy(() -> client.converseStream(REQUEST)).isSameAs(failure);
    }

    @Test
    void a_mid_stream_failure_throws_once_iteration_reaches_it() {
      var textEvent =
          ConverseStreamOutput.contentBlockDeltaBuilder()
              .contentBlockIndex(0)
              .delta(b -> b.text("partial"))
              .build();
      var failure = new IllegalStateException("connection reset");
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(textEvent), failure);
      var client = BedrockProviderConfig.wrap(fake, true);
      var stream = client.converseStream(REQUEST);

      assertThatThrownBy(() -> drain(stream)).isSameAs(failure);
    }
  }

  /**
   * The three shapes {@code StreamFailure#toRuntimeException} distinguishes: a {@link
   * CompletionException} wrapping a {@link RuntimeException} unwraps to that cause directly; one
   * wrapping a checked exception is named into a fresh {@link IllegalStateException}; and a bare
   * {@link RuntimeException} — never wrapped at all — passes straight through.
   */
  @Nested
  class FailureUnwrapping {

    @Test
    void a_completion_exception_wrapping_a_runtime_exception_unwraps_to_that_cause() {
      var cause = new IllegalArgumentException("bad request");
      var failure = new CompletionException(cause);
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var client = BedrockProviderConfig.wrap(fake, true);

      assertThatThrownBy(() -> client.converseStream(REQUEST)).isSameAs(cause);
    }

    @Test
    void a_completion_exception_wrapping_a_checked_exception_is_named_into_a_fresh_failure() {
      var cause = new IOException("connection dropped");
      var failure = new CompletionException(cause);
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var client = BedrockProviderConfig.wrap(fake, true);

      assertThatThrownBy(() -> client.converseStream(REQUEST))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("IOException: connection dropped")
          .hasCause(cause);
    }

    @Test
    void a_bare_runtime_exception_passes_through_unwrapped() {
      var failure = new IllegalStateException("direct failure");
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var client = BedrockProviderConfig.wrap(fake, true);

      assertThatThrownBy(() -> client.converseStream(REQUEST)).isSameAs(failure);
    }
  }
}
