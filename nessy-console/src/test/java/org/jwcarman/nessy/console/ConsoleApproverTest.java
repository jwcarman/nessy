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
package org.jwcarman.nessy.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCall;

@DisplayName("Asking the person at the terminal")
class ConsoleApproverTest {

  private static final ApprovalRequest SENDING_MAIL =
      new ApprovalRequest(
          AgentType.of("chat"),
          AgentId.of("cli"),
          new ToolCall("c1", "send_email", JsonNodeFactory.instance.objectNode()),
          "Send an email to jim@example.com",
          Instant.parse("2026-08-31T12:00:00Z"));

  /** Never used: this approver answers on the spot, so there is nothing to reply to later. */
  private static final ReplyToken UNUSED = ReplyToken.of("unused");

  private static ApprovalResult answerOf(FakeConsole console) {
    Awaited<ApprovalResult> answer =
        new ConsoleApprover(console).approve(SENDING_MAIL, () -> UNUSED);

    assertThat(answer).isInstanceOf(Awaited.Ready.class);
    return ((Awaited.Ready<ApprovalResult>) answer).result();
  }

  @Test
  void y_allows_it() {
    assertThat(answerOf(new FakeConsole("y"))).isInstanceOf(ApprovalResult.Approved.class);
  }

  @Test
  void yes_allows_it_too_whatever_the_case() {
    assertThat(answerOf(new FakeConsole("YES"))).isInstanceOf(ApprovalResult.Approved.class);
  }

  @Test
  @DisplayName("anything else is a no, because the default has to be the safe one")
  void anything_else_denies() {
    assertThat(answerOf(new FakeConsole("n"))).isInstanceOf(ApprovalResult.Denied.class);
    assertThat(answerOf(new FakeConsole(""))).isInstanceOf(ApprovalResult.Denied.class);
    assertThat(answerOf(new FakeConsole("maybe"))).isInstanceOf(ApprovalResult.Denied.class);
  }

  @Test
  @DisplayName("end of input denies: silence is not consent")
  void nobody_there_denies() {
    ApprovalResult result = answerOf(new FakeConsole());

    assertThat(result)
        .isInstanceOfSatisfying(
            ApprovalResult.Denied.class,
            denied -> assertThat(denied.reason()).contains("nobody at the terminal"));
  }

  @Test
  @DisplayName("it shows what it is asking about, not just that it is asking")
  void the_question_names_the_action() {
    FakeConsole console = new FakeConsole("y");

    answerOf(console);

    assertThat(console.written()).contains("Send an email to jim@example.com").contains("[y/N]");
  }

  /** A question appended to a half-written answer reads as part of it. */
  @Test
  void it_starts_on_a_line_of_its_own() {
    FakeConsole console = new FakeConsole("y");

    answerOf(console);

    assertThat(console.written()).startsWith(System.lineSeparator());
  }
}
