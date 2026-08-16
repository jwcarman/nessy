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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@link Subagent} — the narrow doors handle {@link Agent#subagent(String)} returns (design of
 * record 2026-08-16 §0, ruling 3): every door delegates straight to the underlying child agent's
 * own same-named door, traversal reaches a grandchild by chaining, and an unknown name is refused
 * naming both parent and child. There is deliberately no test here exercising a {@code converse} or
 * {@code tell} on {@link Subagent} — it does not compile, because the type does not declare it; the
 * proof is the type itself, not an assertion.
 */
class SubagentTest {

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

  private static ModelEvent.TurnEnded endWithToolUse() {
    return new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero());
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

  private ToolCall delegationCall(String toolName) {
    return new ToolCall("d1", toolName, JsonNodeFactory.instance.objectNode().put("task", "go"));
  }

  private ToolCall askQuestionCall() {
    return new ToolCall("ask-1", "ask_question", JsonNodeFactory.instance.objectNode());
  }

  @Nested
  class Doors_delegate_to_the_child {

    @Test
    void approve_reaches_the_childs_own_approve_door() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall("researcher")), endWithToolUse())
              .turn(new ModelEvent.ToolUseEmitted(askQuestionCall()), endWithToolUse())
              .turn(new ModelEvent.TextChunk("researcher's answer"), endTurn())
              .turn(new ModelEvent.TextChunk("writer wraps up"), endTurn());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(approver)
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("delegates research")
                          .model("m")
                          .tools(
                              ToolGrant.grant(
                                  new AskQuestionTool(), UsagePolicy.requireApproval())))
              .build();
      writer.converse().tell("investigate");
      ParkToken token = approver.token();

      RunOutcome outcome = writer.subagent("researcher").approve(token);

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void deny_reaches_the_childs_own_deny_door_carrying_the_reason() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall("researcher")), endWithToolUse())
              .turn(new ModelEvent.ToolUseEmitted(askQuestionCall()), endWithToolUse())
              .turn(new ModelEvent.TextChunk("researcher notes the denial"), endTurn())
              .turn(new ModelEvent.TextChunk("writer wraps up"), endTurn());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(approver)
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("delegates research")
                          .model("m")
                          .tools(
                              ToolGrant.grant(
                                  new AskQuestionTool(), UsagePolicy.requireApproval())))
              .build();
      writer.converse().tell("investigate");
      ParkToken token = approver.token();

      RunOutcome outcome = writer.subagent("researcher").deny(token, "not now");

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      // N1: distinguishes deny from approve — a Subagent.deny mis-wired to the child's own approve
      // (or one that dropped the reason) would still settle COMPLETE above but never carry this.
      // Searches every request the shared provider ever saw (both writer's and researcher's own
      // calls interleave here), not just the last one, since the researcher's own follow-up turn —
      // the one carrying the denial — settles before the writer's final wrap-up turn does.
      List<ToolResultBlock> denials =
          provider.requests().stream()
              .flatMap(request -> request.context().messages().stream())
              .flatMap(message -> message.content().stream())
              .filter(ToolResultBlock.class::isInstance)
              .map(ToolResultBlock.class::cast)
              .filter(ToolResultBlock::isError)
              .toList();
      assertThat(denials).isNotEmpty();
      assertThat(denials.getFirst().content()).isEqualTo("Denied: not now");
    }

    @Test
    void resume_reaches_the_childs_own_resume_door() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall("researcher")), endWithToolUse())
              .turn(new ModelEvent.ToolUseEmitted(askQuestionCall()), endWithToolUse())
              .turn(new ModelEvent.TextChunk("researcher's answer"), endTurn())
              .turn(new ModelEvent.TextChunk("writer wraps up"), endTurn());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(approver)
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("delegates research")
                          .model("m")
                          .tools(
                              ToolGrant.grant(
                                  new AskQuestionTool(), UsagePolicy.requireApproval())))
              .build();
      writer.converse().tell("investigate");
      ParkToken token = approver.token();

      RunOutcome outcome =
          writer.subagent("researcher").resume(token, new ToolResolution.Decided(Decision.allow()));

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    @Test
    void snapshot_reaches_the_childs_own_snapshot_door() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegationCall("researcher")), endWithToolUse())
              .turn(new ModelEvent.ToolUseEmitted(askQuestionCall()), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(approver)
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("delegates research")
                          .model("m")
                          .tools(
                              ToolGrant.grant(
                                  new AskQuestionTool(), UsagePolicy.requireApproval())))
              .build();
      RunOutcome outcome = writer.converse().tell("investigate");
      ConversationId parentId = outcome.state().id();
      ConversationId childId = new ConversationId(parentId.value() + "/d1");

      ConversationStatus status = writer.subagent("researcher").snapshot(childId).status();

      assertThat(status).isEqualTo(ConversationStatus.PARKED);
    }
  }

  @Nested
  class Unknown_name {

    @Test
    void agent_subagent_of_an_unknown_name_throws_naming_parent_and_child() {
      Agent<String> writer =
          Nessy.harness(new ScriptedProvider()).build().agent().name("writer").model("m").build();

      assertThatThrownBy(() -> writer.subagent("nope"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("writer")
          .hasMessageContaining("nope");
    }

    @Test
    void a_grandchild_is_not_reachable_directly_from_the_top_level_agent() {
      Agent<String> a =
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("a")
              .model("m")
              .subagent(
                  b ->
                      b.name("b")
                          .description("delegates to b")
                          .model("m")
                          .subagent(c -> c.name("c").description("delegates to c").model("m")))
              .build();

      assertThatThrownBy(() -> a.subagent("c"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("a")
          .hasMessageContaining("c");
    }
  }

  @Nested
  class Traversal {

    @Test
    void a_subagent_handle_reaches_its_own_child_by_name() {
      Agent<String> a =
          Nessy.harness(new ScriptedProvider())
              .build()
              .agent()
              .name("a")
              .model("m")
              .subagent(
                  b ->
                      b.name("b")
                          .description("delegates to b")
                          .model("m")
                          .subagent(c -> c.name("c").description("delegates to c").model("m")))
              .build();

      Subagent b = a.subagent("b");
      Subagent c = b.subagent("c");

      assertThat(b.name()).isEqualTo("b");
      assertThat(c.name()).isEqualTo("c");
    }
  }
}
