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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("The direct door refuses a tool whose input is not an object")
class ToolInputSchemaRefusalTest {

  private static final Tool<String> ECHO =
      new Tool<>() {
        @Override
        public ToolName name() {
          return new ToolName("echo");
        }

        @Override
        public String description() {
          return "says it back";
        }

        @Override
        public Class<String> inputType() {
          return String.class;
        }

        @Override
        public Awaited<ToolResult> call(ToolCallRequest<String> request) {
          return Awaited.ready(ToolResult.ok(new Block.Text(request.input())));
        }
      };

  @Test
  void a_string_input_is_refused_when_the_harness_is_built() {
    DefaultDirectHarnessConfig<String> config =
        new DefaultDirectHarnessConfig<>(new AgentType("refuses"), ObservationRegistry.NOOP);
    config.tool(ECHO, t -> {});
    VictoolsJsonSchemaGenerator schemas = new VictoolsJsonSchemaGenerator();
    JsonMapper mapper = JsonMapper.builder().build();

    assertThatThrownBy(() -> config.bindings(schemas, mapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tool 'echo'")
        .hasMessageContaining("but it is type 'string'");
  }
}
