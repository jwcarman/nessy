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
package org.jwcarman.nessy.model.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelRequest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;

class BedrockModelProviderTest {

  private static BedrockClient fakeClient(Object[] capturedArgs, BedrockStream response) {
    return request -> {
      capturedArgs[0] = request;
      return response;
    };
  }

  @Nested
  class Streaming {

    @Test
    void delegates_to_the_client_and_returns_its_stream_unchanged() {
      var capturedArgs = new Object[1];
      var response = new BedrockStream(List.of(), () -> {});
      var provider = new BedrockModelProvider(fakeClient(capturedArgs, response));
      var request =
          new ModelRequest(
              Context.of(List.of()),
              "sys",
              "us.anthropic.claude-haiku-4-5-20251001-v1:0",
              1024,
              List.of(),
              Set.of(),
              null);

      var stream = provider.stream(request);

      assertThat(stream).isSameAs(response);
      assertThat(capturedArgs[0]).isInstanceOf(ConverseStreamRequest.class);
      var captured = (ConverseStreamRequest) capturedArgs[0];
      assertThat(captured.modelId()).isEqualTo("us.anthropic.claude-haiku-4-5-20251001-v1:0");
      assertThat(captured.inferenceConfig().maxTokens()).isEqualTo(1024);
    }
  }

  @Nested
  class Builder {

    @Test
    void rejects_build_with_neither_a_region_nor_a_client() {
      var builder = BedrockModelProvider.builder();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("region")
          .hasMessageContaining("fromEnv")
          .hasMessageContaining("client");
    }

    @Test
    void a_region_alone_is_enough_to_build() {
      var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build();

      assertThat(provider).isNotNull();
    }

    @Test
    void from_env_fails_clearly_when_neither_variable_is_set_naming_both() {
      assumeTrue(System.getenv("AWS_REGION") == null, "AWS_REGION is set in this shell");
      assumeTrue(
          System.getenv("AWS_DEFAULT_REGION") == null, "AWS_DEFAULT_REGION is set in this shell");

      var builder = BedrockModelProvider.builder().fromEnv();

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("AWS_REGION")
          .hasMessageContaining("AWS_DEFAULT_REGION");
    }

    @Test
    void an_explicit_region_set_after_from_env_still_builds_without_needing_the_environment() {
      var provider = BedrockModelProvider.builder().fromEnv().region(Region.US_WEST_2).build();

      assertThat(provider).isNotNull();
    }
  }

  @Nested
  class Capabilities {

    @Test
    void v1_advertises_parallel_tool_calls_but_not_thinking_caching_or_image_input() {
      var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build();

      assertThat(provider.capabilities()).containsExactly(Capability.PARALLEL_TOOL_CALLS);
    }
  }

  @Nested
  class Name {

    @Test
    void reports_bedrock() {
      var provider = BedrockModelProvider.builder().region(Region.US_EAST_1).build();

      assertThat(provider.name()).isEqualTo("Bedrock");
    }
  }
}
