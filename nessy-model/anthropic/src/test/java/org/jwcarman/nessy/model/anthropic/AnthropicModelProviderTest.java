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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonMissing;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicWebhookException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.SseException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.errors.UnexpectedStatusCodeException;
import com.anthropic.errors.UnprocessableEntityException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.blocking.MessageService;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;

class AnthropicModelProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  /**
   * {@link AnthropicClient} carries 8 abstract methods (async, withRawResponse, withOptions,
   * completions, messages, models, beta, close) and its {@link MessageService} carries 6 more, none
   * of which the model handle's {@code stream(...)} ever touches except {@code messages()} and
   * {@code createStreaming(...)}. A JDK dynamic proxy — not a mocking library, just {@link
   * Proxy#newProxyInstance} — answers only the one call path exercised here and throws {@link
   * UnsupportedOperationException} for everything else, without hand-implementing over a dozen
   * unrelated SDK resource accessors.
   */
  private static AnthropicClient fakeClient(
      MessageCreateParams[] capturedParams, StreamResponse<RawMessageStreamEvent> response) {
    var messageService =
        (MessageService)
            Proxy.newProxyInstance(
                MessageService.class.getClassLoader(),
                new Class<?>[] {MessageService.class},
                (proxy, method, args) -> {
                  // The provider calls the SDK's one-arg createStreaming(params) default method,
                  // but a JDK proxy intercepts every interface method call itself rather than
                  // letting the default method's own body run and delegate to the two-arg
                  // abstract overload — so this must match on name alone, not arity.
                  if ("createStreaming".equals(method.getName())) {
                    capturedParams[0] = (MessageCreateParams) args[0];
                    return response;
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

  private static StreamResponse<RawMessageStreamEvent> emptyStreamResponse() {
    return new StreamResponse<>() {
      @Override
      public Stream<RawMessageStreamEvent> stream() {
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
    void delegates_to_the_sdk_client_and_wraps_the_result_in_an_anthropic_stream() {
      var capturedParams = new MessageCreateParams[1];
      var client = fakeClient(capturedParams, emptyStreamResponse());
      var provider = new AnthropicProviderConfig().client(client).build();
      var model = provider.model(ModelId.of("claude-sonnet"));
      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of());

      var stream = model.stream(request);

      assertThat(stream).isInstanceOf(AnthropicStream.class);
      assertThat(capturedParams[0]).isNotNull();
      assertThat(capturedParams[0].model().asString()).isEqualTo("claude-sonnet");
    }

    @Test
    void a_thinking_request_carries_the_configured_budget_through_to_the_sdk_params() {
      var capturedParams = new MessageCreateParams[1];
      var client = fakeClient(capturedParams, emptyStreamResponse());
      var provider = new AnthropicProviderConfig().client(client).thinkingBudget(777).build();
      var model = provider.model(ModelId.of("claude-sonnet"));
      var request =
          new ModelRequest(
              Context.of(List.of()), "sys", 4096, List.of(), Set.of(Capability.THINKING));

      model.stream(request);

      var thinking = capturedParams[0].thinking().orElseThrow();
      assertThat(thinking.isEnabled()).isTrue();
      assertThat(thinking.asEnabled().budgetTokens()).isEqualTo(777L);
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
      AnthropicModelProvider provider =
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
      AnthropicModelProvider provider =
          new AnthropicProviderConfig().fromEnv().apiKey("sk-explicit-only").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      AnthropicModelProvider provider = new AnthropicProviderConfig().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("sk-test").build();

      AnthropicModelProvider provider = new AnthropicProviderConfig().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      AnthropicModelProvider provider =
          new AnthropicProviderConfig()
              .apiKey("sk-test")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_thinking_budget_is_accepted_without_error() {
      AnthropicModelProvider provider =
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
   * Drives the two public static factories directly — {@link AnthropicModelProvider#create} and
   * {@link AnthropicModelProvider#fromEnv} — rather than the package-private {@link
   * AnthropicProviderConfig} the {@link Configuration} tests above reach into. Spec §5 requires
   * {@code fromEnv()} equal {@code create(config -> config.fromEnv())} in behavior; this pins that
   * offline by driving both through the same unset-environment failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> AnthropicModelProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(
          System.getenv("ANTHROPIC_API_KEY") == null
              && System.getenv("ANTHROPIC_AUTH_TOKEN") == null,
          "ANTHROPIC_API_KEY or ANTHROPIC_AUTH_TOKEN is set in this shell");

      assertThatThrownBy(AnthropicModelProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY");
      assertThatThrownBy(() -> AnthropicModelProvider.create(AnthropicProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      AnthropicModelProvider provider = AnthropicModelProvider.create(c -> c.apiKey("sk-test"));

      assertThat(provider).isNotNull();
    }
  }

  // REMOVED IN THE CUTOVER (2026-08-30): a nested class pinning the capability set this vendor
  // advertised through Model#capabilities(). The new SPI has no such method — a request STATES
  // the capabilities it would like via ModelRequest#requested() and an adapter that cannot
  // oblige simply does not — so there is no advertised set left to assert on. What the adapter
  // does with a requested capability is covered by AnthropicRequestsTest's caching and thinking
  // cases, which is the behaviour this class was standing in for.

  @Nested
  class Name {

    @Test
    void reports_anthropic() {
      AnthropicModelProvider provider = new AnthropicProviderConfig().apiKey("sk-test").build();

      assertThat(provider.name()).isEqualTo("Anthropic");
    }
  }

  /**
   * Exercises the split itself, offline: {@link AnthropicModelProvider#model(String)} hands out
   * independent handles that share the gateway's client, each honest about its own id, with a blank
   * id rejected before any handle is created.
   */
  @Nested
  class Gateway {

    @Test
    void two_model_calls_on_one_provider_yield_independent_handles_sharing_the_client() {
      var capturedParams = new MessageCreateParams[1];
      var client = fakeClient(capturedParams, emptyStreamResponse());
      var provider = new AnthropicProviderConfig().client(client).build();

      Model opus = provider.model(ModelId.of("claude-opus-5"));
      Model haiku = provider.model(ModelId.of("claude-haiku-4-5"));

      assertThat(opus.id()).isEqualTo(ModelId.of("claude-opus-5"));
      assertThat(haiku.id()).isEqualTo(ModelId.of("claude-haiku-4-5"));
      assertThat(opus).isNotSameAs(haiku);

      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of());
      opus.stream(request);
      assertThat(capturedParams[0].model().asString()).isEqualTo("claude-opus-5");
      haiku.stream(request);
      assertThat(capturedParams[0].model().asString()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void a_blank_model_id_is_rejected() {
      // The check moved INTO ModelId, so a blank never reaches a provider at all — which is the
      // better place for it: every provider used to have to remember to make it.
      assertThatThrownBy(() -> ModelId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_model_id_is_rejected() {
      var provider = new AnthropicProviderConfig().apiKey("sk-test").build();

      assertThatThrownBy(() -> provider.model(null)).isInstanceOf(NullPointerException.class);
    }
  }

  /**
   * Classifies every exception type in the anthropic-java SDK's {@code com.anthropic.errors}
   * hierarchy (2.52.0) against {@link AnthropicModelProvider#RETRYABLE}.
   *
   * <p>Every type below is constructed directly except the credential-resolution family ({@code
   * CredentialResolutionException}, {@code NoCredentialsException}) and the abstract {@code
   * AnthropicServiceException} base, which are documented rather than instantiated — see their
   * nested classes for why.
   */
  @Nested
  class RetryableClassification {

    @Test
    void a_rate_limit_error_is_retryable() {
      RuntimeException e =
          RateLimitException.builder().headers(emptyHeaders()).body(JsonMissing.of()).build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void an_internal_server_error_is_retryable() {
      RuntimeException e =
          InternalServerException.builder()
              .statusCode(500)
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void an_overloaded_error_reported_as_a_5xx_status_is_retryable() {
      // Anthropic's "overloaded" condition is surfaced as HTTP 529, which the SDK's error
      // handler dispatches through the same 500..599 branch as any other server error.
      RuntimeException e =
          InternalServerException.builder()
              .statusCode(529)
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void an_io_failure_is_retryable() {
      RuntimeException e = new AnthropicIoException("connection reset");

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void the_sdk_s_own_transient_marker_is_retryable() {
      RuntimeException e = new AnthropicRetryableException("transient failure");

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isTrue();
    }

    @Test
    void a_bad_request_is_not_retryable() {
      RuntimeException e =
          BadRequestException.builder().headers(emptyHeaders()).body(JsonMissing.of()).build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unauthorized_error_is_not_retryable() {
      RuntimeException e =
          UnauthorizedException.builder().headers(emptyHeaders()).body(JsonMissing.of()).build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_permission_denied_error_is_not_retryable() {
      RuntimeException e =
          PermissionDeniedException.builder()
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_not_found_error_is_not_retryable() {
      RuntimeException e =
          NotFoundException.builder().headers(emptyHeaders()).body(JsonMissing.of()).build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unprocessable_entity_error_is_not_retryable() {
      RuntimeException e =
          UnprocessableEntityException.builder()
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_unexpected_status_code_is_not_retryable() {
      RuntimeException e =
          UnexpectedStatusCodeException.builder()
              .statusCode(599)
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_malformed_streaming_response_is_not_retryable() {
      RuntimeException e =
          SseException.builder()
              .statusCode(200)
              .headers(emptyHeaders())
              .body(JsonMissing.of())
              .build();

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void an_invalid_data_error_is_not_retryable() {
      RuntimeException e = new AnthropicInvalidDataException("unrecognized enum value");

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void a_webhook_error_is_not_retryable() {
      RuntimeException e = new AnthropicWebhookException("signature mismatch");

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    @Test
    void the_base_sdk_exception_is_not_retryable() {
      RuntimeException e = new AnthropicException("generic failure");

      assertThat(AnthropicModelProvider.RETRYABLE.test(e)).isFalse();
    }

    // CredentialResolutionException and NoCredentialsException both carry Kotlin `internal`
    // constructors that take package-private helper types (CredentialSource, itself
    // internal-constructed) — there is no public factory, so constructing one from this module
    // is impractical. They are thrown only while resolving credentials during client
    // construction, never mid-stream, so RETRYABLE never sees one in practice; by the
    // class-hierarchy logic of RETRYABLE (an explicit instanceof allow-list of four types), any
    // type not on that list — including these — is classified false by construction, with no
    // instance needed to demonstrate it. Same reasoning for the abstract AnthropicServiceException
    // base, which has no public constructor at all and is never thrown directly; every concrete
    // subclass reachable from a real API call is covered by the tests above.
  }
}
