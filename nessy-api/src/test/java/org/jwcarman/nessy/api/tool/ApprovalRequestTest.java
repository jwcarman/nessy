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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;

/**
 * The question put to an approver.
 *
 * <p>{@code facts} is the slot an approver annotates: a risk assessment, a resolved principal, a
 * quota check. This is where risk-based gating will read from, so what it promises about names and
 * replacement is worth pinning down before anything depends on it.
 */
@DisplayName("The question an approver is asked")
class ApprovalRequestTest {

  private static final AgentType WATCHMAN = AgentType.of("watchman");
  private static final AgentId HOUSE = AgentId.of("house-12");
  private static final Instant ASKED = Instant.parse("2026-09-01T12:00:00Z");

  private static ToolCallRequest call() {
    return new ToolCallRequest(
        WATCHMAN,
        HOUSE,
        "turn-1",
        "c1",
        "prune_images",
        JsonNodeFactory.instance.objectNode(),
        ReplyToken.of("token-1"));
  }

  private static ApprovalRequest asked() {
    return new ApprovalRequest(call(), "docker image prune -af", ASKED);
  }

  @Nested
  class AsFirstAsked {

    @Test
    void it_carries_who_is_asking_and_what_about() {
      ApprovalRequest request = asked();

      assertThat(request.call().agentType()).isEqualTo(WATCHMAN);
      assertThat(request.call().agentId()).isEqualTo(HOUSE);
      assertThat(request.call().toolName()).isEqualTo("prune_images");
      assertThat(request.description()).isEqualTo("docker image prune -af");
      assertThat(request.askedAt()).isEqualTo(ASKED);
    }

    @Test
    @DisplayName("the reply token reaches through, so an approver needs one object")
    void it_carries_where_an_answer_would_go() {
      assertThat(asked().replyToken()).isEqualTo(ReplyToken.of("token-1"));
    }

    @Test
    @DisplayName("the call key is the turn and the call, since a call id repeats across turns")
    void it_carries_a_key_a_tool_can_deduplicate_on() {
      assertThat(asked().call().callKey()).isEqualTo("turn-1/c1");
    }

    @Test
    @DisplayName("nothing has annotated it yet")
    void it_starts_with_no_facts() {
      assertThat(asked().facts()).isEmpty();
      assertThat(asked().fact("risk")).isEmpty();
    }
  }

  @Nested
  class BeingAnnotated {

    @Test
    void a_text_fact_reads_back() {
      ApprovalRequest request = asked().fact("principal", "jcarman");

      assertThat(request.fact("principal")).map(node -> node.asText()).contains("jcarman");
    }

    @Test
    void a_structured_fact_reads_back_whole() {
      var assessment = JsonNodeFactory.instance.objectNode();
      assessment.put("level", "HIGH");

      ApprovalRequest request = asked().fact("risk", assessment);

      assertThat(request.fact("risk")).map(node -> node.path("level").asText()).contains("HIGH");
    }

    @Test
    @DisplayName("annotating returns the request, so approvers can chain")
    void facts_accumulate() {
      ApprovalRequest request = asked().fact("principal", "jcarman").fact("quota.remaining", "3");

      assertThat(request.fact("principal")).isPresent();
      assertThat(request.fact("quota.remaining")).isPresent();
    }

    @Test
    @DisplayName("the same name twice replaces, so a later approver's word wins")
    void a_fact_recorded_twice_keeps_the_second() {
      ApprovalRequest request = asked().fact("risk", "LOW").fact("risk", "HIGH");

      assertThat(request.fact("risk")).map(node -> node.asText()).contains("HIGH");
    }
  }

  @Nested
  class Refusing {

    @Test
    void a_question_with_no_description_is_refused() {
      ToolCallRequest call = call();

      assertThatThrownBy(() -> new ApprovalRequest(call, null, ASKED))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("description");
    }

    @Test
    void a_question_about_no_call_is_refused() {
      assertThatThrownBy(() -> new ApprovalRequest(null, "something", ASKED))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("call");
    }

    @Test
    void a_fact_with_no_value_is_refused() {
      ApprovalRequest request = asked();

      assertThatThrownBy(() -> request.fact("risk", (String) null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("value");
    }

    @Test
    void a_fact_with_no_name_is_refused() {
      ApprovalRequest request = asked();

      assertThatThrownBy(() -> request.fact(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("name");
    }
  }
}
