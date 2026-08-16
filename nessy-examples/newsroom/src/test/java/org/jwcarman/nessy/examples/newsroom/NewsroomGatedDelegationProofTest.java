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
package org.jwcarman.nessy.examples.newsroom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.Subagent;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.notebook.NotebookTools;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The generation's own signature proof (design of record 2026-08-16 §4): a delegation gated with
 * {@code UsagePolicy.requireApproval()} whose child itself parks — the two-wait lifecycle the
 * re-park fix exists for, driven end to end through the REAL example wiring's own construction
 * shape (design of record 2026-08-16 §1), offline, with scripted providers and no mocking library.
 *
 * <p>The timeline: the writer's model decides to delegate; the delegation tool's own {@code
 * requireApproval()} policy parks the writer FIRST, before the researcher is ever told anything
 * (park 1 — permission). Approving that park, through the writer's own doors, lets the delegation
 * tool actually run — the researcher is told its task, its model asks a clarifying question, and
 * that gated {@code ask_question} tool parks the researcher (park 2 — work); because the researcher
 * came back {@code PARKED} rather than settled, the writer's own delegation call parks a SECOND
 * time, under a fresh token linked to the researcher's (the parent re-parks, spec §4's "two waits,
 * not one"). Approving the researcher's own park — through {@link Agent#subagent(String)}, not a
 * held reference — settles the researcher; the harness's internally wired completions listener
 * wakes the writer synchronously inside that same call, and the writer's own follow-up turn
 * produces the final answer, carrying the researcher's own text.
 */
class NewsroomGatedDelegationProofTest {

  private static ObjectNode taskArguments(String task) {
    return JsonNodeFactory.instance.objectNode().put("task", task);
  }

  private static ObjectNode questionArguments(String question) {
    return JsonNodeFactory.instance.objectNode().put("question", question);
  }

  @Test
  void a_gated_delegation_whose_child_parks_settles_through_two_parks_and_one_approval_each() {
    ToolCall delegateCall = new ToolCall("d1", "researcher", taskArguments("look into octopuses"));
    ToolCall askQuestion =
        new ToolCall("q1", "ask_question", questionArguments("which octopus fact do you want?"));
    ScriptedModelProvider provider =
        new ScriptedModelProvider()
            .turn(new ModelEvent.ToolUseEmitted(delegateCall), endWithToolUse()) // writer turn 1
            .turn(new ModelEvent.ToolUseEmitted(askQuestion), endWithToolUse()) // researcher turn 1
            .turn(
                new ModelEvent.TextChunk("octopuses have three hearts"),
                endTurn()) // researcher turn 2, post-approval
            .turn(
                new ModelEvent.TextChunk("story filed: octopuses have three hearts"),
                endTurn()); // writer turn 2, woken by completion

    ConversationStore store = ConversationStore.inMemory();
    Parks parks = Parks.inMemory();
    Transcript transcript = Transcript.inMemory();
    Notebook notebook = Notebook.inMemory();
    SubagentLinks links = SubagentLinks.inMemory();
    PendingAnswers pendingAnswers = new PendingAnswers();
    SubjectId subject = new SubjectId("newsroom-gated-delegation-proof");
    Function<ConversationId, SubjectId> subjectResolver = id -> subject;

    Harness harness =
        Nessy.harness(h -> h.provider(provider).store(store).parks(parks).subagentLinks(links));

    Agent<String> writer =
        harness.agent(
            a ->
                a.name("writer")
                    .model("test-model")
                    .approver(Approver.parkAll())
                    .memory(
                        Memory.pipeline(transcript)
                            .transform(NotebookTools.transformer(notebook, subjectResolver))
                            .build())
                    .subagent(
                        sub ->
                            sub.name("researcher")
                                .description("Delegates research to the researcher.")
                                .model("test-model")
                                .policy(UsagePolicy.requireApproval())
                                .tools(
                                    ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
                                    ToolGrant.grant(
                                        new AskQuestionTool(pendingAnswers),
                                        UsagePolicy.requireApproval()))
                                .memory(
                                    Memory.pipeline(transcript)
                                        .transform(
                                            NotebookTools.transformer(notebook, subjectResolver))
                                        .build())));
    Subagent researcher = writer.subagent("researcher");

    RunOutcome delegationParked = writer.converse().tell("write about octopuses");

    assertThat(delegationParked).isInstanceOf(RunOutcome.Parked.class);
    ConversationId writerConversationId = delegationParked.state().id();
    ConversationId childConversationId = new ConversationId(writerConversationId.value() + "/d1");
    ParkToken permissionToken = onlyToken(writer.snapshot(writerConversationId));
    // Only the writer's own decision turn was ever sent — the researcher was never told anything
    // until the delegation itself was approved.
    assertThat(parks.find(permissionToken)).isPresent();
    assertThat(parks.find(permissionToken).orElseThrow().agentName()).isEqualTo("writer");

    RunOutcome reParked = writer.approve(permissionToken);

    assertThat(reParked).isInstanceOf(RunOutcome.Parked.class);
    // Parks never deletes an entry once registered, so both of the writer's own waits — the
    // permission park just resolved above, and the execution park this re-park just minted — are
    // on file for the same conversation; the execution park is whichever of the two is not the
    // permission token (design of record 2026-08-16 §4: "each wait minting its own token").
    List<Parks.Park> writerParks = parks.forConversation(writerConversationId);
    ParkToken executionToken =
        writerParks.stream()
            .map(Parks.Park::token)
            .filter(token -> !token.equals(permissionToken))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no second park was ever minted for the writer"));
    ParkToken questionToken = onlyToken(researcher.snapshot(childConversationId));
    assertThat(writerParks).hasSize(2);
    assertThat(executionToken).isNotEqualTo(permissionToken);
    assertThat(parks.find(executionToken)).isPresent();
    assertThat(parks.find(executionToken).orElseThrow().agentName()).isEqualTo("writer");
    // The second park (the execution wait) is the one still outstanding once the first (the
    // permission wait) has already been resolved and consumed by the execution it triggered.
    assertThat(writer.snapshot(writerConversationId).status()).isEqualTo(ConversationStatus.PARKED);

    researcher.approve(questionToken);

    assertThat(researcher.snapshot(childConversationId).status())
        .isEqualTo(ConversationStatus.COMPLETE);
    // The second park is resolved: the internally-wired completions listener drained it and woke
    // the writer synchronously inside the same approve() call, above.
    assertThat(writer.snapshot(writerConversationId).parkedCalls()).isEmpty();
    assertThat(writer.snapshot(writerConversationId).status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(lastAssistantText(writer.snapshot(writerConversationId)))
        .contains("octopuses have three hearts");
  }

  private static ParkToken onlyToken(ConversationSnapshot snapshot) {
    List<ParkedCall> parked = snapshot.parkedCalls();
    assertThat(parked).isNotEmpty();
    return parked.get(0).token();
  }

  private static String lastAssistantText(ConversationSnapshot snapshot) {
    List<Message> messages = snapshot.context().messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message message = messages.get(i);
      if (message.role() == Role.ASSISTANT) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
          if (block instanceof TextBlock(String blockText)) {
            text.append(blockText);
          }
        }
        return text.toString();
      }
    }
    return "";
  }

  private static ModelEvent.TurnEnded endTurn() {
    return new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero());
  }

  private static ModelEvent.TurnEnded endWithToolUse() {
    return new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero());
  }

  /**
   * A model that says exactly what it is told, one queued turn per call — shared by both agents,
   * consumed in the order the loop actually calls the model.
   */
  private static final class ScriptedModelProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

    ScriptedModelProvider turn(ModelEvent... events) {
      turns.addLast(List.of(events));
      return this;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }
}
