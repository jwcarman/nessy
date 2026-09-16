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
package org.jwcarman.nessy.inference.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.services.blocking.MessageService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.spi.inference.Failure;
import org.jwcarman.nessy.spi.inference.InferenceContext;
import org.jwcarman.nessy.spi.inference.InferenceOptions;
import org.jwcarman.nessy.spi.inference.InferenceRequest;
import org.jwcarman.nessy.spi.inference.InferenceResult;

class AnthropicInferenceProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  /** The SDK's error builders require a body; none of these classifications reads one. */
  private static JsonValue emptyBody() {
    return JsonValue.from(Map.of());
  }

  /**
   * {@link AnthropicClient} carries several abstract accessors and its {@link MessageService}
   * several more, none of which {@code infer} touches except {@code messages().create(...)}. A JDK
   * dynamic proxy -- not a mocking library, just {@link Proxy#newProxyInstance} -- answers only
   * that one call path and throws {@link UnsupportedOperationException} for everything else.
   *
   * <p>{@code answer} receives the params the provider built and returns the message to hand back,
   * or throws -- which is how the classification tests reach {@code infer}'s catch without a
   * network.
   */
  private static AnthropicClient fakeClient(Function<MessageCreateParams, Message> answer) {
    var messageService =
        (MessageService)
            Proxy.newProxyInstance(
                MessageService.class.getClassLoader(),
                new Class<?>[] {MessageService.class},
                (proxy, method, args) -> {
                  // The provider calls the SDK's one-arg create(params) default method, but a JDK
                  // proxy intercepts every interface method call itself rather than letting the
                  // default method's body run and delegate to the two-arg abstract overload -- so
                  // this must match on name alone, not arity.
                  if ("create".equals(method.getName())) {
                    return answer.apply((MessageCreateParams) args[0]);
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (AnthropicClient)
        Proxy.newProxyInstance(
            AnthropicClient.class.getClassLoader(),
            new Class<?>[] {AnthropicClient.class},
            (proxy, method, args) -> {
              if ("messages".equals(method.getName())) {
                return messageService;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** A reply carrying these content blocks and nothing else of interest. */
  private static Message.Builder reply() {
    return Message.builder()
        .id("msg_test")
        .model("claude-sonnet")
        .stopReason(Optional.empty())
        .stopSequence(Optional.empty())
        .container(Optional.empty())
        .stopDetails(Optional.empty())
        .usage(
            // Every field is required by the SDK's builder and none is read by this adapter.
            Usage.builder()
                .inputTokens(1L)
                .outputTokens(1L)
                .cacheCreation(Optional.empty())
                .cacheCreationInputTokens(Optional.empty())
                .cacheReadInputTokens(Optional.empty())
                .inferenceGeo(Optional.empty())
                .outputTokensDetails(Optional.empty())
                .serverToolUse(Optional.empty())
                .serviceTier(Optional.empty())
                .build());
  }

  /** Citations are required by the SDK's builder and are not something this adapter reads. */
  private static TextBlock text(String value) {
    return TextBlock.builder().text(value).citations(Optional.empty()).build();
  }

  /**
   * {@code caller} distinguishes a call the model made from one a server-side tool made on its
   * behalf. Only the direct kind is ever ours to answer, and it is required by the builder.
   */
  private static ToolUseBlock use(String id, String name, java.util.Map<String, Object> input) {
    return ToolUseBlock.builder()
        .id(id)
        .name(name)
        .input(JsonValue.from(input))
        .caller(DirectCaller.builder().build())
        .build();
  }

  private static final InferenceRequest REQUEST =
      new InferenceRequest(
          new SystemPrompt("you are a helpful assistant"),
          InferenceContext.of(
              List.of(
                  new Turn(
                      new TurnId(1),
                      new Observation(new Seq(1), List.of(new Block.Text("hello"))),
                      List.of(),
                      null,
                      0))),
          List.of(),
          new InferenceOptions("claude-sonnet", 1024));

  private static InferenceResult inferAnswering(Message message) {
    return new AnthropicProviderConfig()
        .client(fakeClient(params -> message))
        .build()
        .infer(REQUEST);
  }

  private static Failure inferFailing(RuntimeException failure) {
    InferenceResult result =
        new AnthropicProviderConfig()
            .client(
                fakeClient(
                    params -> {
                      throw failure;
                    }))
            .build()
            .infer(REQUEST);
    assertThat(result).isInstanceOf(InferenceResult.Fault.class);
    return ((InferenceResult.Fault) result).failure();
  }

  @Nested
  class WhatComesBack {

    /** A reply of whitespace only is an empty answer, and an empty answer is a fault. */
    @Test
    void an_empty_answer_is_a_fault() {
      InferenceResult result = inferAnswering(reply().addContent(text("  ")).build());

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> assertThat(fault.failure().reason()).contains("empty"));
    }

    @Test
    void plain_prose_is_an_answer() {
      InferenceResult result = inferAnswering(reply().addContent(text("1412 metres")).build());

      assertThat(result)
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("1412 metres"))));
    }

    @Test
    void a_tool_use_block_is_a_request_for_actions() {
      InferenceResult result =
          inferAnswering(
              reply().addContent(use("call_1", "lookup", Map.of("q", "loch ness"))).build());

      assertThat(((InferenceResult.Actions) result).blocks())
          .singleElement()
          .isInstanceOfSatisfying(
              Block.ToolCall.class,
              call -> {
                assertThat(call.id()).isEqualTo(new CallId("call_1"));
                assertThat(call.name()).isEqualTo(new ToolName("lookup"));
                assertThat(call.arguments()).contains("loch ness");
              });
    }

    /**
     * Prose beside calls is the model talking while it works, not part of an answer. The same field
     * on the wire carries both, so only the shape of the reply can tell them apart.
     */
    @Test
    void prose_beside_a_call_is_commentary_rather_than_an_answer() {
      InferenceResult result =
          inferAnswering(
              reply()
                  .addContent(text("Let me look."))
                  .addContent(use("call_1", "lookup", Map.of()))
                  .build());

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isEqualTo(new Block.Commentary("Let me look."));
    }

    @Test
    void whitespace_beside_a_call_is_not_kept_as_commentary() {
      InferenceResult result =
          inferAnswering(
              reply()
                  .addContent(text("\n\n"))
                  .addContent(use("call_1", "lookup", Map.of()))
                  .build());

      assertThat(((InferenceResult.Actions) result).blocks())
          .noneMatch(Block.Commentary.class::isInstance);
    }

    /**
     * The one thing that has to survive a round trip untouched. Reasoning comes back as this
     * vendor's own payload, signature included, and {@code AnthropicRequests} sends it out again
     * exactly as it arrived -- nothing in between understands it, which is the point.
     */
    @Test
    void reasoning_comes_back_as_this_vendors_own_state_with_its_signature() {
      InferenceResult result =
          inferAnswering(
              reply()
                  .addContent(
                      ThinkingBlock.builder()
                          .thinking("let me recall")
                          .signature("sig-abc")
                          .build())
                  .addContent(text("1412 metres"))
                  .build());

      assertThat(((InferenceResult.Answer) result).blocks().getFirst())
          .isInstanceOfSatisfying(
              Block.Provider.class,
              state -> {
                assertThat(state.vendor()).isEqualTo("anthropic");
                assertThat(state.payload())
                    .contains("let me recall")
                    .contains("sig-abc")
                    .contains("thinking");
              });
    }

    /**
     * Reported in {@code stop_reason} rather than in the content. An adapter for a wire that cannot
     * say it has to guess; this one does not have to.
     */
    @Test
    void a_refusal_is_a_refusal_rather_than_an_answer_that_happens_to_say_no() {
      InferenceResult result =
          inferAnswering(reply().stopReason(StopReason.REFUSAL).addContent(text("")).build());

      assertThat(result).isInstanceOf(InferenceResult.Refusal.class);
    }

    @Test
    void the_model_asked_for_is_the_one_in_the_options() {
      var captured = new MessageCreateParams[1];
      new AnthropicProviderConfig()
          .client(
              fakeClient(
                  params -> {
                    captured[0] = params;
                    return reply().addContent(text("ok")).build();
                  }))
          .build()
          .infer(REQUEST);

      assertThat(captured[0].model().asString()).isEqualTo("claude-sonnet");
    }

    /** Whether to think, and on what budget, is the provider's setting: a deployment decision. */
    @Test
    void thinking_is_asked_for_only_when_the_provider_is_configured_for_it() {
      var captured = new MessageCreateParams[1];
      Function<MessageCreateParams, Message> capture =
          params -> {
            captured[0] = params;
            return reply().addContent(text("ok")).build();
          };

      new AnthropicProviderConfig().client(fakeClient(capture)).build().infer(REQUEST);
      assertThat(captured[0].thinking()).isEmpty();

      new AnthropicProviderConfig()
          .client(fakeClient(capture))
          .thinking(true)
          .thinkingBudget(512)
          .build()
          .infer(REQUEST);
      assertThat(captured[0].thinking().orElseThrow().asEnabled().budgetTokens()).isEqualTo(512L);
    }
  }

  @Nested
  class Configuration {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      var config = new AnthropicProviderConfig();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void from_env_fails_clearly_when_the_variable_is_unset() {
      // fromEnv() itself no longer reads the environment eagerly — it only sets a flag, so the
      // SDK's own environment table (API key, auth token, base URL, profiles, ...) is honored in
      // full at build() time. The failure this test cares about — nothing at all is configured —
      // moves to build() accordingly.
      assumeTrue(
          System.getenv("ANTHROPIC_API_KEY") == null
              && System.getenv("ANTHROPIC_AUTH_TOKEN") == null,
          "ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN is set in this shell");

      var config = new AnthropicProviderConfig().fromEnv();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void an_explicit_api_key_set_after_from_env_still_builds_without_needing_the_environment() {
      // Demonstrates the "explicit beats ambient" precedence from the caller's side: apiKey(...)
      // unblocks build() even though fromEnv() was also requested and this shell may have no
      // ANTHROPIC_API_KEY at all. What this cannot verify offline — since AnthropicClient exposes
      // no accessor for its resolved key/base URL — is that the SDK actually preferred our
      // explicit values over ones an environment variable might also supply; that end-to-end
      // delegation (env baseUrl/auth-token support, explicit override winning) is exercised live
      // by AnthropicLiveTest, whose a_real_conversation_answers test builds exclusively via
      // fromEnv().
      AnthropicInferenceProvider provider =
          new AnthropicProviderConfig()
              .fromEnv()
              .apiKey("sk-explicit-wins")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_explicit_api_key_set_after_from_env_with_no_base_url_still_builds() {
      // Companion to an_explicit_api_key_set_after_from_env_still_builds_without_needing_the
      // _environment above: that test always sets baseUrl too, which never exercises
      // buildFromEnv()'s baseUrl == null branch. This one leaves it unset.
      AnthropicInferenceProvider provider =
          new AnthropicProviderConfig().fromEnv().apiKey("sk-explicit-only").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      AnthropicInferenceProvider provider = new AnthropicProviderConfig().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("sk-test").build();

      AnthropicInferenceProvider provider = new AnthropicProviderConfig().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      AnthropicInferenceProvider provider =
          new AnthropicProviderConfig()
              .apiKey("sk-test")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_thinking_budget_is_accepted_without_error() {
      AnthropicInferenceProvider provider =
          new AnthropicProviderConfig().apiKey("sk-test").thinkingBudget(1024).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_blank_api_key_is_rejected_the_same_as_a_missing_one() {
      var config = new AnthropicProviderConfig().apiKey("   ");

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }
  }

  /**
   * Drives the two public static factories directly — {@link AnthropicInferenceProvider#create} and
   * {@link AnthropicInferenceProvider#fromEnv} — rather than the package-private {@link
   * AnthropicProviderConfig} the {@link Configuration} tests above reach into. Spec §5 requires
   * {@code fromEnv()} equal {@code create(config -> config.fromEnv())} in behavior; this pins that
   * offline by driving both through the same unset-environment failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> AnthropicInferenceProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(
          System.getenv("ANTHROPIC_API_KEY") == null
              && System.getenv("ANTHROPIC_AUTH_TOKEN") == null,
          "ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN is set in this shell");

      assertThatThrownBy(AnthropicInferenceProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY");
      assertThatThrownBy(() -> AnthropicInferenceProvider.create(AnthropicProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      AnthropicInferenceProvider provider =
          AnthropicInferenceProvider.create(c -> c.apiKey("sk-test"));

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Name {

    @Test
    void reports_anthropic() {
      AnthropicInferenceProvider provider = new AnthropicProviderConfig().apiKey("sk-test").build();

      assertThat(provider.name()).isEqualTo("Anthropic");
    }
  }

  /**
   * What a failed call <em>means</em>, decided here and nowhere else.
   *
   * <p>Grounded in the SDK's own retry classification: it retries a raw {@code IOException} or
   * {@link AnthropicRetryableException} unconditionally, and otherwise by status code, before a
   * typed exception is ever constructed -- so by the time one surfaces here its own budget is
   * already spent.
   *
   * <p>Driven through {@code infer} rather than against a predicate, because the classification is
   * only worth anything if a failure actually reaches the fold as a {@code Fault} instead of
   * escaping as a throw.
   */
  @Nested
  class WhatAFailureMeans {

    @Test
    void a_rate_limit_is_worth_another_attempt() {
      assertThat(
              inferFailing(
                  RateLimitException.builder().headers(emptyHeaders()).body(emptyBody()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void so_is_an_internal_server_error() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(500)
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    /** Anthropic reports overload as a 529, which dispatches through the same 5xx branch. */
    @Test
    void and_an_overloaded_service() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(529)
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_the_sdk_s_own_transient_marker() {
      assertThat(inferFailing(new AnthropicRetryableException("transient failure")))
          .isInstanceOf(Failure.Transient.class);
    }

    /**
     * A request that was accepted and then lost its connection may well have been processed, so it
     * is not a failure at all -- only an unanswered question. Repeating it is safe exactly when
     * repeating the work is safe, and that is not this class's call to make.
     */
    @Test
    void a_transport_failure_is_unknown_rather_than_transient() {
      assertThat(inferFailing(new AnthropicIoException("connection reset")))
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void a_bad_request_is_permanent() {
      assertThat(
              inferFailing(
                  BadRequestException.builder().headers(emptyHeaders()).body(emptyBody()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void so_is_an_unauthorized_call() {
      assertThat(
              inferFailing(
                  UnauthorizedException.builder()
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_permission_denied() {
      assertThat(
              inferFailing(
                  PermissionDeniedException.builder()
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_not_found() {
      assertThat(
              inferFailing(
                  NotFoundException.builder().headers(emptyHeaders()).body(emptyBody()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_an_unprocessable_entity() {
      assertThat(
              inferFailing(
                  UnprocessableEntityException.builder()
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_status_code_the_sdk_does_not_recognise() {
      assertThat(
              inferFailing(
                  UnexpectedStatusCodeException.builder()
                      .statusCode(418)
                      .headers(emptyHeaders())
                      .body(emptyBody())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_response_that_would_not_decode() {
      assertThat(inferFailing(new AnthropicInvalidDataException("unrecognized enum value")))
          .isInstanceOf(Failure.Permanent.class);
    }

    /**
     * <b>Nothing here is ever {@link Failure.Rejected}.</b> That is the one classification that
     * authorises throwing away something a person said, and it should rest on a measured marker in
     * a response rather than on a guess about what a 400 meant. This test is what would fail if
     * somebody started guessing.
     */
    @Test
    void and_never_a_rejection_of_the_content_itself() {
      assertThat(
              inferFailing(
                  BadRequestException.builder().headers(emptyHeaders()).body(emptyBody()).build()))
          .isNotInstanceOf(Failure.Rejected.class);
    }

    /**
     * A bug in this adapter must not arrive at the fold dressed as the model's fault: it would be
     * retried and then written into the story as a failed turn.
     */
    @Test
    void a_bug_in_the_adapter_is_not_dressed_up_as_the_model_failing() {
      IllegalStateException bug = new IllegalStateException("a bug in here");
      assertThatThrownBy(() -> inferFailing(bug))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a bug in here");
    }
  }

  @Nested
  class TheEdges {

    @Test
    void redacted_thinking_travels_as_provider_state_and_a_server_tool_block_is_dropped() {
      AnthropicClient client =
          fakeClient(
              params ->
                  reply()
                      .content(
                          List.of(
                              com.anthropic.models.messages.ContentBlock.ofRedactedThinking(
                                  com.anthropic.models.messages.RedactedThinkingBlock.builder()
                                      .data("opaque")
                                      .build()),
                              com.anthropic.models.messages.ContentBlock.ofText(
                                  com.anthropic.models.messages.TextBlock.builder()
                                      .text("hello")
                                      .citations(List.of())
                                      .build())))
                      .build());
      InferenceResult result = new AnthropicProviderConfig().client(client).build().infer(REQUEST);

      assertThat(result).isInstanceOf(InferenceResult.Answer.class);
      List<Block.AnswerContent> blocks = ((InferenceResult.Answer) result).blocks();
      assertThat(blocks.get(0)).isInstanceOf(Block.Provider.class);
      assertThat(((Block.Provider) blocks.get(0)).payload()).contains("redacted_thinking");
      assertThat(blocks.get(1)).isEqualTo(new Block.Text("hello"));
    }

    @Test
    void from_env_is_the_config_route() {
      org.junit.jupiter.api.Assumptions.assumeTrue(
          System.getenv("ANTHROPIC_API_KEY") == null
              && System.getenv("ANTHROPIC_AUTH_TOKEN") == null);
      assertThatThrownBy(AnthropicInferenceProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_null_caching_setting_or_mapper_is_refused_at_configuration() {
      assertThatThrownBy(() -> AnthropicInferenceProvider.create(c -> c.promptCaching(null)))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> AnthropicInferenceProvider.create(c -> c.mapper(null)))
          .isInstanceOf(NullPointerException.class);
    }
  }
}
