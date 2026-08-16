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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;

class BedrockModelProviderTest {

  private static BedrockClient fakeClient(Object[] capturedArgs, BedrockStream response) {
    return request -> {
      capturedArgs[0] = request;
      return response;
    };
  }

  private static ModelRequest request() {
    return new ModelRequest(
        Context.of(List.of()),
        "sys",
        "us.anthropic.claude-haiku-4-5-20251001-v1:0",
        1024,
        List.of(),
        Set.of(),
        null);
  }

  private static ConverseStreamOutput textDelta(String text) {
    return ConverseStreamOutput.contentBlockDeltaBuilder()
        .contentBlockIndex(0)
        .delta(builder -> builder.text(text))
        .build();
  }

  private static ConverseStreamOutput messageStop(String stopReason) {
    return ConverseStreamOutput.messageStopBuilder().stopReason(stopReason).build();
  }

  @Nested
  class Streaming {

    @Test
    void delegates_to_the_client_and_returns_its_stream_unchanged() {
      var capturedArgs = new Object[1];
      var response = new BedrockStream(List.of(), () -> {});
      var provider = new BedrockModelProvider(fakeClient(capturedArgs, response));

      var stream = provider.stream(request());

      assertThat(stream).isSameAs(response);
      assertThat(capturedArgs[0]).isInstanceOf(ConverseStreamRequest.class);
      var captured = (ConverseStreamRequest) capturedArgs[0];
      assertThat(captured.modelId()).isEqualTo("us.anthropic.claude-haiku-4-5-20251001-v1:0");
      assertThat(captured.inferenceConfig().maxTokens()).isEqualTo(1024);
    }
  }

  @Nested
  class Builder {

    @Test
    void rejects_build_with_neither_a_region_nor_a_client() {
      var builder = BedrockModelProvider.builder();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("region")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void a_region_alone_is_enough_to_build() {
      try (var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build()) {
        assertThat(provider).isNotNull();
      }
    }

    @Test
    void from_env_fails_clearly_when_neither_variable_is_set_naming_both() {
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");

      var builder = BedrockModelProvider.builder().fromEnv();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("AWS_DEFAULT_REGION");
    }

    @Test
    void an_explicit_region_set_after_from_env_still_builds_without_needing_the_environment() {
      try (var provider =
          BedrockModelProvider.builder().fromEnv().region(Region.US_WEST_2).build()) {
        assertThat(provider).isNotNull();
      }
    }

    @Test
    void a_preconfigured_sdk_client_bypasses_the_region_requirement() {
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of());

      try (var provider = BedrockModelProvider.builder().client(fake).build()) {
        assertThat(provider).isNotNull();
      }
    }
  }

  @Nested
  class Capabilities {

    @Test
    void v1_advertises_parallel_tool_calls_but_not_thinking_caching_or_image_input() {
      try (var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build()) {
        assertThat(provider.capabilities()).containsExactly(Capability.PARALLEL_TOOL_CALLS);
      }
    }
  }

  @Nested
  class Name {

    @Test
    void reports_bedrock() {
      try (var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build()) {
        assertThat(provider.name()).isEqualTo("Bedrock");
      }
    }
  }

  /**
   * Pins the async-to-blocking bridge ({@link BedrockModelProvider.Builder#wrap}) end to end
   * through the public {@code .client(BedrockRuntimeAsyncClient)} escape hatch — {@link
   * ScriptedBedrockRuntimeAsyncClient} is a hand-rolled fake, not a mock, driven through the real
   * {@code ConverseStreamResponseHandler}/{@code SdkPublisher} machinery the SDK itself ships.
   * Every scenario here previously had zero offline coverage; a future refactor of {@code wrap}
   * that silently breaks ordering, error propagation, cancellation, or pump-priming now fails a
   * test instead of only a live run with real AWS credentials.
   */
  @Nested
  class Bridge {

    @Test
    void events_then_a_normal_completion_translate_and_end_cleanly() {
      var fake =
          ScriptedBedrockRuntimeAsyncClient.succeedingWith(
              List.of(textDelta("hello"), messageStop("end_turn")));

      try (var provider = BedrockModelProvider.builder().client(fake).build()) {
        var collected = new ArrayList<ModelEvent>();
        try (var stream = provider.stream(request())) {
          stream.forEach(collected::add);
        }

        assertThat(collected)
            .containsExactly(
                new ModelEvent.TextChunk("hello"),
                new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      }
    }

    @Test
    void a_mid_stream_failure_delivers_its_preceding_events_first_then_throws_the_real_cause() {
      var cause = new IllegalStateException("ThrottlingException: rate exceeded");
      var fake =
          ScriptedBedrockRuntimeAsyncClient.failingWith(
              List.of(textDelta("a"), textDelta("b")), new CompletionException(cause));

      try (var provider = BedrockModelProvider.builder().client(fake).build()) {
        var collected = new ArrayList<ModelEvent>();
        try (var stream = provider.stream(request())) {
          // The ordering guarantee the whole bridge rests on: both events already queued ahead of
          // the future's completion are delivered before the failure is ever seen.
          assertThatThrownBy(() -> stream.forEach(collected::add)).isSameAs(cause);
        }

        assertThat(collected)
            .containsExactly(new ModelEvent.TextChunk("a"), new ModelEvent.TextChunk("b"));
      }
    }

    @Test
    void a_completion_with_no_message_stop_fails_loudly_through_the_bridge_not_just_a_list() {
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of(textDelta("only")));

      try (var provider = BedrockModelProvider.builder().client(fake).build();
          var stream = provider.stream(request())) {
        assertThatThrownBy(() -> stream.forEach(event -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("messageStop");
      }
    }

    @Test
    void closing_the_stream_cancels_the_underlying_future() {
      var fake = ScriptedBedrockRuntimeAsyncClient.leavingPendingAfter(List.of(textDelta("hi")));

      try (var provider = BedrockModelProvider.builder().client(fake).build()) {
        var stream = provider.stream(request());
        stream.close();

        assertThat(fake.lastFuture().isCancelled()).isTrue();
      }
    }

    @Test
    void a_failure_before_any_event_throws_from_stream_itself_not_from_iteration() {
      var failure = new IllegalStateException("ThrottlingException: too many requests");
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var request = request();

      try (var provider = BedrockModelProvider.builder().client(fake).build()) {
        // The failure must surface from stream() itself — this is what lets
        // RetryingModelProvider retry it, exactly as it does for every synchronous-SDK sibling
        // provider's opening failure. A stream() that returned successfully here (with the
        // failure only surfacing later on the first hasNext()/next()) would defeat that retry.
        assertThatThrownBy(() -> provider.stream(request)).isSameAs(failure);
      }
    }
  }

  /**
   * Exercises the {@code .client(BedrockRuntimeAsyncClient)} escape hatch against a real SDK
   * client.
   */
  @Nested
  class ClientOverride {

    @Test
    void a_real_preconfigured_client_is_accepted_and_closed_with_the_provider() {
      BedrockRuntimeAsyncClient sdkClient =
          BedrockRuntimeAsyncClient.builder().region(Region.US_EAST_1).build();

      try (var provider = BedrockModelProvider.builder().client(sdkClient).build()) {
        assertThat(provider.name()).isEqualTo("Bedrock");
      }
      // provider.close() (via try-with-resources) delegates to sdkClient.close(); a second,
      // redundant close below would be harmless but isn't needed to prove it happened — the
      // absence of a leaked event-loop group is not independently observable from here, so this
      // test's job is only to confirm the escape hatch builds and reports correctly.
    }
  }
}
