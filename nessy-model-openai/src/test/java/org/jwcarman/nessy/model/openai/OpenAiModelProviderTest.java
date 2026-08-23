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
package org.jwcarman.nessy.model.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.InvalidWebhookSignatureException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.SseException;
import com.openai.errors.UnauthorizedException;
import com.openai.errors.UnexpectedStatusCodeException;
import com.openai.errors.UnprocessableEntityException;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;

class OpenAiModelProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  /**
   * {@link OpenAIClient} carries 26 unrelated abstract resource accessors (embeddings, files,
   * images, batches, ...), none of which the model handle's {@code stream(...)} ever touches except
   * {@code chat().completions().createStreaming(...)}. A JDK dynamic proxy — not a mocking library,
   * just {@link Proxy#newProxyInstance} — answers only that one call path and throws {@link
   * UnsupportedOperationException} for everything else, without hand-implementing dozens of
   * unrelated SDK resource accessors.
   */
  private static OpenAIClient fakeClient(
      ChatCompletionCreateParams[] capturedParams, StreamResponse<ChatCompletionChunk> response) {
    var completionService =
        (ChatCompletionService)
            Proxy.newProxyInstance(
                ChatCompletionService.class.getClassLoader(),
                new Class<?>[] {ChatCompletionService.class},
                (proxy, method, args) -> {
                  // The provider calls the SDK's one-arg createStreaming(params) default method,
                  // but a JDK proxy intercepts every interface method call itself rather than
                  // letting the default method's own body run and delegate to the two-arg
                  // abstract overload — so this must match on name alone, not arity.
                  if ("createStreaming".equals(method.getName())) {
                    capturedParams[0] = (ChatCompletionCreateParams) args[0];
                    return response;
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

  private static StreamResponse<ChatCompletionChunk> emptyStreamResponse() {
    return new StreamResponse<>() {
      @Override
      public Stream<ChatCompletionChunk> stream() {
        return Stream.of();
      }

      @Override
      public void close() {
        // no-op: this fake never holds a resource, so there is nothing to release
      }
    };
  }

  @Nested
  class Streaming {

    @Test
    void delegates_to_the_sdk_client_and_wraps_the_result_in_an_openai_stream() {
      var capturedParams = new ChatCompletionCreateParams[1];
      var client = fakeClient(capturedParams, emptyStreamResponse());
      var provider = new OpenAiProviderConfig().client(client).build();
      var model = provider.model("gpt-4o");
      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of(), null);

      var stream = model.stream(request);

      assertThat(stream).isInstanceOf(OpenAiStream.class);
      assertThat(capturedParams[0]).isNotNull();
      assertThat(capturedParams[0].model().asString()).isEqualTo("gpt-4o");
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
      OpenAiModelProvider provider =
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
      OpenAiModelProvider provider =
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
      OpenAiModelProvider provider = new OpenAiProviderConfig().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("sk-test").build();

      OpenAiModelProvider provider = new OpenAiProviderConfig().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      OpenAiModelProvider provider =
          new OpenAiProviderConfig()
              .apiKey("sk-test")
              .baseUrl("https://openrouter.ai/api/v1")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_organization_is_accepted_without_error() {
      OpenAiModelProvider provider =
          new OpenAiProviderConfig().apiKey("sk-test").organization("org-123").build();

      assertThat(provider).isNotNull();
    }
  }

  /**
   * Drives the two public static factories directly — {@link OpenAiModelProvider#create} and {@link
   * OpenAiModelProvider#fromEnv} — rather than the package-private {@link OpenAiProviderConfig} the
   * {@link Configuration} tests above reach into. Spec §5 requires {@code fromEnv()} equal {@code
   * create(config -> config.fromEnv())} in behavior; this pins that offline by driving both through
   * the same unset-environment failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> OpenAiModelProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(System.getenv("OPENAI_API_KEY") == null, "OPENAI_API_KEY is set in this shell");

      assertThatThrownBy(OpenAiModelProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
      assertThatThrownBy(() -> OpenAiModelProvider.create(OpenAiProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      OpenAiModelProvider provider = OpenAiModelProvider.create(c -> c.apiKey("sk-test"));

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void advertise_parallel_tool_calls_and_image_input_but_not_thinking_or_caching() {
      OpenAiModelProvider provider = new OpenAiProviderConfig().apiKey("sk-test").build();

      assertThat(provider.model("gpt-4o").capabilities())
          .containsExactlyInAnyOrder(Capability.PARALLEL_TOOL_CALLS, Capability.IMAGE_INPUT);
    }
  }

  @Nested
  class Name {

    @Test
    void reports_openai_even_when_wired_to_a_compatible_endpoint_such_as_xai() {
      OpenAiModelProvider provider =
          new OpenAiProviderConfig().apiKey("sk-test").baseUrl("https://api.x.ai/v1").build();

      assertThat(provider.name()).isEqualTo("OpenAI");
    }
  }

  /**
   * Exercises the split itself, offline: {@link OpenAiModelProvider#model(String)} hands out
   * independent handles that share the gateway's client, each honest about its own id, with a blank
   * id rejected before any handle is created.
   */
  @Nested
  class Gateway {

    @Test
    void two_model_calls_on_one_provider_yield_independent_handles_sharing_the_client() {
      var capturedParams = new ChatCompletionCreateParams[1];
      var client = fakeClient(capturedParams, emptyStreamResponse());
      var provider = new OpenAiProviderConfig().client(client).build();

      Model gpt4o = provider.model("gpt-4o");
      Model gpt4oMini = provider.model("gpt-4o-mini");

      assertThat(gpt4o.id()).isEqualTo("gpt-4o");
      assertThat(gpt4oMini.id()).isEqualTo("gpt-4o-mini");
      assertThat(gpt4o).isNotSameAs(gpt4oMini);

      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of(), null);
      gpt4o.stream(request);
      assertThat(capturedParams[0].model().asString()).isEqualTo("gpt-4o");
      gpt4oMini.stream(request);
      assertThat(capturedParams[0].model().asString()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void a_blank_model_id_is_rejected() {
      var provider = new OpenAiProviderConfig().apiKey("sk-test").build();

      assertThatThrownBy(() -> provider.model("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_model_id_is_rejected() {
      var provider = new OpenAiProviderConfig().apiKey("sk-test").build();

      assertThatThrownBy(() -> provider.model(null)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  /**
   * Classifies every exception type in the openai-java SDK's {@code com.openai.errors} hierarchy
   * (4.50.0) against {@link OpenAiModelProvider#RETRYABLE}.
   *
   * <p>Every type below is constructed directly except the workload-identity credential family
   * ({@code SubjectTokenProviderException}) and the abstract {@code OpenAIServiceException} base,
   * which are documented rather than instantiated — see the trailing comment for why.
   */
  @Nested
  class RetryableClassification {

    @Test
    void a_rate_limit_error_is_retryable() {
      RuntimeException e = RateLimitException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void an_internal_server_error_is_retryable() {
      RuntimeException e =
          InternalServerException.builder().statusCode(500).headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void a_gateway_error_reported_as_a_5xx_status_is_retryable() {
      // Any 5xx (502 Bad Gateway, 503 Service Unavailable, ...) dispatches through the same
      // 500..599 branch in the SDK's error handler.
      RuntimeException e =
          InternalServerException.builder().statusCode(503).headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void an_io_failure_is_retryable() {
      RuntimeException e = new OpenAIIoException("connection reset");

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void the_sdk_s_own_transient_marker_is_retryable() {
      RuntimeException e = new OpenAIRetryableException("transient failure");

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void a_bad_request_is_not_retryable() {
      RuntimeException e = BadRequestException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unauthorized_error_is_not_retryable() {
      RuntimeException e = UnauthorizedException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_permission_denied_error_is_not_retryable() {
      RuntimeException e = PermissionDeniedException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_not_found_error_is_not_retryable() {
      RuntimeException e = NotFoundException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unprocessable_entity_error_is_not_retryable() {
      RuntimeException e = UnprocessableEntityException.builder().headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unexpected_status_code_is_not_retryable() {
      // Also covers a 408/409 that survived the SDK's own internal retry budget: the error
      // handler has no dedicated exception type for those two codes, so they fall into this
      // branch too.
      RuntimeException e =
          UnexpectedStatusCodeException.builder().statusCode(409).headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_malformed_streaming_response_is_not_retryable() {
      RuntimeException e = SseException.builder().statusCode(200).headers(emptyHeaders()).build();

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_invalid_data_error_is_not_retryable() {
      RuntimeException e = new OpenAIInvalidDataException("unrecognized enum value");

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_invalid_webhook_signature_is_not_retryable() {
      RuntimeException e = new InvalidWebhookSignatureException("signature mismatch");

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void the_base_sdk_exception_is_not_retryable() {
      RuntimeException e = new OpenAIException("generic failure");

      assertThat(OpenAiModelProvider.RETRYABLE.test(e)).isFalse();
    }

    // SubjectTokenProviderException (workload-identity token resolution) is thrown only while
    // resolving credentials during client construction, never mid-stream, so RETRYABLE never sees
    // one in practice; by the class-hierarchy logic of RETRYABLE (an explicit instanceof allow-list
    // of four types), any type not on that list — including this one — is classified false by
    // construction, with no instance needed to demonstrate it. Same reasoning for the abstract
    // OpenAIServiceException base, which has no public constructor at all and is never thrown
    // directly; every concrete subclass reachable from a real API call is covered by the tests
    // above.
  }
}
