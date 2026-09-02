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
package org.jwcarman.nessy.model.gemini;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelRequest;

class GeminiModelProviderTest {

  private static GeminiClient fakeClient(Object[] capturedArgs, GeminiStream response) {
    return (model, contents, config) -> {
      capturedArgs[0] = model;
      capturedArgs[1] = contents;
      capturedArgs[2] = config;
      return response;
    };
  }

  @Nested
  class Streaming {

    @Test
    void delegates_to_the_client_and_returns_its_stream_unchanged() {
      var capturedArgs = new Object[3];
      var response = new GeminiStream(List.of(), () -> {});
      var provider = new GeminiModelProvider(fakeClient(capturedArgs, response));
      var model = provider.model(ModelId.of("gemini-2.5-flash"));
      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of());

      var stream = model.stream(request);

      assertThat(stream).isSameAs(response);
      assertThat(capturedArgs[0]).isEqualTo("gemini-2.5-flash");
      assertThat(capturedArgs[1]).isInstanceOf(List.class);
      assertThat(capturedArgs[2]).isInstanceOf(GenerateContentConfig.class);
      var config = (GenerateContentConfig) capturedArgs[2];
      assertThat(config.maxOutputTokens()).contains(1024);
    }
  }

  @Nested
  class Configuration {

    @Test
    void rejects_build_with_neither_a_key_nor_a_client() {
      var config = new GeminiProviderConfig();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void a_blank_api_key_is_rejected_the_same_as_a_missing_one() {
      var config = new GeminiProviderConfig().apiKey("   ");

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("apiKey")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void an_api_key_alone_is_enough_to_build() {
      var provider = new GeminiProviderConfig().apiKey("test-key").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_base_url_is_accepted_without_error() {
      var provider =
          new GeminiProviderConfig().apiKey("test-key").baseUrl("https://example.invalid").build();

      assertThat(provider).isNotNull();
    }

    @Test
    void a_preconfigured_client_bypasses_the_key_requirement() {
      Client client = Client.builder().apiKey("test-key").build();

      var provider = new GeminiProviderConfig().client(client).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void from_env_fails_clearly_when_neither_variable_is_set_naming_both() {
      assumeTrue(System.getenv("GEMINI_API_KEY") == null, "GEMINI_API_KEY is set in this shell");
      assumeTrue(System.getenv("GOOGLE_API_KEY") == null, "GOOGLE_API_KEY is set in this shell");

      var config = new GeminiProviderConfig().fromEnv();

      assertThatThrownBy(config::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          .hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void an_explicit_api_key_set_after_from_env_still_builds_without_needing_the_environment() {
      var provider = new GeminiProviderConfig().fromEnv().apiKey("explicit-key").build();

      assertThat(provider).isNotNull();
    }
  }

  /**
   * Drives the two public static factories directly — {@link GeminiModelProvider#create} and {@link
   * GeminiModelProvider#fromEnv} — rather than the package-private {@link GeminiProviderConfig} the
   * {@link Configuration} tests above reach into. Spec §5 requires {@code fromEnv()} equal {@code
   * create(config -> config.fromEnv())} in behavior; this pins that offline by driving both through
   * the same unset-environment failure and comparing messages.
   */
  @Nested
  class PublicStaticFactories {

    @Test
    void create_rejects_a_null_customizer() {
      assertThatThrownBy(() -> GeminiModelProvider.create(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("customizer must not be null");
    }

    @Test
    void from_env_fails_the_same_way_create_with_a_from_env_customizer_does() {
      assumeTrue(System.getenv("GEMINI_API_KEY") == null, "GEMINI_API_KEY is set in this shell");
      assumeTrue(System.getenv("GOOGLE_API_KEY") == null, "GOOGLE_API_KEY is set in this shell");

      assertThatThrownBy(GeminiModelProvider::fromEnv)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          .hasMessageContaining("GOOGLE_API_KEY");
      assertThatThrownBy(() -> GeminiModelProvider.create(GeminiProviderConfig::fromEnv))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GEMINI_API_KEY")
          .hasMessageContaining("GOOGLE_API_KEY");
    }

    @Test
    void create_reaches_the_real_construction_path_offline() {
      var provider = GeminiModelProvider.create(c -> c.apiKey("test-key"));

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    // REMOVED IN THE CUTOVER (2026-08-30): pinned the capability set advertised through
    // Model#capabilities(), which the new SPI does not have — a request STATES what it would like
    // and an adapter that cannot oblige simply does not.
  }

  @Nested
  class Name {

    @Test
    void reports_gemini() {
      var provider = new GeminiProviderConfig().apiKey("test-key").build();

      assertThat(provider.name()).isEqualTo("Gemini");
    }
  }

  /**
   * Exercises the split itself, offline: {@link GeminiModelProvider#model(String)} hands out
   * independent handles that share the gateway's client, each honest about its own id, with a blank
   * id rejected before any handle is created.
   */
  @Nested
  class Gateway {

    @Test
    void two_model_calls_on_one_provider_yield_independent_handles_sharing_the_client() {
      var capturedArgs = new Object[3];
      var response = new GeminiStream(List.of(), () -> {});
      var provider = new GeminiModelProvider(fakeClient(capturedArgs, response));

      Model flash = provider.model(ModelId.of("gemini-2.5-flash"));
      Model pro = provider.model(ModelId.of("gemini-2.5-pro"));

      assertThat(flash.id()).isEqualTo(ModelId.of("gemini-2.5-flash"));
      assertThat(pro.id()).isEqualTo(ModelId.of("gemini-2.5-pro"));
      assertThat(flash).isNotSameAs(pro);

      var request = new ModelRequest(Context.of(List.of()), "sys", 1024, List.of(), Set.of());
      flash.stream(request);
      assertThat(capturedArgs[0]).isEqualTo("gemini-2.5-flash");
      pro.stream(request);
      assertThat(capturedArgs[0]).isEqualTo("gemini-2.5-pro");
    }

    @Test
    void a_blank_model_id_is_rejected() {
      // The check moved INTO ModelId, so a blank never reaches a provider at all.
      assertThatThrownBy(() -> ModelId.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_null_model_id_is_rejected() {
      var provider = new GeminiProviderConfig().apiKey("test-key").build();

      assertThatThrownBy(() -> provider.model(null)).isInstanceOf(NullPointerException.class);
    }
  }
}
