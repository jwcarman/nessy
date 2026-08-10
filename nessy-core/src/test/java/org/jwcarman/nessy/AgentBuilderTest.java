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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/** {@code AgentBuilder.tools(ToolGrant...)}'s own validation, isolated from the full build path. */
class AgentBuilderTest {

  private static final ModelProvider NEVER_CALLED =
      new ModelProvider() {
        @Override
        public ModelStream stream(ModelRequest request) {
          throw new AssertionError("never called");
        }

        @Override
        public Set<Capability> capabilities() {
          return Set.of();
        }
      };

  record Nothing() {}

  private static final class NoOpTool implements Tool<Nothing> {
    @Override
    public String name() {
      return "noop";
    }

    @Override
    public String description() {
      return "Does nothing";
    }

    @Override
    public Class<Nothing> inputType() {
      return Nothing.class;
    }

    @Override
    public Awaited<ToolResult> execute(Nothing input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("done"));
    }
  }

  @Nested
  class Tools_by_grant {

    @Test
    void a_null_grants_array_is_rejected() {
      ToolGrant[] grants = null;

      assertThatThrownBy(() -> Nessy.harness(NEVER_CALLED).build().agent().tools(grants))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants");
    }

    @Test
    void a_null_element_in_the_grants_array_is_rejected() {
      ToolGrant present = ToolGrant.grant(new NoOpTool(), UsagePolicy.allow());

      assertThatThrownBy(() -> Nessy.harness(NEVER_CALLED).build().agent().tools(present, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("grants[1]");
    }
  }
}
