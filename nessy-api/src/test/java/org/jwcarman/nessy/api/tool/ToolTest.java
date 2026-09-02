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
package org.jwcarman.nessy.api.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;

@DisplayName("Something the model can call")
class ToolTest {

  record Args(@JsonPropertyDescription("who to greet") String name) {}

  /** A tool that never overrides {@link Tool#inputSchema()}, so the default is what runs. */
  private static final class Greeter implements Tool<Args> {
    @Override
    public Class<Args> inputType() {
      return Args.class;
    }

    @Override
    public String name() {
      return "greet";
    }

    @Override
    public String description() {
      return "greets someone";
    }

    @Override
    public Awaited<ToolResult> execute(ToolCallRequest<Args> call) {
      return Awaited.ready(ToolResult.ok("hi " + call.input().name()));
    }
  }

  @Test
  @DisplayName("a tool that declares only its input type gets a schema generated from it, for free")
  void the_default_schema_is_generated_from_the_input_type() {
    Tool<Args> tool = new Greeter();

    assertThat(tool.inputSchema()).isEqualTo(Schemas.of(Args.class));
  }
}
