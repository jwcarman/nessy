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
package org.jwcarman.nessy.agent.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.support.ScriptedModelProvider;
import org.jwcarman.nessy.agent.support.TestSettings;
import org.jwcarman.nessy.spi.model.ModelEvent;

class CliAgentTest {

  @Test
  void helloWorldEndToEnd() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("Hello "), new ModelEvent.TextChunk("back!"))));
    try (var agent = Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      assertThat(agent.converse("hello")).isEqualTo("Hello back!");
    }
  }

  @Test
  void twoTurnsShareOneMemory() throws Exception {
    var provider =
        new ScriptedModelProvider(
            List.of(
                List.of(new ModelEvent.TextChunk("one")),
                List.of(new ModelEvent.TextChunk("two"))));
    try (var agent = Nessy.cli().provider(provider).settings(TestSettings.settings()).build()) {
      agent.converse("first");
      agent.converse("second");
      // the second request's context carries the whole first exchange plus the new user turn
      assertThat(provider.requests()).hasSize(2);
      assertThat(provider.requests().get(1).context().messages()).hasSize(3);
    }
  }
}
