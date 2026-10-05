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
package org.jwcarman.nessy.engine.effect;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The question an approver was asked, as a document: built field by field, never by serializing the
 * request, because the request holds the reply token and a stored document is read by far more than
 * the one who may reply.
 */
class ApprovalQuestionsTest {

  private static final UUID AGENT = UUID.fromString("01999999-0000-7000-8000-0000000000aa");
  private static final UUID KEY = UUID.fromString("01999999-0000-7000-8000-000000000001");
  private static final Instant ASKED = Instant.parse("2026-09-08T12:00:00Z");
  private static final Instant DEADLINE = Instant.parse("2026-09-08T12:10:00Z");
  private static final String TOKEN = "tok-9f3b7c1e-not-for-storage";

  private static ApprovalRequest request(String arguments) {
    return new ApprovalRequest(
        new AgentType("gated"),
        new AgentId(AGENT),
        new TurnId(3),
        new CallId("c1"),
        IdempotencyKey.of(KEY),
        new ToolName("lookup"),
        arguments,
        "look up loch ness",
        ASKED,
        DEADLINE,
        new ReplyToken(TOKEN));
  }

  @Test
  void the_document_names_the_agent_the_call_and_the_question() {
    ApprovalRequest request = request("{\"q\":\"loch ness\"}").fact("risk", "low");

    JsonNode document = ApprovalQuestions.document(request);

    assertThat(document.get("agentType").asString()).isEqualTo("gated");
    assertThat(document.get("agentId").asString()).isEqualTo(AGENT.toString());
    assertThat(document.get("turn").asLong()).isEqualTo(3L);
    assertThat(document.get("callId").asString()).isEqualTo("c1");
    assertThat(document.get("idempotencyKey").asString()).isEqualTo(KEY.toString());
    assertThat(document.get("toolName").asString()).isEqualTo("lookup");
    assertThat(document.get("arguments").get("q").asString()).isEqualTo("loch ness");
    assertThat(document.get("action").asString()).isEqualTo("look up loch ness");
    assertThat(document.get("askedAt").asString()).isEqualTo("2026-09-08T12:00:00Z");
    assertThat(document.get("deadline").asString()).isEqualTo("2026-09-08T12:10:00Z");
    assertThat(document.get("facts").get("risk").asString()).isEqualTo("low");
  }

  @Test
  void the_reply_token_is_not_in_it() {
    JsonNode document = ApprovalQuestions.document(request("{\"q\":\"loch ness\"}"));

    assertThat(document.toString()).doesNotContain(TOKEN);
  }

  @Test
  void the_arguments_are_a_document_not_text() {
    JsonNode document = ApprovalQuestions.document(request("{\"q\":\"loch ness\",\"n\":[1,2]}"));

    assertThat(document.get("arguments").isObject()).isTrue();
    assertThat(document.get("arguments").get("n").size()).isEqualTo(2);
  }

  /**
   * The arguments were read into the tool's input type before a question exists, so this is not
   * expected; if it happens the question is still kept, with what the model wrote.
   */
  @Test
  void arguments_that_do_not_parse_are_kept_as_the_text_they_are() {
    JsonNode document = ApprovalQuestions.document(request("{\"q\": "));

    assertThat(document.get("arguments").isString()).isTrue();
    assertThat(document.get("arguments").asString()).isEqualTo("{\"q\": ");
  }

  /** An approver adds facts while deciding; a document stored earlier must not change under it. */
  @Test
  void a_fact_added_after_the_document_was_built_is_not_in_it() {
    ApprovalRequest request = request("{\"q\":\"loch ness\"}").fact("risk", "low");
    JsonNode document = ApprovalQuestions.document(request);

    request.fact("later", JsonNodeFactory.instance.stringNode("added"));

    assertThat(document.get("facts").has("later")).isFalse();
    assertThat(document.get("facts").has("risk")).isTrue();
  }

  @Test
  void the_fields_come_in_a_fixed_order() {
    JsonNode document = ApprovalQuestions.document(request("{\"q\":\"loch ness\"}"));

    assertThat(document.propertyNames())
        .containsExactlyElementsOf(
            List.of(
                "agentType",
                "agentId",
                "turn",
                "callId",
                "idempotencyKey",
                "toolName",
                "arguments",
                "action",
                "askedAt",
                "deadline",
                "facts"));
  }
}
