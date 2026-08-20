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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.event.ToolProgress;

class ToolContextTest {

  @Test
  void progress_emits_with_the_frameworks_own_ids() {
    List<Object> heard = new ArrayList<>();
    ToolCall call = new ToolCall("c1", "issue_coupon", JsonNodeFactory.instance.objectNode());
    ToolContext context = new ToolContext(call, heard::add);

    context.progress("halfway");

    assertThat(heard).containsExactly(new ToolProgress("c1", "halfway"));
  }
}
