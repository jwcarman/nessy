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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import java.lang.reflect.Proxy;
import java.util.List;
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

class OpenAiInferenceProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  /**
   * {@link OpenAIClient} carries dozens of unrelated abstract resource accessors (embeddings,
   * files, images, batches, ...), none of which {@code infer} ever touches except {@code
   * chat().completions().create(...)}. A JDK dynamic proxy -- not a mocking library, just {@link
   * Proxy#newProxyInstance} -- answers only that one call path and throws {@link
   * UnsupportedOperationException} for everything else.
   *
   * <p>{@code answer} receives the params the provider built and returns the completion to hand
   * back, or throws -- which is how the classification tests below reach {@code infer}'s catch
   * without a network.
   */
  private static OpenAIClient fakeClient(
      Function<ChatCompletionCreateParams, ChatCompletion> answer) {
    var completionService =
        (ChatCompletionService)
            Proxy.newProxyInstance(
                ChatCompletionService.class.getClassLoader(),
                new Class<?>[] {ChatCompletionService.class},
                (proxy, method, args) -> {
                  // The provider calls the SDK's one-arg create(params) default method, but a
                  // JDK proxy intercepts every interface method call itself rather than letting
                  // the default method's body run and delegate to the two-arg abstract overload
                  // -- so this must match on name alone, not arity.
                  if ("create".equals(method.getName())) {
                    return answer.apply((ChatCompletionCreateParams) args[0]);
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    var chatService =
        (ChatService)
            Proxy.newProxyInstance(
                ChatService.class.getClassLoader(),
                new Class<?>[] {ChatService.class},
                (proxy, method, args) -> {
                  if ("completions".equals(method.getName())) {
                    return completionService;
                  }
                  throw new UnsupportedOperationException(method.getName());
                });
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("chat".equals(method.getName())) {
                return chatService;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  /** One completion carrying one choice, which is the only shape this adapter reads. */
  private static ChatCompletion completionOf(ChatCompletionMessage message) {
    return ChatCompletion.builder()
        .id("chatcmpl-test")
        .created(0L)
        .model("gpt-4o")
        .addChoice(
            ChatCompletion.Choice.builder()
                .finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .index(0L)
                .message(message)
                .logprobs(Optional.empty())
                .build())
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
          InferenceOptions.of("gpt-4o"));

  /** Runs one inference against a client that answers with {@code message}. */
  private static InferenceResult inferAnswering(ChatCompletionMessage message) {
    return new OpenAiProviderConfig()
        .client(fakeClient(params -> completionOf(message)))
        .build()
        .infer(REQUEST);
  }

  /** Runs one inference against a client that fails with {@code failure}. */
  private static Failure inferFailing(RuntimeException failure) {
    InferenceResult result =
        new OpenAiProviderConfig()
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

    /**
     * A reasoning model that spends its whole token budget thinking sends a 200 with empty content
     * and finish_reason=length. That is not an answer the story can hold, and it is not this
     * adapter's bug either: it is the model's, said as one.
     */
    @Test
    void an_empty_answer_is_a_fault_that_names_the_finish_reason() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content("")
                  .refusal(Optional.<String>empty())
                  .build());

      assertThat(result)
          .isInstanceOfSatisfying(
              InferenceResult.Fault.class,
              fault -> {
                assertThat(fault.failure()).isInstanceOf(Failure.Permanent.class);
                assertThat(fault.failure().reason()).contains("empty").contains("stop");
              });
    }

    @Test
    void plain_prose_is_an_answer() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content("1412 metres")
                  .refusal(Optional.<String>empty())
                  .build());

      assertThat(result)
          .isEqualTo(new InferenceResult.Answer(List.of(new Block.Text("1412 metres"))));
    }

    /**
     * Decided on the presence of calls rather than on {@code finish_reason}: the content is the
     * thing that has to be answered, and OpenAI-compatible servers report the reason inconsistently
     * while all of them put the calls in the same place.
     */
    @Test
    void calls_are_a_request_for_actions_carrying_the_arguments_the_model_wrote() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content(Optional.<String>empty())
                  .refusal(Optional.<String>empty())
                  .addToolCall(
                      ChatCompletionMessageFunctionToolCall.builder()
                          .id("call_1")
                          .function(
                              ChatCompletionMessageFunctionToolCall.Function.builder()
                                  .name("lookup")
                                  .arguments("{\"q\":\"loch ness\"}")
                                  .build())
                          .build())
                  .build());

      assertThat(result)
          .isEqualTo(
              new InferenceResult.Actions(
                  List.of(
                      new Block.ToolCall(
                          new CallId("call_1"), new ToolName("lookup"), "{\"q\":\"loch ness\"}"))));
    }

    /**
     * Prose beside calls is the model talking while it works, not part of an answer -- so it is
     * commentary, and the grammar is what says so: a turn that is still asking has not answered.
     */
    @Test
    void prose_beside_calls_is_kept_as_commentary() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content("Let me look.")
                  .refusal(Optional.<String>empty())
                  .addToolCall(
                      ChatCompletionMessageFunctionToolCall.builder()
                          .id("call_1")
                          .function(
                              ChatCompletionMessageFunctionToolCall.Function.builder()
                                  .name("lookup")
                                  .arguments("{}")
                                  .build())
                          .build())
                  .build());

      assertThat(((InferenceResult.Actions) result).blocks().getFirst())
          .isEqualTo(new Block.Commentary("Let me look."));
    }

    /**
     * Whitespace that a server sends as the content of a message whose whole point is its calls is
     * formatting, not the model talking. A commentary block made of it is a dim empty line in every
     * console and a wasted block in every later request.
     */
    @Test
    void whitespace_beside_calls_is_not_kept_as_commentary() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content("\n\n")
                  .refusal(Optional.<String>empty())
                  .addToolCall(
                      ChatCompletionMessageFunctionToolCall.builder()
                          .id("call_1")
                          .function(
                              ChatCompletionMessageFunctionToolCall.Function.builder()
                                  .name("lookup")
                                  .arguments("{}")
                                  .build())
                          .build())
                  .build());

      assertThat(((InferenceResult.Actions) result).blocks())
          .noneMatch(Block.Commentary.class::isInstance);
    }

    /**
     * This wire reports a refusal in a field of its own. Most do not, and an adapter for one of
     * those cannot tell a refusal from an answer -- so the distinction is taken here, where it is
     * offered.
     */
    @Test
    void a_refusal_is_a_refusal_rather_than_an_answer_that_happens_to_say_no() {
      InferenceResult result =
          inferAnswering(
              ChatCompletionMessage.builder()
                  .content(Optional.<String>empty())
                  .refusal("I cannot help with that")
                  .build());

      assertThat(result).isEqualTo(new InferenceResult.Refusal("I cannot help with that"));
    }

    /** A 200 that carries no answer. Asking again returns the same nothing. */
    @Test
    void a_completion_with_no_choices_is_a_permanent_fault() {
      InferenceResult result =
          new OpenAiProviderConfig()
              .client(
                  fakeClient(
                      params ->
                          ChatCompletion.builder()
                              .id("chatcmpl-empty")
                              .created(0L)
                              .model("gpt-4o")
                              .choices(List.of())
                              .build()))
              .build()
              .infer(REQUEST);

      assertThat(result)
          .isEqualTo(new InferenceResult.Fault(new Failure.Permanent("model returned no choices")));
    }

    @Test
    void the_model_asked_for_is_the_one_in_the_options() {
      var captured = new ChatCompletionCreateParams[1];
      new OpenAiProviderConfig()
          .client(
              fakeClient(
                  params -> {
                    captured[0] = params;
                    return completionOf(
                        ChatCompletionMessage.builder()
                            .content("ok")
                            .refusal(Optional.<String>empty())
                            .build());
                  }))
          .build()
          .infer(REQUEST);

      assertThat(captured[0].model().asString()).isEqualTo("gpt-4o");
    }
  }

  @Nested
  class Configuration {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      var config = new OpenAiProviderConfig();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void from_env_fails_clearly_when_the_variable_is_unset() {
      // fromEnv() itself no longer reads the environment eagerly — it only sets a flag, so the
      // SDK's own environment table (API key, org, project, base URL, Azure credential, ...) is
      // honored in full at build() time. The failure this test cares about — nothing at all is
      // configured — moves to build() accordingly.
      assumeTrue(System.getenv("OPENAI_API_KEY") == null, "OPENAI_API_KEY is set in this shell");

      var config = new OpenAiProviderConfig().fromEnv();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void an_explicit_api_key_set_after_from_env_still_builds_without_needing_the_environment() {
      // Demonstrates the "explicit beats ambient" precedence from the caller's side: apiKey(...)
      // unblocks build() even though fromEnv() was also requested and this shell may have no
      // OPENAI_API_KEY at all. What this cannot verify offline — since OpenAIClient exposes no
      // accessor for its resolved key/base URL — is that the SDK actually preferred our explicit
      // values over ones an environment variable might also supply; that end-to-end delegation is
      // exercised live by OpenAiLiveTest, whose a_real_conversation_answers test builds exclusively
      // via fromEnv().
      OpenAiInferenceProvider provider =
          new OpenAiProviderConfig()
              .fromEnv()
              .apiKey("sk-explicit-wins")
              .baseUrl("https://example.invalid")
              .organization("org-explicit-wins")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_explicit_api_key_set_after_from_env_with_no_base_url_or_organization_still_builds() {
      // Companion to an_explicit_api_key_set_after_from_env_still_builds_without_needing_the
      // _environment above: that test always sets baseUrl and organization too, which never
      // exercises buildFromEnv()'s baseUrl == null / organization == null branches. This one
      // leaves both unset.
      OpenAiInferenceProvider provider =
          new OpenAiProviderConfig().fromEnv().apiKey("sk-explicit-only").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_blank_api_key_is_rejected_the_same_as_a_missing_one() {
      var config = new OpenAiProviderConfig().apiKey("   ");

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      OpenAiInferenceProvider provider = new OpenAiProviderConfig().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("sk-test").build();

      OpenAiInferenceProvider provider = new OpenAiProviderConfig().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      OpenAiInferenceProvider provider =
          new OpenAiProviderConfig()
              .apiKey("sk-test")
              .baseUrl("https://openrouter.ai/api/v1")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_organization_is_accepted_without_error() {
      OpenAiInferenceProvider provider =
          new OpenAiProviderConfig().apiKey("sk-test").organization("org-123").build();

      assertThat(provider).isNotNull();
    }
  }

  /**
   * Drives the two public static factories directly — {@link OpenAiInferenceProvider#create} and
   * {@link OpenAiInferenceProvider#fromEnv} — rather than the package-private {@link
   * OpenAiProviderConfig} the {@link Configuration} tests above reach into. Spec §5 requires {@code
   * fromEnv()} equal {@code create(config -> config.fromEnv())} in behavior; this pins that offline
   * by driving both through the same unset-environment failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> OpenAiInferenceProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(System.getenv("OPENAI_API_KEY") == null, "OPENAI_API_KEY is set in this shell");

      assertThatThrownBy(OpenAiInferenceProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
      assertThatThrownBy(() -> OpenAiInferenceProvider.create(OpenAiProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      OpenAiInferenceProvider provider = OpenAiInferenceProvider.create(c -> c.apiKey("sk-test"));

      assertThat(provider).isNotNull();
    }
  }

  // REMOVED IN THE CUTOVER (2026-08-30): pinned the capability set this vendor advertised through
  // Model#capabilities(), which the new SPI does not have — a request STATES what it would like
  // via ModelRequest#requested() and an adapter that cannot oblige simply does not.

  @Nested
  class Name {

    @Test
    void reports_openai_even_when_wired_to_a_compatible_endpoint_such_as_xai() {
      OpenAiInferenceProvider provider =
          new OpenAiProviderConfig().apiKey("sk-test").baseUrl("https://api.x.ai/v1").build();

      assertThat(provider.name()).isEqualTo("OpenAI");
    }
  }

  /**
   * What a failed call <em>means</em> is decided here and nowhere else, because this is the only
   * class that knows what this SDK's exceptions say.
   *
   * <p>Grounded in the SDK's own retry classification: {@code
   * com.openai.core.http.RetryingHttpClient} retries a raw {@code IOException} or {@code
   * OpenAIRetryableException} unconditionally, and otherwise by status code (408, 409, 429, or any
   * 5xx) <em>before</em> the response is ever translated into one of the typed exceptions below --
   * so by the time one surfaces here, the SDK's own budget ({@code maxRetries}, default 2) is
   * already spent, and only a further caller-driven retry with backoff is left.
   *
   * <p>Driven through {@link OpenAiInferenceProvider#infer} rather than against a predicate,
   * because the classification is only worth anything if a failure actually reaches the fold as a
   * {@code Fault} instead of escaping as a throw.
   */
  @Nested
  class WhatAFailureMeans {

    @Test
    void a_rate_limit_is_worth_another_attempt() {
      assertThat(inferFailing(RateLimitException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void so_is_an_internal_server_error() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(500)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    /** Any 5xx (502, 503, ...) dispatches through the same branch in the SDK's error handler. */
    @Test
    void and_a_gateway_error_reported_as_a_5xx() {
      assertThat(
              inferFailing(
                  InternalServerException.builder()
                      .statusCode(503)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Transient.class);
    }

    @Test
    void and_the_sdk_s_own_transient_marker() {
      assertThat(inferFailing(new OpenAIRetryableException("transient failure")))
          .isInstanceOf(Failure.Transient.class);
    }

    /**
     * A request that was accepted and then lost its connection may well have been processed, so it
     * is not a failure at all -- only an unanswered question. Repeating it is safe exactly when
     * repeating the work is safe, and that is not this class's call to make.
     */
    @Test
    void a_transport_failure_is_unknown_rather_than_transient() {
      assertThat(inferFailing(new OpenAIIoException("connection reset")))
          .isInstanceOf(Failure.Unknown.class);
    }

    @Test
    void a_bad_request_is_permanent() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void so_is_an_unauthorized_call() {
      assertThat(inferFailing(UnauthorizedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_permission_denied() {
      assertThat(inferFailing(PermissionDeniedException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_not_found() {
      assertThat(inferFailing(NotFoundException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_an_unprocessable_entity() {
      assertThat(
              inferFailing(UnprocessableEntityException.builder().headers(emptyHeaders()).build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    /**
     * Also covers a 408 or 409 that survived the SDK's own retry budget: its error handler has no
     * dedicated exception type for those two codes, so they fall into this branch too.
     */
    @Test
    void and_a_status_code_the_sdk_does_not_recognise() {
      assertThat(
              inferFailing(
                  UnexpectedStatusCodeException.builder()
                      .statusCode(409)
                      .headers(emptyHeaders())
                      .build()))
          .isInstanceOf(Failure.Permanent.class);
    }

    @Test
    void and_a_response_that_would_not_decode() {
      assertThat(inferFailing(new OpenAIInvalidDataException("unrecognized enum value")))
          .isInstanceOf(Failure.Permanent.class);
    }

    /**
     * <b>Nothing here is ever {@link Failure.Rejected}.</b> That is the one classification that
     * authorises throwing away something a person said, and it should rest on a measured marker in
     * a particular server's response rather than on a guess about what a 400 meant. None has been
     * measured on this wire, so none is claimed -- and this test is what would fail if somebody
     * started guessing.
     */
    @Test
    void and_never_a_rejection_of_the_content_itself() {
      assertThat(inferFailing(BadRequestException.builder().headers(emptyHeaders()).build()))
          .isNotInstanceOf(Failure.Rejected.class);
    }

    /**
     * A bug in this adapter must not arrive at the fold dressed as the model's fault: it would be
     * retried three times and then written into the story as a failed turn. The catch is narrow so
     * that anything which is not the provider failing still escapes.
     */
    @Test
    void a_bug_in_the_adapter_is_not_dressed_up_as_the_model_failing() {
      assertThatThrownBy(() -> inferFailing(new IllegalStateException("a bug in here")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("a bug in here");
    }
  }
}
