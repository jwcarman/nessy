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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.spi.model.Capability;

class OpenAiModelProviderTest {

  private static Headers emptyHeaders() {
    return Headers.builder().build();
  }

  @Nested
  class Builder {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      assertThatThrownBy(() -> OpenAiModelProvider.builder().build())
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

      assertThatThrownBy(() -> OpenAiModelProvider.builder().fromEnv().build())
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
          OpenAiModelProvider.builder()
              .fromEnv()
              .apiKey("sk-explicit-wins")
              .baseUrl("https://example.invalid")
              .organization("org-explicit-wins")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      OpenAiModelProvider provider = OpenAiModelProvider.builder().apiKey("sk-test").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      OpenAIClient client = OpenAIOkHttpClient.builder().apiKey("sk-test").build();

      OpenAiModelProvider provider = OpenAiModelProvider.builder().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      OpenAiModelProvider provider =
          OpenAiModelProvider.builder()
              .apiKey("sk-test")
              .baseUrl("https://openrouter.ai/api/v1")
              .build();

      assertThat(provider).isNotNull();
    }

    @Test
    void an_organization_is_accepted_without_error() {
      OpenAiModelProvider provider =
          OpenAiModelProvider.builder().apiKey("sk-test").organization("org-123").build();

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void advertise_parallel_tool_calls_and_image_input_but_not_thinking_or_caching() {
      OpenAiModelProvider provider = OpenAiModelProvider.builder().apiKey("sk-test").build();

      assertThat(provider.capabilities())
          .containsExactlyInAnyOrder(Capability.PARALLEL_TOOL_CALLS, Capability.IMAGE_INPUT);
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
