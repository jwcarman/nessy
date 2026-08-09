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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Capability;

class AnthropicModelProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  @Nested
  class Builder {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      assertThatThrownBy(() -> AnthropicModelProvider.builder().build())
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

      assertThatThrownBy(() -> AnthropicModelProvider.builder().fromEnv().build())
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
          AnthropicModelProvider.builder()
              .fromEnv()
              .apiKey("sk-explicit-wins")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      AnthropicModelProvider provider = AnthropicModelProvider.builder().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      AnthropicClient client = AnthropicOkHttpClient.builder().apiKey("sk-test").build();

      AnthropicModelProvider provider = AnthropicModelProvider.builder().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      AnthropicModelProvider provider =
          AnthropicModelProvider.builder()
              .apiKey("sk-test")
              .baseUrl("https://example.invalid")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_thinking_budget_is_accepted_without_error() {
      AnthropicModelProvider provider =
          AnthropicModelProvider.builder().apiKey("sk-test").thinkingBudget(1024).build();

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void advertise_thinking_caching_parallel_tools_and_images() {
      AnthropicModelProvider provider = AnthropicModelProvider.builder().apiKey("sk-test").build();

      assertThat(provider.capabilities())
          .containsExactlyInAnyOrder(
              Capability.THINKING,
              Capability.PROMPT_CACHING,
              Capability.PARALLEL_TOOL_CALLS,
              Capability.IMAGE_INPUT);
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
