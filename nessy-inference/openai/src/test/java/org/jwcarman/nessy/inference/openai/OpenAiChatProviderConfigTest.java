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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openai.client.OpenAIClient;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transport timeout setter (design record 2026-09-25-locks-as-plumbing-design.md §5).
 *
 * <p>Building a provider needs no network: the SDK client is constructed, never used. The client's
 * own {@code Timeout} is not readable back through {@link OpenAIClient} without reflection or a
 * network call (verified against openai-java 4.50.0: the interface exposes no accessor for it), so
 * these tests assert on the setter's own validation and on the config's fluent state rather than on
 * a value read back from a built client.
 */
@DisplayName("The OpenAI provider config's transport timeout")
class OpenAiChatProviderConfigTest {

  @Test
  void a_null_timeout_is_rejected() {
    assertThatThrownBy(
            () -> OpenAiChatInferenceProvider.of(c -> c.apiKey("test-key").timeout(null)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_zero_timeout_is_rejected() {
    assertThatThrownBy(
            () -> OpenAiChatInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ZERO)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_negative_timeout_is_rejected() {
    assertThatThrownBy(
            () ->
                OpenAiChatInferenceProvider.of(
                    c -> c.apiKey("test-key").timeout(Duration.ofSeconds(-1))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
    OpenAiChatInferenceProvider provider =
        OpenAiChatInferenceProvider.of(c -> c.apiKey("test-key").timeout(Duration.ofMinutes(6)));

    assertThat(provider.name()).isEqualTo("OpenAI");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_timeout_applies_on_the_from_env_build_path_too() {
    assertThatCode(
            () ->
                OpenAiChatInferenceProvider.of(
                        c -> c.fromEnv().apiKey("explicit").timeout(Duration.ofMinutes(6)))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_timeout_alongside_a_supplied_client_leaves_that_client_unclosed_here() {
    AtomicInteger closes = new AtomicInteger();
    OpenAIClient supplied = recordingClient(closes);

    OpenAiChatInferenceProvider provider =
        OpenAiChatInferenceProvider.of(c -> c.client(supplied).timeout(Duration.ofMinutes(6)));
    provider.close();

    assertThat(closes).hasValue(0);
  }

  private static OpenAIClient recordingClient(AtomicInteger closes) {
    return (OpenAIClient)
        Proxy.newProxyInstance(
            OpenAIClient.class.getClassLoader(),
            new Class<?>[] {OpenAIClient.class},
            (proxy, method, args) -> {
              if ("close".equals(method.getName())) {
                closes.incrementAndGet();
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }
}
