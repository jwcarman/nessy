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
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.ResponseCreateParams;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.inference.InferenceOptions;

/** Building a Responses provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The OpenAI Responses provider config")
class OpenAiResponsesProviderConfigTest {

  @Test
  void a_null_timeout_is_rejected() {
    Customizer<OpenAiResponsesProviderConfig> customizer = c -> c.apiKey("test-key").timeout(null);
    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_zero_timeout_is_rejected() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").timeout(Duration.ZERO);
    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void neither_a_key_nor_a_client_is_rejected_at_build() {
    OpenAiResponsesProviderConfig config = new OpenAiResponsesProviderConfig();
    assertThatThrownBy(config::build)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("API key");
  }

  @Test
  void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
    OpenAiResponsesInferenceProvider provider =
        OpenAiResponsesInferenceProvider.of(
            c -> c.apiKey("test-key").timeout(Duration.ofMinutes(6)));

    assertThat(provider.name()).isEqualTo("OpenAI");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_timeout_applies_on_the_from_env_build_path_too() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.fromEnv().apiKey("explicit").timeout(Duration.ofMinutes(6));
    assertThatCode(() -> OpenAiResponsesInferenceProvider.of(customizer).close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_supplied_client_is_never_closed_by_the_provider() {
    AtomicInteger closes = new AtomicInteger();
    OpenAIClient supplied = recordingClient(closes);

    OpenAiResponsesInferenceProvider provider =
        OpenAiResponsesInferenceProvider.of(c -> c.client(supplied).timeout(Duration.ofMinutes(6)));
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

  @Test
  void a_property_on_the_config_reaches_every_request() {
    var captured = new ResponseCreateParams[1];
    OpenAiResponsesInferenceProvider provider =
        new OpenAiResponsesProviderConfig()
            .property("openai.reasoning.effort", "medium")
            .client(
                ResponseStreams.client(
                    params -> {
                      captured[0] = params;
                      return ResponseStreams.eventsOf(
                          ResponseStreams.completed(
                              List.of(ResponseStreams.message("msg_1", "ok"))));
                    }))
            .build();

    provider.infer(OpenAiResponsesInferenceProviderTest.REQUEST);

    assertThat(captured[0].reasoning().orElseThrow().effort().map(ReasoningEffort::asString))
        .contains("medium");
  }

  @Test
  void strict_false_on_the_provider_is_refused_at_build() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.tools.strict", "false");

    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.tools.strict'");
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").properties(Map.of("gemini.labels.team", "billing"));

    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.labels.team'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void validate_refuses_the_fixed_fields_an_agent_type_names() {
    OpenAiResponsesInferenceProvider provider =
        new OpenAiResponsesProviderConfig()
            .client(ResponseStreams.client(params -> List.of()))
            .build();
    InferenceOptions options =
        new InferenceOptions("gpt-6-sol", 1024, Map.of("openai.background", "true"));

    assertThatThrownBy(() -> provider.validate(options))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.background'");
  }
}
