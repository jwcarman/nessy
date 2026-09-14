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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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

  private static final ModelId MODEL_ID = ModelId.of("us.anthropic.claude-haiku-4-5-20251001-v1:0");

  private static ModelRequest request() {
    return new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of());
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

      var stream = provider.model(MODEL_ID).stream(request());

      assertThat(stream).isSameAs(response);
      assertThat(capturedArgs[0]).isInstanceOf(ConverseStreamRequest.class);
      var captured = (ConverseStreamRequest) capturedArgs[0];
      assertThat(captured.modelId()).isEqualTo("us.anthropic.claude-haiku-4-5-20251001-v1:0");
      assertThat(captured.inferenceConfig().maxTokens()).isEqualTo(1024);
    }

    /**
     * {@link BedrockClient#close()} defaults to a no-op precisely so a hand-rolled fake like {@link
     * #fakeClient} never has to implement it — this pins that the default itself does nothing
     * rather than, say, throwing {@code UnsupportedOperationException}.
     */
    @Test
    void closing_a_provider_over_a_client_with_no_close_override_does_nothing_special() {
      var provider = new BedrockModelProvider(fakeClient(new Object[1], null));

      assertThatCode(provider::close).doesNotThrowAnyException();
    }
  }

  @Nested
  class Configuration {

    @Test
    void rejects_build_with_neither_a_region_nor_a_client() {
      var config = new BedrockProviderConfig();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("region")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void a_region_alone_is_enough_to_build() {
      try (var provider = new BedrockProviderConfig().region(Region.US_EAST_1).build()) {
        assertThat(provider).isNotNull();
      }
    }

    @Test
    void from_env_fails_clearly_when_neither_variable_is_set_naming_both() {
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");

      var config = new BedrockProviderConfig().fromEnv();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("AWS_DEFAULT_REGION");
    }

    @Test
    void an_explicit_region_set_after_from_env_still_builds_without_needing_the_environment() {
      try (var provider = new BedrockProviderConfig().fromEnv().region(Region.US_WEST_2).build()) {
        assertThat(provider).isNotNull();
      }
    }

    @Test
    void an_explicit_credentials_provider_overrides_the_default_chain() {
      var credentials =
          StaticCredentialsProvider.create(AwsBasicCredentials.create("key", "secret"));

      try (var provider =
          new BedrockProviderConfig()
              .region(Region.US_EAST_1)
              .credentialsProvider(credentials)
              .build()) {
        assertThat(provider).isNotNull();
      }
    }

    @Test
    void a_preconfigured_sdk_client_bypasses_the_region_requirement() {
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of());

      try (var provider = new BedrockProviderConfig().client(fake).build()) {
        assertThat(provider).isNotNull();
      }
    }
  }

  /**
   * Drives the two public static factories directly — {@link BedrockModelProvider#create} and
   * {@link BedrockModelProvider#fromEnv} — rather than the package-private {@link
   * BedrockProviderConfig} the {@link Configuration} tests above reach into. Before this class,
   * {@code fromEnv()} had zero offline coverage at all — only {@code @Tag("live")} and a
   * condition-gated Boot bean exercised it. Spec §5 requires {@code fromEnv()} equal {@code
   * create(config -> config.fromEnv())} in behavior; this pins that offline by driving both through
   * the same unset-region failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> BedrockModelProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");

      assertThatThrownBy(BedrockModelProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("AWS_DEFAULT_REGION");
      assertThatThrownBy(() -> BedrockModelProvider.create(BedrockProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("AWS_DEFAULT_REGION");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      try (var provider = BedrockModelProvider.create(c -> c.region(Region.US_EAST_1))) {
        assertThat(provider.name()).isEqualTo("Bedrock");
      }
    }
  }

  @Nested
  class Capabilities {

    // REMOVED IN THE CUTOVER (2026-08-30): pinned the capability set advertised through
    // Model#capabilities(), which the new SPI does not have.
  }

  @Nested
  class Name {

    @Test
    void reports_bedrock() {
      try (var provider = new BedrockProviderConfig().region(Region.US_EAST_1).build()) {
        assertThat(provider.name()).isEqualTo("Bedrock");
      }
    }
  }

  /**
   * Exercises the split itself, offline: {@link BedrockModelProvider#model(String)} hands out
   * independent handles that share the gateway's client, each honest about its own id, with a blank
   * id rejected before any handle is created.
   */
  @Nested
  class Gateway {

    @Test
    void two_model_calls_on_one_provider_yield_independent_handles_sharing_the_client() {
      var capturedArgs = new Object[1];
      var response = new BedrockStream(List.of(), () -> {});
      var provider = new BedrockModelProvider(fakeClient(capturedArgs, response));

      Model haiku = provider.model(MODEL_ID);
      Model opus = provider.model(ModelId.of("us.anthropic.claude-opus-5-20260101-v1:0"));

      assertThat(haiku.id()).isEqualTo(MODEL_ID);
      assertThat(opus.id()).isEqualTo(ModelId.of("us.anthropic.claude-opus-5-20260101-v1:0"));
      assertThat(haiku).isNotSameAs(opus);

      haiku.stream(request());
      assertThat(((ConverseStreamRequest) capturedArgs[0]).modelId()).isEqualTo(MODEL_ID.value());
      opus.stream(request());
      assertThat(((ConverseStreamRequest) capturedArgs[0]).modelId())
          .isEqualTo("us.anthropic.claude-opus-5-20260101-v1:0");
    }

    /**
     * {@link BedrockModelProvider#model(ModelId)} itself performs no blankness check of its own
     * (see its source: only {@link java.util.Objects#requireNonNull}) — a blank id can never reach
     * it in the first place because {@link ModelId}'s own compact constructor rejects one first.
     * This pins that {@code ModelId.of(" ")} is where the rejection actually happens, so a future
     * relaxation of {@link ModelId}'s own guard does not silently let a blank id through this
     * gateway unnoticed.
     */
    @Test
    void a_blank_model_id_is_rejected_by_model_id_itself_before_it_ever_reaches_the_provider() {
      assertThatThrownBy(() -> ModelId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_model_id_is_rejected() {
      var provider = new BedrockModelProvider(fakeClient(new Object[1], null));

      assertThatThrownBy(() -> provider.model(null)).isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * Pins the async-to-blocking bridge ({@link BedrockProviderConfig#wrap}) end to end through the
   * public {@code .client(BedrockRuntimeAsyncClient)} escape hatch — {@link
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

      try (var provider = new BedrockProviderConfig().client(fake).build()) {
        var collected = new ArrayList<ModelEvent>();
        try (var stream = provider.model(MODEL_ID).stream(request())) {
          stream.forEach(collected::add);
        }

        assertThat(collected)
            .containsExactly(
                new ModelEvent.TextChunk("hello"),
                new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported()));
      }
    }

    @Test
    void a_mid_stream_failure_delivers_its_preceding_events_first_then_throws_the_real_cause() {
      var cause = new IllegalStateException("ThrottlingException: rate exceeded");
      var fake =
          ScriptedBedrockRuntimeAsyncClient.failingWith(
              List.of(textDelta("a"), textDelta("b")), new CompletionException(cause));

      try (var provider = new BedrockProviderConfig().client(fake).build()) {
        var collected = new ArrayList<ModelEvent>();
        try (var stream = provider.model(MODEL_ID).stream(request())) {
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

      try (var provider = new BedrockProviderConfig().client(fake).build();
          var stream = provider.model(MODEL_ID).stream(request())) {
        assertThatThrownBy(() -> stream.forEach(event -> {}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("messageStop");
      }
    }

    @Test
    void closing_the_stream_cancels_the_underlying_future() {
      var fake = ScriptedBedrockRuntimeAsyncClient.leavingPendingAfter(List.of(textDelta("hi")));

      try (var provider = new BedrockProviderConfig().client(fake).build()) {
        var stream = provider.model(MODEL_ID).stream(request());
        stream.close();

        assertThat(fake.lastFuture().isCancelled()).isTrue();
      }
    }

    @Test
    void a_failure_before_any_event_throws_from_stream_itself_not_from_iteration() {
      var failure = new IllegalStateException("ThrottlingException: too many requests");
      var fake = ScriptedBedrockRuntimeAsyncClient.failingWith(List.of(), failure);
      var request = request();

      try (var provider = new BedrockProviderConfig().client(fake).build()) {
        // The failure must surface from stream() itself — this is what lets
        // RetryingModel retry it, exactly as it does for every synchronous-SDK sibling
        // provider's opening failure. A stream() that returned successfully here (with the
        // failure only surfacing later on the first hasNext()/next()) would defeat that retry.
        assertThatThrownBy(() -> provider.model(MODEL_ID).stream(request)).isSameAs(failure);
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
    void a_real_preconfigured_client_is_accepted_and_left_open_by_the_provider() {
      BedrockRuntimeAsyncClient sdkClient =
          BedrockRuntimeAsyncClient.builder().region(Region.US_EAST_1).build();

      try (var provider = new BedrockProviderConfig().client(sdkClient).build()) {
        assertThat(provider.name()).isEqualTo("Bedrock");
      }
      // provider.close() (via try-with-resources) must NOT delegate to sdkClient.close() — the
      // close-ownership rider: a caller-supplied client is the caller's to close, not the
      // provider's. That branch is pinned precisely, with a fake that records close calls, by
      // CloseOwnership below; this test's own job is only to confirm the escape hatch itself
      // still builds and reports correctly. sdkClient is closed here, once, to avoid leaking its
      // real Netty resources for the rest of the test run.
      sdkClient.close();
    }
  }

  /**
   * Pins close ownership: {@link BedrockModelProvider#close()} must close only a {@code
   * BedrockRuntimeAsyncClient} this provider built itself, never one supplied through {@link
   * BedrockProviderConfig#client(BedrockRuntimeAsyncClient)}. {@link BedrockProviderConfig#wrap} is
   * package-private specifically so this ownership branch is directly testable against {@link
   * ScriptedBedrockRuntimeAsyncClient}'s close-tracking, without needing a real, network-capable
   * SDK client on either side of the assertion.
   */
  @Nested
  class CloseOwnership {

    @Test
    void an_internally_built_client_is_closed_when_the_provider_is_closed() {
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of());
      var provider = new BedrockModelProvider(BedrockProviderConfig.wrap(fake, true));

      provider.close();

      assertThat(fake.isClosed()).isTrue();
    }

    @Test
    void a_caller_supplied_client_is_not_closed_when_the_provider_is_closed() {
      var fake = ScriptedBedrockRuntimeAsyncClient.succeedingWith(List.of());
      var provider = new BedrockModelProvider(BedrockProviderConfig.wrap(fake, false));

      provider.close();

      assertThat(fake.isClosed()).isFalse();
    }
  }
}
