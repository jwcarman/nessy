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
package org.jwcarman.nessy.embedding.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Customizer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * No embedder property is supported yet: one under its own prefix is ignored with a warning;
 * another prefix is a mistake.
 */
class BedrockEmbedderConfigTest {

  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("akid", "secret"));

  @Test
  void a_property_under_its_own_prefix_builds() {
    assertThatCode(
            () ->
                BedrockEmbeddingProvider.of(
                        c ->
                            c.region(Region.US_EAST_1)
                                .credentialsProvider(CREDENTIALS)
                                .property("bedrock.truncate", "END"))
                    .close())
        .doesNotThrowAnyException();
  }

  @Test
  void a_property_under_another_prefix_is_refused_at_build_naming_the_prefix() {
    Customizer<BedrockEmbedderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .properties(Map.of("openai.user", "tenant-42"));

    assertThatThrownBy(() -> BedrockEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'openai.user'")
        .hasMessageContaining("'bedrock.'");
  }

  @Test
  void a_blank_value_is_refused_naming_the_property() {
    Customizer<BedrockEmbedderConfig> customizer =
        c ->
            c.region(Region.US_EAST_1)
                .credentialsProvider(CREDENTIALS)
                .property("bedrock.truncate", " ");

    assertThatThrownBy(() -> BedrockEmbeddingProvider.of(customizer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("'bedrock.truncate'");
  }

  @Test
  void a_property_under_its_own_prefix_is_warned_once_at_build_naming_it_and_no_supported_names() {
    var built = new BedrockEmbeddingProvider[1];

    List<ILoggingEvent> events =
        LogCapture.during(
            BedrockEmbedderConfig.class,
            () ->
                built[0] =
                    BedrockEmbeddingProvider.of(
                        c ->
                            c.region(Region.US_EAST_1)
                                .credentialsProvider(CREDENTIALS)
                                .property("bedrock.truncate", "x")));

    assertThat(LogCapture.warnings(events))
        .containsExactly(
            "NESSY EMBEDDING: property 'bedrock.truncate' is not supported by bedrock and is ignored;"
                + " supported: []");
    built[0].close();
  }
}
