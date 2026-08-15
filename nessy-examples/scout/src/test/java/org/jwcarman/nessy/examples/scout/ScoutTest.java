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
package org.jwcarman.nessy.examples.scout;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.testing.ScriptedModelProvider;

/**
 * Proves rather than asserts: {@link Scout#scout} — the exact construction seam {@link Scout#main}
 * runs — grants three real MCP tools through the real {@code ToolGrant}/{@code UsagePolicy} door,
 * over a real in-process MCP server shaped like DeepWiki's own ({@link DeepWikiTestServer}), and
 * the gated ones stay gated. DeepWiki itself is never touched here — see the module README for the
 * live-server verification of the tool names this test's fixture reuses.
 */
class ScoutTest {

  private static ObjectNode repoArgs(String repoName) {
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.put("repoName", repoName);
    return arguments;
  }

  private static ObjectNode questionArgs(String repoName, String question) {
    ObjectNode arguments = repoArgs(repoName);
    arguments.put("question", question);
    return arguments;
  }

  @Nested
  class The_allow_granted_tools {

    @Test
    void read_wiki_structure_runs_through_the_real_executor_and_its_answer_lands() {
      AtomicInteger askQuestionCalls = new AtomicInteger();
      try (DeepWikiTestServer fixture =
          DeepWikiTestServer.open(
              (exchange, request) -> {
                askQuestionCalls.incrementAndGet();
                return DeepWikiTestServer.okResult("should never be reached");
              })) {
        ScriptedModelProvider provider =
            ScriptedModelProvider.builder()
                .toolUse("c1", DeepWikiTestServer.READ_WIKI_STRUCTURE, repoArgs("jwcarman/nessy"))
                .endWithToolUse()
                .text("Here's the structure.")
                .endTurn()
                .build();
        Harness harness = Nessy.harness(provider).build();
        // A denying approver, on purpose: an allow()-granted tool must run regardless of what
        // the approver would have said, which is the other half of proving the gate actually
        // gates (see The_require_approval_granted_tool below).
        Agent<String> agent =
            Scout.scout(harness, fixture.toolbox(), "fake-model", Approver.denyAll("never asked"))
                .agent();
        Conversation<String> conversation = agent.converse();

        RunOutcome outcome = conversation.tell("what's the structure of jwcarman/nessy?");

        assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
        ToolResultBlock result = toolResultAt(agent, conversation);
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("structure: jwcarman/nessy");
        assertThat(askQuestionCalls).hasValue(0);
      }
    }
  }

  @Nested
  class The_require_approval_granted_tool {

    @Test
    void ask_question_is_blocked_by_a_declining_approver_before_the_server_ever_sees_it() {
      AtomicInteger askQuestionCalls = new AtomicInteger();
      try (DeepWikiTestServer fixture =
          DeepWikiTestServer.open(
              (exchange, request) -> {
                askQuestionCalls.incrementAndGet();
                return DeepWikiTestServer.okResult("should never be reached");
              })) {
        ScriptedModelProvider provider =
            ScriptedModelProvider.builder()
                .toolUse(
                    "c1",
                    DeepWikiTestServer.ASK_QUESTION,
                    questionArgs("jwcarman/nessy", "how does the reducer work?"))
                .endWithToolUse()
                .text("I wasn't allowed to ask.")
                .endTurn()
                .build();
        Harness harness = Nessy.harness(provider).build();
        Agent<String> agent =
            Scout.scout(
                    harness,
                    fixture.toolbox(),
                    "fake-model",
                    Approver.denyAll("declined by policy"))
                .agent();
        Conversation<String> conversation = agent.converse();

        RunOutcome outcome = conversation.tell("how does the reducer work in jwcarman/nessy?");

        // The gate blocks the call before the server ever sees it: the conversation still
        // completes (the model gets the denial as a normal tool result and answers around it),
        // but the fixture's handler — proof the request never reached DeepWiki — was never run.
        assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
        assertThat(askQuestionCalls).hasValue(0);
        ToolResultBlock result = toolResultAt(agent, conversation);
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).isEqualTo("Denied: declined by policy");
      }
    }

    @Test
    void ask_question_reaches_the_server_and_the_answer_lands_when_approved() {
      AtomicInteger askQuestionCalls = new AtomicInteger();
      try (DeepWikiTestServer fixture =
          DeepWikiTestServer.open(
              (exchange, request) -> {
                askQuestionCalls.incrementAndGet();
                return DeepWikiTestServer.okResult("the reducer lives in one method for locality");
              })) {
        ScriptedModelProvider provider =
            ScriptedModelProvider.builder()
                .toolUse(
                    "c1",
                    DeepWikiTestServer.ASK_QUESTION,
                    questionArgs("jwcarman/nessy", "how does the reducer work?"))
                .endWithToolUse()
                .text("Here's what DeepWiki said.")
                .endTurn()
                .build();
        Harness harness = Nessy.harness(provider).build();
        // The demo's headline beat: approving the gate actually lets the call through to the
        // remote server, and its answer flows back into context — the mirror image of the
        // declining-approver case above, which only proves the gate can say no.
        Agent<String> agent =
            Scout.scout(harness, fixture.toolbox(), "fake-model", Approver.allowAll()).agent();
        Conversation<String> conversation = agent.converse();

        RunOutcome outcome = conversation.tell("how does the reducer work in jwcarman/nessy?");

        assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
        assertThat(askQuestionCalls).hasValue(1);
        ToolResultBlock result = toolResultAt(agent, conversation);
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).isEqualTo("the reducer lives in one method for locality");
      }
    }
  }

  /** The one tool-result block a single tool-use turn produces, at its fixed message index. */
  private static ToolResultBlock toolResultAt(
      Agent<String> agent, Conversation<String> conversation) {
    return (ToolResultBlock)
        agent.contextFor(conversation.conversationId()).messages().get(2).content().getFirst();
  }
}
