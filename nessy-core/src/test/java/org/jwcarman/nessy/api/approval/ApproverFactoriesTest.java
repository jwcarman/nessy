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
package org.jwcarman.nessy.api.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.AuthorizationContext;

class ApproverFactoriesTest {

  private static final ConversationId CONVERSATION_ID = new ConversationId("s1");
  private static final ToolCall CALL =
      new ToolCall("c1", "anything", JsonNodeFactory.instance.objectNode());

  private final ApprovalRequest request =
      new ApprovalRequest(
          CONVERSATION_ID,
          CALL,
          AuthorizationContext.of(
              CONVERSATION_ID,
              "test-agent",
              CALL,
              ConversationState.newConversation(CONVERSATION_ID)),
          "anything()");

  @Nested
  class Allow_all {

    @Test
    void allows() {
      assertThat(Approver.allowAll().approve(request)).isEqualTo(Awaited.ready(Decision.allow()));
    }
  }

  @Nested
  class Deny_all {

    @Test
    void denies_with_its_reason() {
      assertThat(Approver.denyAll("read-only").approve(request))
          .isEqualTo(Awaited.ready(new Decision.Deny("read-only")));
    }
  }

  @Nested
  class Park_all {

    @Test
    void park_all_parks_every_request_with_a_fresh_token() {
      Approver approver = Approver.parkAll();

      Awaited<Decision> first = approver.approve(request);
      Awaited<Decision> second = approver.approve(request);

      assertThat(first).isInstanceOf(Awaited.Parked.class);
      assertThat(second).isInstanceOf(Awaited.Parked.class);
      assertThat(((Awaited.Parked<Decision>) first).token())
          .isNotEqualTo(((Awaited.Parked<Decision>) second).token());
    }
  }
}
