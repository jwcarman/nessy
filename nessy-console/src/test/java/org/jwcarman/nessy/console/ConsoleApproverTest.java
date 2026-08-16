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
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;

class ConsoleApproverTest {

  private static final ConversationId CONVERSATION_ID = ConversationId.generate();
  private static final ToolCall CALL =
      new ToolCall("c1", "clock", JsonNodeFactory.instance.objectNode());

  private static final ApprovalRequest REQUEST =
      new ApprovalRequest(
          CONVERSATION_ID,
          CALL,
          AuthorizationContext.of(
              CONVERSATION_ID,
              "test-agent",
              CALL,
              ConversationState.newConversation(CONVERSATION_ID)),
          "read the current time");

  @AfterEach
  void clear_the_override_seam() {
    Ansi.overrideEnabled(null);
  }

  @Nested
  class A_y_answer {

    @Test
    void allows_the_call() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      ConsoleApprover approver =
          new ConsoleApprover(new BufferedReader(new StringReader("y\n")), out);

      Awaited<Decision> decision = approver.approve(REQUEST);

      assertThat(decision).isEqualTo(Awaited.ready(Decision.allow()));
      assertThat(out).hasToString("\napprove: read the current time\ny/n> ");
    }
  }

  @Nested
  class An_n_answer {

    @Test
    void denies_the_call_with_a_console_reason() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      ConsoleApprover approver =
          new ConsoleApprover(new BufferedReader(new StringReader("n\n")), out);

      Awaited<Decision> decision = approver.approve(REQUEST);

      assertThat(decision).isEqualTo(Awaited.ready(new Decision.Deny("declined at the console")));
      assertThat(out).hasToString("\napprove: read the current time\ny/n> ");
    }
  }

  @Nested
  class Garbage_input {

    @Test
    void reprompts_instead_of_treating_it_as_a_denial() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      ConsoleApprover approver =
          new ConsoleApprover(new BufferedReader(new StringReader("blah\ny\n")), out);

      Awaited<Decision> decision = approver.approve(REQUEST);

      assertThat(decision).isEqualTo(Awaited.ready(Decision.allow()));
      assertThat(out)
          .hasToString("\napprove: read the current time\ny/n> please answer y or n\ny/n> ");
    }
  }

  @Nested
  class End_of_input {

    @Test
    void is_treated_as_a_denial() {
      Ansi.overrideEnabled(false);
      StringWriter out = new StringWriter();
      ConsoleApprover approver = new ConsoleApprover(new BufferedReader(new StringReader("")), out);

      Awaited<Decision> decision = approver.approve(REQUEST);

      assertThat(decision).isEqualTo(Awaited.ready(new Decision.Deny("declined at the console")));
    }
  }
}
