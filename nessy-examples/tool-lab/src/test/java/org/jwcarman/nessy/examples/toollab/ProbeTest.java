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
package org.jwcarman.nessy.examples.toollab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.inference.Failure;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ProbeTest {

  private static final InferenceOptions OPTIONS = new InferenceOptions("fake-model", 500);

  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

  private String printed() {
    return captured.toString(StandardCharsets.UTF_8);
  }

  @Nested
  class The_two_requests {

    @Test
    void share_a_history_that_called_a_tool_which_succeeded() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.saying("ok"))
              .then(ScriptedProvider.saying("ok"));

      Probe.run(provider, OPTIONS, out);

      List<InferenceRequest> requests = provider.requests();
      assertThat(requests).hasSize(2);
      for (InferenceRequest request : requests) {
        Exchange exchange = request.context().activeTurn().exchanges().getFirst();
        assertThat(exchange.calls().getFirst().name().value()).isEqualTo("jira_get_issue");
        assertThat(exchange.outcomes().getFirst()).isInstanceOf(ToolOutcome.Succeeded.class);
      }
      assertThat(requests.get(0).context()).isEqualTo(requests.get(1).context());
    }

    @Test
    void offer_only_a_different_tool_in_the_first_and_no_tools_in_the_second() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.saying("ok"))
              .then(ScriptedProvider.saying("ok"));

      Probe.run(provider, OPTIONS, out);

      List<InferenceRequest> requests = provider.requests();
      assertThat(requests.get(0).toolset().offers()).hasSize(1);
      assertThat(requests.get(0).toolset().offers().getFirst().name().value())
          .isEqualTo("slack_post_message");
      assertThat(requests.get(1).toolset().offers()).isEmpty();
    }
  }

  @Nested
  class The_report {

    @Test
    void says_accepted_for_a_request_the_vendor_answered() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(ScriptedProvider.saying("ok"))
              .then(new InferenceResult.Actions(List.of(new Block.ToolCall("c", "x", "{}"))));

      Probe.run(provider, OPTIONS, out);

      assertThat(printed()).contains("accepted").doesNotContain("rejected");
    }

    @Test
    void gives_the_vendors_message_for_a_request_it_rejected_by_fault_or_by_throwing() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .then(
                  new InferenceResult.Fault(new Failure.Rejected("tool jira_get_issue not found")))
              .thenThrow(new IllegalStateException("400 bad request: unknown tool"));

      Probe.run(provider, OPTIONS, out);

      assertThat(printed())
          .contains("rejected")
          .contains("tool jira_get_issue not found")
          .contains("400 bad request: unknown tool");
    }

    @Test
    void does_not_try_a_failed_request_again() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .thenThrow(new IllegalStateException("boom"))
              .thenThrow(new IllegalStateException("boom"));

      Probe.run(provider, OPTIONS, out);

      assertThat(provider.requests()).hasSize(2);
    }
  }
}
