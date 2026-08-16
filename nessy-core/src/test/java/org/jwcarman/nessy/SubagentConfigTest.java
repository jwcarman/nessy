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
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

/**
 * {@code AgentBuilder.subagent(Consumer)} / {@code SubagentConfig} — the build-time construction
 * surface (design of record 2026-08-16 §0-§3): required fields, the delegation tool grant it
 * produces, the harness's own internal name registry rejecting a collision anywhere in the whole
 * delegation tree, and the lexical A→B→C nesting settling bottom-up.
 */
class SubagentConfigTest {

  private static final ModelProvider NEVER_CALLED =
      new ModelProvider() {
        @Override
        public ModelStream stream(ModelRequest request) {
          throw new AssertionError("never called");
        }

        @Override
        public Set<Capability> capabilities() {
          return Set.of();
        }
      };

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

  @Nested
  class Required_fields {

    @Test
    void a_missing_name_throws_naming_the_field() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();

      assertThatThrownBy(
              () ->
                  harness
                      .agent()
                      .name("writer")
                      .model("m")
                      .subagent(sub -> sub.description("delegates research"))
                      .build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("name");
    }

    @Test
    void a_missing_description_throws_naming_the_field() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();

      assertThatThrownBy(
              () ->
                  harness
                      .agent()
                      .name("writer")
                      .model("m")
                      .subagent(sub -> sub.name("researcher").model("m"))
                      .build())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("description");
    }
  }

  @Nested
  class Delegation_tool_grant {

    @Test
    void the_delegation_tool_is_named_and_described_after_the_child() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("ok"), endTurn());
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("Delegates research questions.")
                          .model("m"))
              .build();

      writer.converse().tell("go");

      ToolSpec spec =
          provider.requests().getFirst().tools().stream()
              .filter(t -> t.name().equals("researcher"))
              .findFirst()
              .orElseThrow();
      assertThat(spec.description()).isEqualTo("Delegates research questions.");
    }

    /**
     * Default policy is {@link UsagePolicy#allow()} (spec §0 ruling 2): no approver is ever
     * consulted, so the delegation call proceeds straight to the child even though the writer's own
     * approver would deny everything, proving the delegation grant's own policy — not some default
     * derived from the child — governs the call.
     */
    @Test
    void the_default_delegation_policy_is_allow_no_approver_consulted() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(
                      new ToolCall(
                          "d1",
                          "researcher",
                          JsonNodeFactory.instance.objectNode().put("task", "go"))),
                  endWithToolUse())
              .turn(new ModelEvent.TextChunk("researcher's own reply"), endTurn())
              .turn(new ModelEvent.TextChunk("writer wraps up"), endTurn());
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(Approver.denyAll("writer never approves anything"))
              .subagent(sub -> sub.name("researcher").description("Delegates research.").model("m"))
              .build();

      RunOutcome outcome = writer.converse().tell("investigate");

      assertThat(outcome).isInstanceOf(RunOutcome.Completed.class);
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * {@code .policy(requireApproval())} gates the DELEGATION tool itself, at the parent: the
     * writer's own approver parks the call before the child researcher is ever told anything — only
     * one model request (the writer's own decision turn) is ever sent.
     */
    @Test
    void a_required_approval_policy_gates_the_delegation_call_before_the_child_is_told_anything() {
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(
                  new ModelEvent.ToolUseEmitted(
                      new ToolCall(
                          "d1",
                          "researcher",
                          JsonNodeFactory.instance.objectNode().put("task", "go"))),
                  endWithToolUse());
      ParkingApprover writerApprover = new ParkingApprover();
      Agent<String> writer =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("writer")
              .model("m")
              .approver(writerApprover)
              .subagent(
                  sub ->
                      sub.name("researcher")
                          .description("Delegates research.")
                          .model("m")
                          .policy(UsagePolicy.requireApproval()))
              .build();

      RunOutcome outcome = writer.converse().tell("investigate");

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      assertThat(writerApprover.token()).isNotNull();
      // Only the writer's own decision turn was ever sent — the researcher was never contacted.
      assertThat(provider.requests()).hasSize(1);
    }
  }

  @Nested
  class Duplicate_names {

    @Test
    void two_top_level_agents_sharing_a_name_is_rejected() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();
      harness.agent().name("dup").model("m").build();

      assertThatThrownBy(() -> harness.agent().name("dup").model("m").build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dup");
    }

    @Test
    void a_subagent_sharing_its_parents_own_name_is_rejected() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();

      assertThatThrownBy(
              () ->
                  harness
                      .agent()
                      .name("writer")
                      .model("m")
                      .subagent(sub -> sub.name("writer").description("d").model("m"))
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("writer");
    }

    @Test
    void two_sibling_subagents_sharing_a_name_is_rejected() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();

      assertThatThrownBy(
              () ->
                  harness
                      .agent()
                      .name("writer")
                      .model("m")
                      .subagent(sub -> sub.name("helper").description("d").model("m"))
                      .subagent(sub -> sub.name("helper").description("d2").model("m"))
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("helper");
    }

    @Test
    void a_name_collision_two_levels_deep_is_still_rejected() {
      Harness harness = Nessy.harness(NEVER_CALLED).build();
      harness.agent().name("archivist").model("m").build();

      assertThatThrownBy(
              () ->
                  harness
                      .agent()
                      .name("writer")
                      .model("m")
                      .subagent(
                          b ->
                              b.name("b")
                                  .description("d")
                                  .model("m")
                                  .subagent(c -> c.name("archivist").description("d").model("m")))
                      .build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("archivist");
    }
  }

  @Nested
  class Lexical_nesting_and_wake_chain {

    /**
     * The offline end-to-end proof for spec §0 ruling 1's lexical A→B→C tree: C's own gated tool
     * parks, which parks B's delegation call to C, which in turn parks A's delegation call to B —
     * one outer {@code tell} returns a single {@link RunOutcome.Parked}. Approving C's wait settles
     * C, whose internally-wired completions listener wakes B; B's own follow-up turn settles B in
     * turn, waking A; A's own follow-up turn produces the final answer. Every model turn is
     * scripted, offline, and consumed in strict FIFO order — proof the wake chain runs
     * synchronously bottom-up with no step skipped or reordered.
     */
    @Test
    void a_three_level_delegation_settles_bottom_up_after_one_approval() {
      ToolCall delegateToB =
          new ToolCall("d-ab", "b", JsonNodeFactory.instance.objectNode().put("task", "go"));
      ToolCall delegateToC =
          new ToolCall("d-bc", "c", JsonNodeFactory.instance.objectNode().put("task", "go"));
      ToolCall askQuestion =
          new ToolCall("ask-1", "ask_question", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegateToB), endWithToolUse()) // A turn 1
              .turn(new ModelEvent.ToolUseEmitted(delegateToC), endWithToolUse()) // B turn 1
              .turn(new ModelEvent.ToolUseEmitted(askQuestion), endWithToolUse()) // C turn 1
              .turn(
                  new ModelEvent.TextChunk("C's own answer"), endTurn()) // C turn 2 (post-approve)
              .turn(new ModelEvent.TextChunk("B relays C's answer"), endTurn()) // B turn 2
              .turn(new ModelEvent.TextChunk("A wraps everything up"), endTurn()); // A turn 2
      ParkingApprover approver = new ParkingApprover();
      Agent<String> a =
          Nessy.harness(provider)
              .build()
              .agent()
              .name("a")
              .model("m")
              .approver(approver)
              .subagent(
                  b ->
                      b.name("b")
                          .description("delegates to b")
                          .model("m")
                          .subagent(
                              c ->
                                  c.name("c")
                                      .description("delegates to c")
                                      .model("m")
                                      .tools(
                                          ToolGrant.grant(
                                              new AskQuestionTool(),
                                              UsagePolicy.requireApproval()))))
              .build();

      RunOutcome outcome = a.converse().tell("start");

      assertThat(outcome).isInstanceOf(RunOutcome.Parked.class);
      ParkToken askToken = approver.token();
      assertThat(askToken).isNotNull();

      RunOutcome resolved = a.subagent("b").subagent("c").approve(askToken);

      assertThat(resolved.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(a.snapshot(outcome.state().id()).status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(provider.requests()).hasSize(6);
    }
  }
}
