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
package org.jwcarman.nessy.inference.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.VendorProperty;
import org.jwcarman.nessy.inference.InferenceOptions;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import tools.jackson.databind.json.JsonMapper;

/** Building a provider needs no network: the SDK client is constructed, never used. */
@DisplayName("The Bedrock provider config")
class BedrockProviderConfigTest {

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));

  @Test
  void a_region_and_credentials_build_a_provider_that_closes_its_own_client() {
    BedrockInferenceProvider provider =
        BedrockInferenceProvider.of(
            c ->
                c.region(Region.US_EAST_1)
                    .credentialsProvider(CREDENTIALS)
                    .mapper(JsonMapper.builder().build()));

    assertThat(provider.name()).isEqualTo("Bedrock");
    assertThatCode(provider::close).doesNotThrowAnyException();
  }

  @Test
  void a_client_the_application_hands_in_is_used_and_never_closed_here() {
    AtomicBoolean closed = new AtomicBoolean();
    BedrockRuntimeAsyncClient theirs =
        (BedrockRuntimeAsyncClient)
            Proxy.newProxyInstance(
                BedrockRuntimeAsyncClient.class.getClassLoader(),
                new Class<?>[] {BedrockRuntimeAsyncClient.class},
                (proxy, method, args) -> {
                  if ("close".equals(method.getName())) {
                    closed.set(true);
                  }
                  return null;
                });

    BedrockInferenceProvider.of(c -> c.client(theirs)).close();

    assertThat(closed).isFalse();
  }

  @Test
  void an_explicit_region_wins_over_the_environment() {
    assertThatCode(
            () ->
                BedrockInferenceProvider.of(
                        c -> c.fromEnv().region(Region.EU_WEST_1).credentialsProvider(CREDENTIALS))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void from_env_with_no_region_names_both_variables() {
    assumeTrue(
        System.getenv("AWS_REGION") == null && System.getenv("AWS_DEFAULT_REGION") == null,
        "a region is set in this environment");

    assertThatThrownBy(
            () -> BedrockInferenceProvider.of(c -> c.fromEnv().credentialsProvider(CREDENTIALS)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AWS_REGION")
        .hasMessageContaining("AWS_DEFAULT_REGION");
  }

  @Test
  void a_null_mapper_is_refused() {
    assertThatThrownBy(() -> BedrockInferenceProvider.of(c -> c.mapper(null)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("aws.bedrock.thinking.type", "enabled");

    assertThatThrownBy(() -> BedrockInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'aws.bedrock.thinking.type'")
        .hasMessageContaining("'bedrock.'");
  }

  @Test
  void a_typed_property_is_stored_under_its_name_and_refused_like_the_string_form() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property(VendorProperty.ofBoolean("aws.bedrock.thinking.type"), true);

    assertThatThrownBy(() -> BedrockInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'aws.bedrock.thinking.type'")
        .hasMessageContaining("'bedrock.'");
  }

  @Test
  void an_unsupported_property_is_warned_once_at_build_and_the_provider_still_builds() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("bedrock.thinking.type", "enabled");
    var built = new BedrockInferenceProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            BedrockProperties.class, () -> built[0] = BedrockInferenceProvider.of(customizer));

    assertThat(LogCapture.warnings(events))
        .singleElement()
        .asString()
        .contains("'bedrock.thinking.type'")
        .contains("bedrock.inferenceConfig.topP");
    assertThat(built[0]).isNotNull();
    built[0].close();
  }

  @Test
  void a_bad_value_on_the_config_is_refused_at_build() {
    Customizer<BedrockProviderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("bedrock.inferenceConfig.temperature", "hot");

    assertThatThrownBy(() -> BedrockInferenceProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'bedrock.inferenceConfig.temperature'");
  }

  @Test
  void valid_properties_on_the_config_build_and_validate_cleanly() {
    BedrockInferenceProvider provider =
        BedrockInferenceProvider.of(
            c ->
                c.region(Region.US_EAST_1)
                    .credentialsProvider(CREDENTIALS)
                    .properties(
                        Map.of(
                            "bedrock.inferenceConfig.temperature", "0.2",
                            "bedrock.inferenceConfig.topP", "0.5")));
    InferenceOptions options = new InferenceOptions("m", 512, Map.of("other.x", "1"));
    try {
      assertThatCode(() -> provider.validate(options)).doesNotThrowAnyException();
    } finally {
      provider.close();
    }
  }

  /**
   * The transport timeout setter (design record 2026-09-25-locks-as-plumbing-design.md §5). Unlike
   * OpenAI, Anthropic and Gemini, AWS's own {@code ClientOverrideConfiguration.apiCallTimeout()} is
   * publicly readable back off a real {@code BedrockRuntimeAsyncClient} -- but this module's {@link
   * BedrockClient} seam intentionally never hands that raw client back out (it exists precisely so
   * the provider need not depend on it directly), so reaching it from here would mean reflection or
   * widening internal visibility beyond what this setter needs. Per the same "not observable
   * without reflection or a network call" rule as the other three providers, these tests assert on
   * the setter's own validation and that building still succeeds.
   */
  @Nested
  @DisplayName("its transport timeout")
  class Its_transport_timeout {

    @Test
    void a_null_timeout_is_rejected() {
      assertThatThrownBy(
              () ->
                  BedrockInferenceProvider.of(
                      c ->
                          c.region(Region.US_EAST_1)
                              .credentialsProvider(CREDENTIALS)
                              .timeout(null)))
          .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_zero_timeout_is_rejected() {
      assertThatThrownBy(
              () ->
                  BedrockInferenceProvider.of(
                      c ->
                          c.region(Region.US_EAST_1)
                              .credentialsProvider(CREDENTIALS)
                              .timeout(Duration.ZERO)))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_negative_timeout_is_rejected() {
      assertThatThrownBy(
              () ->
                  BedrockInferenceProvider.of(
                      c ->
                          c.region(Region.US_EAST_1)
                              .credentialsProvider(CREDENTIALS)
                              .timeout(Duration.ofSeconds(-1))))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_positive_timeout_builds_a_provider_that_closes_its_own_client() {
      BedrockInferenceProvider provider =
          BedrockInferenceProvider.of(
              c ->
                  c.region(Region.US_EAST_1)
                      .credentialsProvider(CREDENTIALS)
                      .timeout(Duration.ofMinutes(6)));

      assertThat(provider.name()).isEqualTo("Bedrock");
      assertThatCode(provider::close).doesNotThrowAnyException();
    }

    @Test
    void a_timeout_alongside_a_supplied_client_leaves_that_client_unclosed_here() {
      AtomicBoolean closed = new AtomicBoolean();
      BedrockRuntimeAsyncClient theirs =
          (BedrockRuntimeAsyncClient)
              Proxy.newProxyInstance(
                  BedrockRuntimeAsyncClient.class.getClassLoader(),
                  new Class<?>[] {BedrockRuntimeAsyncClient.class},
                  (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                      closed.set(true);
                    }
                    return null;
                  });

      BedrockInferenceProvider.of(c -> c.client(theirs).timeout(Duration.ofMinutes(6))).close();

      assertThat(closed).isFalse();
    }
  }
}
