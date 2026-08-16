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
package org.jwcarman.nessy.internal.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.WrongAgentException;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;

class AgentToolsTest {

  /** A provider that replays one scripted turn (a list of {@link ModelEvent}) per call. */
  private static final class ScriptedProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    ScriptedProvider turn(ModelEvent... events) {
      turns.addLast(List.of(events));
      return this;
    }

    List<ModelRequest> requests() {
      return List.copyOf(requests);
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      requests.add(request);
      Iterator<ModelEvent> events = turns.removeFirst().iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  private static ModelEvent.TurnEnded endTurn() {
    return new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero());
  }

  private static ModelEvent.TurnEnded refused() {
    return new ModelEvent.TurnEnded(StopReason.REFUSAL, Usage.zero());
  }

  private static ModelEvent.TurnEnded endWithToolUse() {
    return new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero());
  }

  private static ObjectNode taskArguments(String task) {
    return JsonNodeFactory.instance.objectNode().put("task", task);
  }

  record AskInput(String question) {}

  /** The typed door's own wire shape (design of record 2026-08-16 §0.5, final review SF-4). */
  record ResearchRequest(String question, int depth) {}

  /** A tool that always succeeds once invoked — the gate is what parks, not the tool itself. */
  private static final class AskQuestionTool implements Tool<AskInput> {

    @Override
    public String name() {
      return "ask_question";
    }

    @Override
    public String description() {
      return "Asks a clarifying question";
    }

    @Override
    public Class<AskInput> inputType() {
      return AskInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(AskInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("answered: " + input.question()));
    }
  }

  record EchoInput(String text) {}

  /** An always-allowed tool, used only to give a turn something to narrate progress about. */
  private static final class EchoTool implements Tool<EchoInput> {

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Echoes its input";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("echo: " + input.text()));
    }
  }

  /** Parks the first call it is asked, remembering the token it handed out. */
  private static final class ParkingApprover implements Approver {

    private ParkToken token;

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      token = ParkToken.generate();
      return Awaited.parked(token);
    }

    ParkToken token() {
      return token;
    }
  }

  private static ToolContext contextFor(ConversationId parentId, ToolCall call) {
    return new ToolContext(parentId, call, EventEmitter.noop());
  }

  @Nested
  class Delegation_input {

    @Test
    void a_blank_task_is_rejected() {
      assertThatThrownBy(() -> new AgentTools.Delegation(" "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("task");
    }
  }

  @Nested
  class Tool_semantics {

    @Test
    void name_is_the_childs_own_name() {
      Agent<String> child =
          Nessy.harness(h -> h.provider(new ScriptedProvider()))
              .agent(a -> a.name("researcher").model("m"));

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.name()).isEqualTo("researcher");
    }

    @Test
    void description_is_the_one_given() {
      Agent<String> child =
          Nessy.harness(h -> h.provider(new ScriptedProvider()))
              .agent(a -> a.name("researcher").model("m"));

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.description()).isEqualTo("delegates research");
    }

    @Test
    void input_type_is_delegation() {
      Agent<String> child =
          Nessy.harness(h -> h.provider(new ScriptedProvider()))
              .agent(a -> a.name("researcher").model("m"));

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.inputType()).isEqualTo(AgentTools.Delegation.class);
    }

    @Test
    void effect_shows_the_task_text() {
      Agent<String> child =
          Nessy.harness(h -> h.provider(new ScriptedProvider()))
              .agent(a -> a.name("researcher").model("m"));

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.effect(new AgentTools.Delegation("look into the outage")))
          .isEqualTo("look into the outage");
    }
  }

  @Nested
  class Child_conversation_identity {

    /**
     * Task-3 fix round 1, must-fix 2: {@code execute} inspects {@link Agent#snapshot} before
     * telling anything, so a redelivered call under the same call id (every real transport is
     * at-least-once) never re-runs the child — only one turn is ever driven, and every replay
     * answers with that same turn's own text. Only a single {@code ScriptedProvider} turn is queued
     * here on purpose: if the implementation regressed to re-telling on replay, the second {@code
     * execute} would exhaust the script and throw.
     */
    @Test
    void two_executes_with_the_same_call_id_run_the_child_exactly_once() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("the answer"), endTurn());
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider)).agent(a -> a.name("researcher").model("m"));
      Tool<AgentTools.Delegation> tool =
          AgentTools.subagent(child, "delegates research", SubagentLinks.inMemory());
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("investigate"));
      ToolContext context = contextFor(parentId, call);
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());

      Awaited<ToolResult> first = tool.execute(new AgentTools.Delegation("investigate"), context);
      Awaited<ToolResult> second = tool.execute(new AgentTools.Delegation("investigate"), context);

      assertThat(first).isInstanceOf(Awaited.Ready.class);
      assertThat(second).isInstanceOf(Awaited.Ready.class);
      assertThat(((Awaited.Ready<ToolResult>) first).value().content()).isEqualTo("the answer");
      assertThat(((Awaited.Ready<ToolResult>) second).value().content()).isEqualTo("the answer");
      assertThat(child.contextFor(expectedChildId).messages()).hasSize(2);
    }
  }

  @Nested
  class Settlement_results {

    @Test
    void a_completed_child_answers_with_its_final_assistant_text() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("the answer is 42"), endTurn());
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider)).agent(a -> a.name("researcher").model("m"));
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("what is the answer"));
      ToolContext context = contextFor(ConversationId.generate(), call);

      Awaited<ToolResult> awaited =
          tool.execute(new AgentTools.Delegation("what is the answer"), context);

      assertThat(awaited).isInstanceOf(Awaited.Ready.class);
      ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("the answer is 42");
    }

    @Test
    void a_failed_child_answers_with_its_failure_reason() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("no"), refused());
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider)).agent(a -> a.name("researcher").model("m"));
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("do something unsafe"));
      ToolContext context = contextFor(ConversationId.generate(), call);

      Awaited<ToolResult> awaited =
          tool.execute(new AgentTools.Delegation("do something unsafe"), context);

      assertThat(awaited).isInstanceOf(Awaited.Ready.class);
      ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).containsIgnoringCase("refusal");
    }
  }

  @Nested
  class Parking {

    @Test
    void a_parked_child_mints_a_parent_token_and_saves_the_link() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      SubagentLinks links = SubagentLinks.inMemory();
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research", links);
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("ask around"));
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());

      Awaited<ToolResult> awaited =
          tool.execute(new AgentTools.Delegation("ask around"), contextFor(parentId, call));

      assertThat(awaited).isInstanceOf(Awaited.Parked.class);
      ParkToken parentToken = ((Awaited.Parked<ToolResult>) awaited).token();
      assertThat(parentToken).isNotEqualTo(approver.token());
      assertThat(links.find(expectedChildId)).contains(parentToken);
    }

    /**
     * Task-3 fix round 1, should-fix 4: a redelivered {@code execute} against a still-parked child
     * (same call id) must return the exact same parent token already on file — not mint a fresh
     * one, which would orphan the first token's park entry and reopen {@link
     * AgentTools#completions}'s race window on every replay. Only a single {@code ScriptedProvider}
     * turn is queued: the second {@code execute} must never re-tell the child at all.
     */
    @Test
    void re_executing_a_parked_delegation_returns_the_same_parent_token() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      SubagentLinks links = SubagentLinks.inMemory();
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research", links);
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("ask around"));
      ToolContext context = contextFor(parentId, call);
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());
      AgentTools.Delegation input = new AgentTools.Delegation("ask around");

      Awaited<ToolResult> first = tool.execute(input, context);
      Awaited<ToolResult> second = tool.execute(input, context);

      assertThat(first).isInstanceOf(Awaited.Parked.class);
      assertThat(second).isInstanceOf(Awaited.Parked.class);
      ParkToken firstToken = ((Awaited.Parked<ToolResult>) first).token();
      ParkToken secondToken = ((Awaited.Parked<ToolResult>) second).token();
      assertThat(secondToken).isEqualTo(firstToken);
      assertThat(links.find(expectedChildId)).contains(firstToken);
    }

    @Test
    void a_parked_child_with_no_links_store_throws_naming_the_missing_store() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");
      ToolContext context =
          contextFor(
              ConversationId.generate(), new ToolCall("call-1", "researcher", taskArguments("x")));
      AgentTools.Delegation input = new AgentTools.Delegation("ask around");

      assertThatThrownBy(() -> tool.execute(input, context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("SubagentLinks");
    }
  }

  /**
   * Final review SF-4: {@link AgentTools.TypedSubagentTool} duplicates {@link
   * AgentTools.SubagentTool}'s entire execute/settle/park recipe (verbatim except {@code T} riding
   * straight through instead of unwrapping {@link AgentTools.Delegation#task()}), but before this
   * class only the fresh-tell happy path was ever driven for the typed copy — its {@code PARKED}
   * replay arm, {@link AgentTools#subagentTyped}'s own {@code freshPark}, {@code existingPark}, and
   * {@code requireLinks} had zero coverage. This nested class mirrors {@link Parking} exactly, one
   * test each, over {@link ResearchRequest} instead of the degenerate {@link AgentTools.Delegation}
   * wrapper — the riskiest lines on the branch, since drift between the two copies would land here
   * first and go unnoticed without a suite that exercises both.
   */
  @Nested
  class Typed_door_parking {

    @Test
    void a_typed_parked_child_mints_a_parent_token_and_saves_the_link() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  ResearchRequest.class,
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      SubagentLinks links = SubagentLinks.inMemory();
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(child, ResearchRequest.class, "delegates research", links);
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode());
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());

      Awaited<ToolResult> awaited =
          tool.execute(new ResearchRequest("ask around", 1), contextFor(parentId, call));

      assertThat(awaited).isInstanceOf(Awaited.Parked.class);
      ParkToken parentToken = ((Awaited.Parked<ToolResult>) awaited).token();
      assertThat(parentToken).isNotEqualTo(approver.token());
      assertThat(links.find(expectedChildId)).contains(parentToken);
    }

    /**
     * Mirrors {@link Parking#re_executing_a_parked_delegation_returns_the_same_parent_token}: a
     * redelivered {@code execute} against a still-parked typed child must return the exact same
     * parent token already on file, not mint a fresh one. Only a single {@code ScriptedProvider}
     * turn is queued: the second {@code execute} must never re-tell the child at all.
     */
    @Test
    void re_executing_a_typed_parked_delegation_returns_the_same_parent_token() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  ResearchRequest.class,
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      SubagentLinks links = SubagentLinks.inMemory();
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(child, ResearchRequest.class, "delegates research", links);
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode());
      ToolContext context = contextFor(parentId, call);
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());
      ResearchRequest input = new ResearchRequest("ask around", 1);

      Awaited<ToolResult> first = tool.execute(input, context);
      Awaited<ToolResult> second = tool.execute(input, context);

      assertThat(first).isInstanceOf(Awaited.Parked.class);
      assertThat(second).isInstanceOf(Awaited.Parked.class);
      ParkToken firstToken = ((Awaited.Parked<ToolResult>) first).token();
      ParkToken secondToken = ((Awaited.Parked<ToolResult>) second).token();
      assertThat(secondToken).isEqualTo(firstToken);
      assertThat(links.find(expectedChildId)).contains(firstToken);
    }

    @Test
    void a_typed_parked_child_with_no_links_store_throws_naming_the_missing_store() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  ResearchRequest.class,
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(approver));
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(child, ResearchRequest.class, "delegates research", null);
      ToolContext context =
          contextFor(
              ConversationId.generate(),
              new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode()));
      ResearchRequest input = new ResearchRequest("ask around", 1);

      assertThatThrownBy(() -> tool.execute(input, context))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("SubagentLinks");
    }
  }

  /**
   * Final review SF-4: mirrors {@link Settlement_results} and {@link Child_conversation_identity}
   * over {@link ResearchRequest} instead of {@link AgentTools.Delegation} — the typed door's own
   * COMPLETE/FAILED replay arms and snapshot short-circuit idempotency.
   */
  @Nested
  class Typed_door_settlement {

    @Test
    void a_typed_completed_child_answers_with_its_final_assistant_text() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("the answer is 42"), endTurn());
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(ResearchRequest.class, a -> a.name("researcher").model("m"));
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(child, ResearchRequest.class, "delegates research", null);
      ToolCall call = new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode());
      ToolContext context = contextFor(ConversationId.generate(), call);

      Awaited<ToolResult> awaited =
          tool.execute(new ResearchRequest("what is the answer", 1), context);

      assertThat(awaited).isInstanceOf(Awaited.Ready.class);
      ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
      assertThat(result.isError()).isFalse();
      assertThat(result.content()).isEqualTo("the answer is 42");
    }

    @Test
    void a_typed_failed_child_answers_with_its_failure_reason() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("no"), refused());
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(ResearchRequest.class, a -> a.name("researcher").model("m"));
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(child, ResearchRequest.class, "delegates research", null);
      ToolCall call = new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode());
      ToolContext context = contextFor(ConversationId.generate(), call);

      Awaited<ToolResult> awaited =
          tool.execute(new ResearchRequest("do something unsafe", 1), context);

      assertThat(awaited).isInstanceOf(Awaited.Ready.class);
      ToolResult result = ((Awaited.Ready<ToolResult>) awaited).value();
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).containsIgnoringCase("refusal");
    }

    /**
     * Mirrors {@link Child_conversation_identity}: only a single {@code ScriptedProvider} turn is
     * queued, so if the typed tool regressed to re-telling on replay, the second {@code execute}
     * would exhaust the script and throw.
     */
    @Test
    void two_typed_executes_with_the_same_call_id_run_the_child_exactly_once() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("the answer"), endTurn());
      Agent<ResearchRequest> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(ResearchRequest.class, a -> a.name("researcher").model("m"));
      Tool<ResearchRequest> tool =
          AgentTools.subagentTyped(
              child, ResearchRequest.class, "delegates research", SubagentLinks.inMemory());
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", JsonNodeFactory.instance.objectNode());
      ToolContext context = contextFor(parentId, call);
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());
      ResearchRequest input = new ResearchRequest("investigate", 1);

      Awaited<ToolResult> first = tool.execute(input, context);
      Awaited<ToolResult> second = tool.execute(input, context);

      assertThat(first).isInstanceOf(Awaited.Ready.class);
      assertThat(second).isInstanceOf(Awaited.Ready.class);
      assertThat(((Awaited.Ready<ToolResult>) first).value().content()).isEqualTo("the answer");
      assertThat(((Awaited.Ready<ToolResult>) second).value().content()).isEqualTo("the answer");
      assertThat(child.contextFor(expectedChildId).messages()).hasSize(2);
    }
  }

  @Nested
  class Progress_pings {

    @Test
    void relays_a_ping_on_every_tool_call_the_child_requests() {
      ToolCall childCall = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse())
              .turn(new ModelEvent.TextChunk("done"), endTurn());
      Agent<String> child =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("researcher")
                          .model("m")
                          .tools(ToolGrant.grant(new EchoTool(), UsagePolicy.allow())));
      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");
      List<ToolProgress> heard = new ArrayList<>();
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("echo hi"));
      EventEmitter capturing =
          event -> {
            if (event instanceof ToolProgress progress) {
              heard.add(progress);
            }
          };
      ToolContext context = new ToolContext(ConversationId.generate(), call, capturing);

      tool.execute(new AgentTools.Delegation("echo hi"), context);

      // Exactly one ToolCallRequested narrates in this script (one tool call, one text turn) — an
      // implementation pinging on every TextDelta instead would over-produce and this would catch
      // it.
      assertThat(heard).hasSize(1);
      assertThat(heard.getFirst().message()).isEqualTo("researcher: echo");
    }
  }

  @Nested
  class Completions {

    @Test
    void a_settlement_with_no_saved_link_is_a_silent_no_op() {
      SubagentLinks links = SubagentLinks.inMemory();
      Parks parks = Parks.inMemory();
      CallbackRouter router = new CallbackRouter();
      ConversationId childId = ConversationId.generate();
      ConversationSettled event =
          new ConversationSettled(childId, ConversationStatus.COMPLETE, null, "x");

      assertThatCode(() -> AgentTools.completions(links, parks, router).accept(event))
          .doesNotThrowAnyException();
      assertThat(links.find(childId)).isEmpty();
    }

    /**
     * Task-3 fix round 1, must-fix 1: {@link Parks} never deletes an entry once registered, so a
     * present link whose park is absent can only mean the parent's own park has not landed
     * <em>yet</em> — the narrow race window between {@code execute}'s own {@code links.save} and
     * the parent loop's later {@code Parks.park}. Forgetting the link here (the old behaviour) was
     * a lost wakeup: the child had already settled and nothing would ever wake it again. The
     * corrected behaviour throws instead, and — critically — leaves the link in place so a
     * redelivery of this same settlement (the whole point of registering this consumer
     * synchronously) can succeed once the park has landed.
     */
    @Test
    void a_settlement_whose_park_has_not_registered_yet_throws_and_leaves_the_link_in_place() {
      ConversationId childId = ConversationId.generate();
      ParkToken parentToken = ParkToken.generate();
      SubagentLinks links = SubagentLinks.inMemory();
      links.save(childId, parentToken);
      Parks parks = Parks.inMemory();
      CallbackRouter router = new CallbackRouter();
      ConversationSettled event =
          new ConversationSettled(childId, ConversationStatus.COMPLETE, null, "x");
      var consumer = AgentTools.completions(links, parks, router);

      assertThatThrownBy(() -> consumer.accept(event))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(childId.value())
          .hasMessageContaining(parentToken.value());

      assertThat(links.find(childId)).contains(parentToken);
    }

    /**
     * The full offline park-and-wake chain: a scripted parent delegates to a scripted child whose
     * own tool is approval-gated, so the child parks and the parent parks with it (both stores
     * assert). Approving the child completes it, the completions listener (registered sync on the
     * child's own harness) wakes the parent, and the parent completes carrying the child's answer.
     */
    @Test
    void the_parent_parks_with_its_child_and_wakes_with_the_childs_answer_on_approval() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider childProvider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse())
              .turn(new ModelEvent.TextChunk("the answer is 42"), endTurn());
      ParkingApprover childApprover = new ParkingApprover();
      SubagentLinks links = SubagentLinks.inMemory();
      Parks parentParks = Parks.inMemory();
      CallbackRouter router = new CallbackRouter();
      Agent<String> child =
          Nessy.harness(
                  h ->
                      h.provider(childProvider)
                          .listen(
                              ConversationSettled.class,
                              AgentTools.completions(links, parentParks, router)))
              .agent(
                  a ->
                      a.name("researcher")
                          .model("child-model")
                          .tools(
                              ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
                          .approver(childApprover));
      Tool<AgentTools.Delegation> delegation =
          AgentTools.subagent(child, "delegates research", links);
      ToolCall delegationCall =
          new ToolCall("d1", "researcher", taskArguments("investigate the topic"));
      ScriptedProvider parentProvider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall), endWithToolUse())
              .turn(new ModelEvent.TextChunk("wrapped up"), endTurn());
      Harness parentHarness = Nessy.harness(h -> h.provider(parentProvider).parks(parentParks));
      Agent<String> writer =
          parentHarness.agent(
              a ->
                  a.name("writer")
                      .model("parent-model")
                      .tools(ToolGrant.grant(delegation, UsagePolicy.allow())));
      router.register(writer);

      RunOutcome parentOutcome = writer.converse().tell("investigate the topic");

      assertThat(parentOutcome).isInstanceOf(RunOutcome.Parked.class);
      ConversationId parentId = parentOutcome.state().id();
      ConversationId childId = new ConversationId(parentId.value() + "/" + delegationCall.id());
      Optional<ParkToken> parentToken = links.find(childId);
      assertThat(parentToken).isPresent();
      assertThat(parentParks.find(parentToken.orElseThrow()))
          .isPresent()
          .get()
          .satisfies(park -> assertThat(park.agentName()).isEqualTo("writer"));

      RunOutcome childOutcome = child.approve(childApprover.token());

      assertThat(childOutcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(links.find(childId)).isEmpty();
      assertThat(writer.snapshot(parentId).status()).isEqualTo(ConversationStatus.COMPLETE);
      List<ToolResultBlock> parentResults =
          parentProvider.requests().getLast().context().messages().stream()
              .flatMap(message -> message.content().stream())
              .filter(ToolResultBlock.class::isInstance)
              .map(ToolResultBlock.class::cast)
              .toList();
      assertThat(parentResults).isNotEmpty();
      assertThat(parentResults.getFirst().isError()).isFalse();
      assertThat(parentResults.getFirst().content()).isEqualTo("the answer is 42");

      // At-least-once: the very same settlement redelivered after the link is already forgotten
      // is a silent no-op — it must not throw and must not try to resume the parent a second time.
      AgentTools.completions(links, parentParks, router)
          .accept(
              new ConversationSettled(
                  childId, ConversationStatus.COMPLETE, null, "the answer is 42"));

      assertThat(writer.snapshot(parentId).status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * Spec §4 names this arm explicitly ("{@code deny}-shaped failure text on child failure"): a
     * {@link ConversationStatus#FAILED} settlement for a linked child resumes the parent with a
     * {@link ToolResult#error} carrying the settlement's own {@code failureReason} — not the
     * generic already-failed text {@link Tool#execute}'s own replay short-circuit uses, since this
     * is a fresh, first-time settlement, off the real {@code RunOutcome}, not a redelivery. The
     * link is forgotten afterward, same as the {@code COMPLETE} arm.
     */
    @Test
    void a_failed_settlement_resumes_the_parent_with_a_tool_error_carrying_the_reason() {
      ToolCall call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode());
      ScriptedProvider writerProvider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(call), endWithToolUse())
              .turn(new ModelEvent.TextChunk("noted the failure"), endTurn());
      ParkingApprover writerApprover = new ParkingApprover();
      Parks parks = Parks.inMemory();
      Harness harness = Nessy.harness(h -> h.provider(writerProvider).parks(parks));
      Agent<String> writer =
          harness.agent(
              a ->
                  a.name("writer")
                      .model("m")
                      .tools(ToolGrant.grant(new EchoTool(), UsagePolicy.requireApproval()))
                      .approver(writerApprover));
      CallbackRouter router = new CallbackRouter();
      router.register(writer);
      RunOutcome parentOutcome = writer.converse().tell("echo hi");
      ConversationId parentId = parentOutcome.state().id();
      ParkToken parentToken = writerApprover.token();
      SubagentLinks links = SubagentLinks.inMemory();
      ConversationId childId = ConversationId.generate();
      links.save(childId, parentToken);
      var consumer = AgentTools.completions(links, parks, router);
      ConversationSettled event =
          new ConversationSettled(
              childId, ConversationStatus.FAILED, "the child agent crashed", "");

      consumer.accept(event);

      assertThat(writer.snapshot(parentId).status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(links.find(childId)).isEmpty();
      List<ToolResultBlock> parentResults =
          writerProvider.requests().getLast().context().messages().stream()
              .flatMap(message -> message.content().stream())
              .filter(ToolResultBlock.class::isInstance)
              .map(ToolResultBlock.class::cast)
              .toList();
      assertThat(parentResults).isNotEmpty();
      assertThat(parentResults.getFirst().isError()).isTrue();
      assertThat(parentResults.getFirst().content()).isEqualTo("the child agent crashed");
    }

    /**
     * {@code completions} does not catch or wrap whatever {@code Agent.resume} throws (documented,
     * not swallowed): here the routing name {@code parks.find} reports for the parent token does
     * not match the resuming agent's own name, so {@link WrongAgentException} surfaces uncaught —
     * and the link survives the throw, left in place for a future retry rather than forgotten on a
     * failed delivery.
     */
    @Test
    void a_wrong_agent_resume_surfaces_uncaught_and_leaves_the_link_in_place() {
      ToolCall call = new ToolCall("c1", "echo", JsonNodeFactory.instance.objectNode());
      ScriptedProvider writerProvider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(call), endWithToolUse());
      ParkingApprover writerApprover = new ParkingApprover();
      Parks sharedParks = Parks.inMemory();
      Harness harness = Nessy.harness(h -> h.provider(writerProvider).parks(sharedParks));
      Agent<String> writer =
          harness.agent(
              a ->
                  a.name("writer")
                      .model("m")
                      .tools(ToolGrant.grant(new EchoTool(), UsagePolicy.requireApproval()))
                      .approver(writerApprover));
      Agent<String> researcher = harness.agent(a -> a.name("researcher").model("m"));
      CallbackRouter router = new CallbackRouter();
      router.register(writer);
      router.register(researcher);
      writer.converse().tell("echo hi");
      ParkToken realParentToken = writerApprover.token();
      // A Parks view that answers truthfully for every token except this one, whose stamp it
      // misreports as "researcher" instead of "writer" — simulating a routing-name mismatch
      // between whatever informed this consumer's Parks view and the token's real owner. The
      // resuming agent (researcher) still consults its own, real, shared Parks internally, which
      // truthfully finds the token stamped "writer" — hence the mismatch.
      Parks misreportingParks =
          new Parks() {
            @Override
            public void park(Park park) {
              sharedParks.park(park);
            }

            @Override
            public Optional<Park> find(ParkToken token) {
              return sharedParks
                  .find(token)
                  .map(
                      park ->
                          token.equals(realParentToken)
                              ? new Park(
                                  park.conversationId(), park.token(), park.call(), "researcher")
                              : park);
            }

            @Override
            public List<Park> forConversation(ConversationId id) {
              return sharedParks.forConversation(id);
            }
          };
      SubagentLinks links = SubagentLinks.inMemory();
      ConversationId childId = ConversationId.generate();
      links.save(childId, realParentToken);
      ConversationSettled event =
          new ConversationSettled(childId, ConversationStatus.COMPLETE, null, "irrelevant");
      var consumer = AgentTools.completions(links, misreportingParks, router);

      assertThatThrownBy(() -> consumer.accept(event))
          .isInstanceOf(WrongAgentException.class)
          .hasMessageContaining("writer")
          .hasMessageContaining("researcher");
      assertThat(links.find(childId)).contains(realParentToken);
    }
  }
}
