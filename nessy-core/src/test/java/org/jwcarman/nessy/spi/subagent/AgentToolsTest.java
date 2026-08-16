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
package org.jwcarman.nessy.spi.subagent;

import static org.assertj.core.api.Assertions.assertThat;
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
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .build();

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.name()).isEqualTo("researcher");
    }

    @Test
    void description_is_the_one_given() {
      Agent<String> child =
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .build();

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.description()).isEqualTo("delegates research");
    }

    @Test
    void input_type_is_delegation() {
      Agent<String> child =
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .build();

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.inputType()).isEqualTo(AgentTools.Delegation.class);
    }

    @Test
    void describe_shows_the_task_text() {
      Agent<String> child =
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .build();

      Tool<AgentTools.Delegation> tool = AgentTools.subagent(child, "delegates research");

      assertThat(tool.describe(new AgentTools.Delegation("look into the outage")))
          .isEqualTo("look into the outage");
    }
  }

  @Nested
  class Child_conversation_identity {

    @Test
    void two_executes_with_the_same_call_id_land_on_the_same_child_conversation() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.TextChunk("first"), endTurn())
              .turn(new ModelEvent.TextChunk("second"), endTurn());
      Agent<String> child =
          Nessy.harness(provider).build().agent().name("researcher").model("m").build();
      Tool<AgentTools.Delegation> tool =
          AgentTools.subagent(child, "delegates research", SubagentLinks.inMemory());
      ConversationId parentId = ConversationId.generate();
      ToolCall call = new ToolCall("call-1", "researcher", taskArguments("investigate"));
      ToolContext context = contextFor(parentId, call);
      ConversationId expectedChildId = new ConversationId(parentId.value() + "/" + call.id());

      tool.execute(new AgentTools.Delegation("investigate"), context);
      tool.execute(new AgentTools.Delegation("investigate further"), context);

      assertThat(child.snapshot(expectedChildId).status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(child.contextFor(expectedChildId).messages()).hasSize(4);
    }
  }

  @Nested
  class Settlement_results {

    @Test
    void a_completed_child_answers_with_its_final_assistant_text() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("the answer is 42"), endTurn());
      Agent<String> child =
          Nessy.harness(provider).build().agent().name("researcher").model("m").build();
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
          Nessy.harness(provider).build().agent().name("researcher").model("m").build();
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
          Nessy.harness(provider)
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .tools(ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
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

    @Test
    void a_parked_child_with_no_links_store_throws_naming_the_missing_store() {
      ToolCall childCall =
          new ToolCall("c1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(childCall), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> child =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .tools(ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
              .approver(approver)
              .build();
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
          Nessy.harness(provider)
              .build()
              .agent()
              .name("researcher")
              .model("m")
              .tools(ToolGrant.grant(new EchoTool(), UsagePolicy.allow()))
              .build();
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

      assertThat(heard).isNotEmpty();
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
      ConversationSettled event =
          new ConversationSettled(
              ConversationId.generate(), ConversationStatus.COMPLETE, null, "x");

      AgentTools.completions(links, parks, router).accept(event);
    }

    @Test
    void a_settlement_whose_park_is_already_gone_forgets_the_link_and_resumes_nobody() {
      ConversationId childId = ConversationId.generate();
      ParkToken parentToken = ParkToken.generate();
      SubagentLinks links = SubagentLinks.inMemory();
      links.save(childId, parentToken);
      Parks parks = Parks.inMemory();
      CallbackRouter router = new CallbackRouter();
      ConversationSettled event =
          new ConversationSettled(childId, ConversationStatus.COMPLETE, null, "x");

      AgentTools.completions(links, parks, router).accept(event);

      assertThat(links.find(childId)).isEmpty();
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
          Nessy.harness(childProvider)
              .listen(ConversationSettled.class, AgentTools.completions(links, parentParks, router))
              .build()
              .agent()
              .name("researcher")
              .model("child-model")
              .tools(ToolGrant.grant(new AskQuestionTool(), UsagePolicy.requireApproval()))
              .approver(childApprover)
              .build();
      Tool<AgentTools.Delegation> delegation =
          AgentTools.subagent(child, "delegates research", links);
      ToolCall delegationCall =
          new ToolCall("d1", "researcher", taskArguments("investigate the topic"));
      ScriptedProvider parentProvider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall), endWithToolUse())
              .turn(new ModelEvent.TextChunk("wrapped up"), endTurn());
      Harness parentHarness = Nessy.harness(parentProvider).parks(parentParks).build();
      Agent<String> writer =
          parentHarness
              .agent()
              .name("writer")
              .model("parent-model")
              .tools(ToolGrant.grant(delegation, UsagePolicy.allow()))
              .build();
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
      Harness harness = Nessy.harness(writerProvider).parks(sharedParks).build();
      Agent<String> writer =
          harness
              .agent()
              .name("writer")
              .model("m")
              .tools(ToolGrant.grant(new EchoTool(), UsagePolicy.requireApproval()))
              .approver(writerApprover)
              .build();
      Agent<String> researcher = harness.agent().name("researcher").model("m").build();
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
