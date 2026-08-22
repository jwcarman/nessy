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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

class EventGrammarTest {

  private static ToolCall call(String id) {
    return new ToolCall(id, "lookup", JsonNodeFactory.instance.objectNode());
  }

  @Test
  void anObservationCarriesItsRenderedContent() {
    var observed = new AgentEvent.Observed(List.of(new TextBlock("hi")));
    assertThat(observed.content()).containsExactly(new TextBlock("hi"));
  }

  @Test
  void anObservationRejectsNullContent() {
    assertThatThrownBy(() -> new AgentEvent.Observed(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelCompletionWrapsExactlyOneOutcome() {
    var responded =
        new ModelOutcome.Responded(
            List.of(new TextBlock("ok")), List.of(), ModelResponseId.of("response-1"));
    assertThat(new AgentEvent.ModelFinished(responded).outcome()).isEqualTo(responded);
  }

  @Test
  void aModelCompletionRejectsANullOutcome() {
    assertThatThrownBy(() -> new AgentEvent.ModelFinished(null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelFailureCarriesItsReason() {
    assertThat(new ModelOutcome.Failed("overloaded").reason()).isEqualTo("overloaded");
  }

  @Test
  void aToolCompletionCarriesItsCallAndOutcome() {
    var outcome = new ToolOutcome.Returned(ToolResult.ok("42"));
    var finished = new AgentEvent.ToolFinished(call("c1"), outcome);
    assertThat(finished.call().id()).isEqualTo("c1");
    assertThat(finished.outcome()).isEqualTo(outcome);
  }

  @Test
  void aFailedToolOutcomeCarriesAnError() {
    var failed = new ToolOutcome.Failed(new ToolError("timed out"));
    assertThat(failed.error().message()).isEqualTo("timed out");
  }

  @Test
  void aToolErrorRejectsANullMessage() {
    assertThatThrownBy(() -> new ToolError(null)).isInstanceOf(NullPointerException.class);
  }
}
