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

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.openai.client.OpenAIClient;
import com.openai.models.Reasoning;
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
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;

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
  void typed_properties_set_in_code_reach_the_request_as_the_sdk_values() {
    var captured = new ResponseCreateParams[1];
    new OpenAiResponsesProviderConfig()
        .property(OpenAiProperties.REASONING_EFFORT, OpenAiReasoningEffort.MINIMAL)
        .property(OpenAiProperties.REASONING_SUMMARY, OpenAiReasoningSummary.DETAILED)
        .property(OpenAiProperties.SERVICE_TIER, OpenAiServiceTier.ULTRAFAST)
        .client(
            ResponseStreams.client(
                params -> {
                  captured[0] = params;
                  return ResponseStreams.eventsOf(
                      ResponseStreams.completed(List.of(ResponseStreams.message("msg_1", "ok"))));
                }))
        .build()
        .infer(OpenAiResponsesInferenceProviderTest.REQUEST);

    assertThat(captured[0].reasoning().orElseThrow().effort()).contains(ReasoningEffort.MINIMAL);
    assertThat(captured[0].reasoning().orElseThrow().summary())
        .contains(Reasoning.Summary.DETAILED);
    assertThat(captured[0].serviceTier()).contains(ResponseCreateParams.ServiceTier.ULTRAFAST);
  }

  @Test
  void the_ultrafast_tier_as_a_yaml_style_string_is_accepted_and_sent() {
    var captured = new ResponseCreateParams[1];
    new OpenAiResponsesProviderConfig()
        .property("openai.service_tier", "ultrafast")
        .client(
            ResponseStreams.client(
                params -> {
                  captured[0] = params;
                  return ResponseStreams.eventsOf(
                      ResponseStreams.completed(List.of(ResponseStreams.message("msg_1", "ok"))));
                }))
        .build()
        .infer(OpenAiResponsesInferenceProviderTest.REQUEST);

    assertThat(captured[0].serviceTier()).contains(ResponseCreateParams.ServiceTier.ULTRAFAST);
  }

  @Test
  void a_yaml_style_string_with_a_bad_spelling_fails_at_build_listing_the_spellings() {
    OpenAiResponsesProviderConfig config =
        new OpenAiResponsesProviderConfig()
            .apiKey("test-key")
            .property("openai.reasoning.summary", "verbose");

    assertThatThrownBy(config::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "property 'openai.reasoning.summary' must be one of [auto, concise, detailed],"
                + " was 'verbose'");
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
  void a_typed_property_is_stored_under_its_name_and_refused_like_the_string_form() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").property(VendorProperty.ofBoolean("gemini.labels.team"), true);

    assertThatThrownBy(() -> OpenAiResponsesInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'gemini.labels.team'")
        .hasMessageContaining("'openai.'");
  }

  @Test
  void validate_warns_once_for_an_unsupported_agent_type_property_and_inference_stays_silent() {
    OpenAiResponsesInferenceProvider provider =
        new OpenAiResponsesProviderConfig()
            .client(
                ResponseStreams.client(
                    params ->
                        ResponseStreams.eventsOf(
                            ResponseStreams.completed(
                                List.of(ResponseStreams.message("msg_1", "ok"))))))
            .build();
    InferenceOptions options =
        new InferenceOptions("gpt-6-sol", 1024, Map.of("openai.background", "true"));
    InferenceRequest request =
        new InferenceRequest(
            OpenAiResponsesInferenceProviderTest.REQUEST.systemPrompt(),
            OpenAiResponsesInferenceProviderTest.REQUEST.context(),
            OpenAiResponsesInferenceProviderTest.REQUEST.toolset(),
            options);

    List<ILoggingEvent> atValidate =
        LogCapture.during(OpenAiProperties.class, () -> provider.validate(options));
    List<ILoggingEvent> atInference =
        LogCapture.during(
            OpenAiProperties.class,
            () -> {
              provider.infer(request);
              provider.infer(request);
            });

    assertThat(LogCapture.warnings(atValidate))
        .singleElement()
        .asString()
        .contains("'openai.background'")
        .contains("openai.reasoning.effort");
    assertThat(atInference).isEmpty();
  }

  @Test
  void an_unsupported_property_is_warned_once_at_build_and_the_provider_still_builds() {
    Customizer<OpenAiResponsesProviderConfig> customizer =
        c -> c.apiKey("test-key").property("openai.store", "true");
    var built = new OpenAiResponsesInferenceProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            OpenAiProperties.class,
            () -> built[0] = OpenAiResponsesInferenceProvider.of(customizer));

    assertThat(LogCapture.warnings(events))
        .singleElement()
        .asString()
        .contains("'openai.store'")
        .contains("openai.service_tier");
    assertThat(built[0]).isNotNull();
    built[0].close();
  }
}
