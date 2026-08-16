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
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
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
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.conversation.Usage;
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
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.jwcarman.nessy.spi.plan.PlanTools;
import org.jwcarman.nessy.spi.subagent.AgentTools;
import org.jwcarman.nessy.spi.subagent.CallbackRouter;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The offline smoke test spec §8 asked for by name ("{@code ScriptedModelProvider} on both agents
 * driving the full park-and-wake chain offline") and the one every comparable example ships
 * (dispatcher, order-desk, scout) but the newsroom never got: {@link NewsroomRepl}'s own headless
 * constructor (the testability seam docs itself as existing "specifically for a headless test that
 * was never written" — final review SF-3), driven end to end over piped console I/O and a scripted
 * model, with no real database and no mocking library.
 *
 * <p>Wiring here mirrors {@link NewsroomAgents#agentsFor} — same tools, same gate, same shared
 * {@link SubjectId} — but swaps every {@code nessy-jdbc} door for its in-memory default, since
 * there is no in-memory-harness path through {@code agentsFor} itself (it takes a {@code
 * DataSource}); duplicating the dozen lines of tool/memory wiring here is cheaper than adding one.
 *
 * <p>One {@link ScriptedModelProvider} is shared by both agents, exactly as the real harness shares
 * one {@link org.jwcarman.nessy.spi.model.ModelProvider} — the turns queue in the order the loop
 * actually calls the model, regardless of which agent asks: the writer delegates, the researcher
 * asks a clarifying question and parks, the console approves it, the researcher's answer completes
 * it, {@link AgentTools#completions} wakes the writer synchronously inside that same {@code
 * approve} call, and the writer's own final turn prints.
 */
class NewsroomReplSmokeTest {

  private static ObjectNode taskArguments(String task) {
    return JsonNodeFactory.instance.objectNode().put("task", task);
  }

  private static ObjectNode questionArguments(String question) {
    return JsonNodeFactory.instance.objectNode().put("question", question);
  }

  @Test
  void one_delegation_parks_gets_approved_wakes_the_writer_and_prints_the_final_answer() {
    ScriptedModelProvider provider =
        new ScriptedModelProvider()
            .turn(
                new ModelEvent.ToolUseEmitted(
                    new ToolCall("d1", "researcher", taskArguments("look into octopuses"))),
                endWithToolUse())
            .turn(
                new ModelEvent.ToolUseEmitted(
                    new ToolCall(
                        "q1",
                        "ask_question",
                        questionArguments("which octopus fact do you want?"))),
                endWithToolUse())
            .turn(new ModelEvent.TextChunk("octopuses have three hearts"), endTurn())
            .turn(new ModelEvent.TextChunk("story filed: octopuses have three hearts"), endTurn());

    ConversationStore store = ConversationStore.inMemory();
    Parks parks = Parks.inMemory();
    Transcript transcript = Transcript.inMemory();
    Notebook notebook = Notebook.inMemory();
    PlanStore planStore = PlanStore.inMemory();
    SubagentLinks links = SubagentLinks.inMemory();
    CallbackRouter router = new CallbackRouter();
    PendingAnswers pendingAnswers = new PendingAnswers();
    SubjectId subject = new SubjectId("newsroom-smoke-test");
    Function<ConversationId, SubjectId> subjectResolver = id -> subject;

    Harness harness =
        Nessy.harness(provider)
            .store(store)
            .parks(parks)
            .listen(ConversationSettled.class, AgentTools.completions(links, parks, router))
            .build();

    Agent<String> researcher =
        harness
            .agent()
            .name("researcher")
            .model("test-model")
            .tools(
                ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
                ToolGrant.grant(new AskQuestionTool(pendingAnswers), UsagePolicy.requireApproval()))
            .approver(Approver.parkAll())
            .memory(
                Memory.pipeline(transcript)
                    .transform(NotebookTools.transformer(notebook, subjectResolver))
                    .build())
            .build();

    Agent<String> writer =
        harness
            .agent()
            .name("writer")
            .model("test-model")
            .tools(
                ToolGrant.grant(PlanTools.updatePlan(planStore), UsagePolicy.allow()),
                ToolGrant.grant(
                    AgentTools.subagent(researcher, "Delegates research to the researcher.", links),
                    UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.remember(notebook, subjectResolver), UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.recall(notebook, subjectResolver), UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.forget(notebook, subjectResolver), UsagePolicy.allow()))
            .memory(
                Memory.pipeline(transcript)
                    .transform(PlanTools.transformer(planStore))
                    .transform(NotebookTools.transformer(notebook, subjectResolver))
                    .build())
            .build();

    router.register(writer);
    router.register(researcher);

    NewsroomAgents.Built built =
        new NewsroomAgents.Built(writer, researcher, planStore, pendingAnswers);
    BufferedReader input =
        new BufferedReader(
            new StringReader("look into octopuses\ny\noctopuses have three hearts\nexit\n"));
    StringWriter output = new StringWriter();
    NewsroomRepl repl = new NewsroomRepl(built, input, output);

    repl.run();

    String transcriptText = output.toString();
    assertThat(transcriptText)
        .contains("which octopus fact do you want?")
        .contains("story filed: octopuses have three hearts")
        .contains("goodbye.");
    assertThat(writer.snapshot(NewsroomAgents.WRITER_CONVERSATION_ID).status())
        .isEqualTo(ConversationStatus.COMPLETE);
    assertThat(
            links.find(new ConversationId(NewsroomAgents.WRITER_CONVERSATION_ID.value() + "/d1")))
        .isEmpty();
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
