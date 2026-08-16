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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.InputRenderer;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.TextBlock;
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
 * {@code AgentConfig.subagent(SubagentCustomizer)} / {@code SubagentConfig} — the build-time
 * construction surface (design of record 2026-08-16 §0-§3): required fields, the delegation tool
 * grant it produces, the harness's own internal name registry rejecting a collision anywhere in the
 * whole delegation tree, and the lexical A→B→C nesting settling bottom-up.
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
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.description("delegates research"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("name");
    }

    @Test
    void a_missing_description_throws_naming_the_field() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.name("researcher").model("m"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("description");
    }

    /**
     * N2: partial registration must not survive a failed build. Every declared config, at every
     * depth, is validated before any of them is built or registered (see {@code
     * SubagentAssembly.build()}'s own up-front pass) — so a later sibling's bad config never leaves
     * an earlier, perfectly valid sibling sitting in the harness's own internal registry when the
     * throw reaches the caller. Proved here by reusing "helper" on a second, independent build
     * attempt: if the first attempt had left "helper" registered, this would collide instead of
     * succeeding.
     */
    @Test
    void a_later_siblings_invalid_config_leaves_no_earlier_sibling_registered() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.name("helper").description("d").model("m"))
              .subagent(sub -> sub.name("broken").model("m")); // missing description

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("description");

      Agent<String> retry =
          harness.agent(
              a ->
                  a.name("writer-2")
                      .model("m")
                      .subagent(sub -> sub.name("helper").description("d").model("m")));

      assertThat(retry.subagent("helper").name()).isEqualTo("helper");
    }
  }

  @Nested
  class Delegation_tool_grant {

    @Test
    void the_delegation_tool_is_named_and_described_after_the_child() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("ok"), endTurn());
      Agent<String> writer =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("writer")
                          .model("m")
                          .subagent(
                              sub ->
                                  sub.name("researcher")
                                      .description("Delegates research questions.")
                                      .model("m")));

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
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("writer")
                          .model("m")
                          .approver(Approver.denyAll("writer never approves anything"))
                          .subagent(
                              sub ->
                                  sub.name("researcher")
                                      .description("Delegates research.")
                                      .model("m")));

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
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("writer")
                          .model("m")
                          .approver(writerApprover)
                          .subagent(
                              sub ->
                                  sub.name("researcher")
                                      .description("Delegates research.")
                                      .model("m")
                                      .policy(UsagePolicy.requireApproval())));

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
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      harness.agent(a -> a.name("dup").model("m"));
      AgentConfig<String> duplicate =
          new AgentConfig<>(harness, String.class, InputRenderer.text()).name("dup").model("m");

      assertThatThrownBy(duplicate::build)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("dup");
    }

    @Test
    void a_subagent_sharing_its_parents_own_name_is_rejected() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.name("writer").description("d").model("m"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("writer");
    }

    @Test
    void two_sibling_subagents_sharing_a_name_is_rejected() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.name("helper").description("d").model("m"))
              .subagent(sub -> sub.name("helper").description("d2").model("m"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("helper");
    }

    /**
     * Final review SF-5: unlike {@code
     * a_later_siblings_invalid_config_leaves_no_earlier_sibling_registered} (a config-validation
     * failure, caught entirely up front before anything builds), a duplicate NAME is only
     * detectable at registration time — deep inside the second "helper"'s own {@code build()} —
     * after the FIRST "helper" has already built and registered itself successfully. Without a
     * rollback, that first "helper" would stay in the harness's internal registry forever, and a
     * corrected rebuild would collide on it instead of the actual mistake. Proved the same way: a
     * second, independent build reusing the name "helper" must succeed.
     */
    @Test
    void
        two_sibling_subagents_sharing_a_name_leaves_neither_registered_and_a_corrected_rebuild_succeeds() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(sub -> sub.name("helper").description("d").model("m"))
              .subagent(sub -> sub.name("helper").description("d2").model("m"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("helper");

      Agent<String> retry =
          harness.agent(
              a ->
                  a.name("writer-2")
                      .model("m")
                      .subagent(sub -> sub.name("helper").description("d").model("m")));

      assertThat(retry.subagent("helper").name()).isEqualTo("helper");
    }

    @Test
    void a_name_collision_two_levels_deep_is_still_rejected() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      harness.agent(a -> a.name("archivist").model("m"));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(
                  b ->
                      b.name("b")
                          .description("d")
                          .model("m")
                          .subagent(c -> c.name("archivist").description("d").model("m")));

      assertThatThrownBy(builder::build)
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
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  top ->
                      top.name("a")
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
                                                          UsagePolicy.requireApproval())))));

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

  /** The typed door's own wire shape (design of record 2026-08-16 §0.5). */
  record ResearchRequest(String question, int depth) {}

  private static InputRenderer<ResearchRequest> researchRequestRenderer() {
    return request ->
        List.of(new TextBlock("Q: " + request.question() + " (depth " + request.depth() + ")"));
  }

  private static ObjectNode researchRequestArguments(String question, int depth) {
    return JsonNodeFactory.instance.objectNode().put("question", question).put("depth", depth);
  }

  @Nested
  class Typed_door {

    /**
     * The typed door's own tool schema IS {@code T}'s victools schema (design of record 2026-08-16
     * §0.5) — not the degenerate {@code Delegation(String task)} wrapper the String door always
     * carries. Pinned against {@code ResearchRequest(String question, int depth)}: an object schema
     * with both components required.
     */
    @Test
    void the_delegation_tools_schema_is_the_records_own_victools_schema() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("ok"), endTurn());
      Nessy.harness(h -> h.provider(provider))
          .agent(
              a ->
                  a.name("writer")
                      .model("m")
                      .subagent(
                          ResearchRequest.class,
                          sub ->
                              sub.name("researcher")
                                  .description("Delegates a structured research request.")
                                  .model("m")
                                  .renderer(researchRequestRenderer())))
          .converse()
          .tell("go");

      ToolSpec spec =
          provider.requests().getFirst().tools().stream()
              .filter(t -> t.name().equals("researcher"))
              .findFirst()
              .orElseThrow();
      ObjectNode schema = spec.inputSchema();

      assertThat(schema.get("type").asText()).isEqualTo("object");
      assertThat(schema.get("properties").get("question").get("type").asText()).isEqualTo("string");
      assertThat(schema.get("properties").get("depth").get("type").asText()).isEqualTo("integer");
      List<String> required = new ArrayList<>();
      schema.get("required").forEach(node -> required.add(node.asText()));
      assertThat(required).containsExactlyInAnyOrder("question", "depth");
    }

    /**
     * The pin the review asked for, unchanged on the String door: even after the typed door exists
     * alongside it, {@code .subagent(sub -> ...)}'s own delegation tool schema is still exactly
     * v1's degenerate {@code Delegation(String task)} wrapper — one required string property named
     * {@code task}, nothing else.
     */
    @Test
    void the_string_doors_delegation_tool_keeps_v1s_wire_shape_byte_for_byte() {
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.TextChunk("ok"), endTurn());
      Nessy.harness(h -> h.provider(provider))
          .agent(
              a ->
                  a.name("writer")
                      .model("m")
                      .subagent(
                          sub ->
                              sub.name("researcher").description("Delegates research.").model("m")))
          .converse()
          .tell("go");

      ToolSpec spec =
          provider.requests().getFirst().tools().stream()
              .filter(t -> t.name().equals("researcher"))
              .findFirst()
              .orElseThrow();
      ObjectNode schema = spec.inputSchema();

      assertThat(schema.get("type").asText()).isEqualTo("object");
      assertThat(schema.get("properties").get("task").get("type").asText()).isEqualTo("string");
      List<String> propertyNames = new ArrayList<>();
      schema.get("properties").fieldNames().forEachRemaining(propertyNames::add);
      assertThat(propertyNames).containsExactly("task");
      List<String> required = new ArrayList<>();
      schema.get("required").forEach(node -> required.add(node.asText()));
      assertThat(required).containsExactly("task");
    }

    /**
     * The full typed round trip: the parent's model calls the delegation tool with JSON arguments
     * that deserialize into {@code ResearchRequest} (the ordinary tool-invocation path, unchanged),
     * and the child's own {@code InputRenderer<ResearchRequest>} — required on this door — is what
     * turns that record into the content block the child actually sees, not any prose task string.
     */
    @Test
    void the_typed_door_carries_a_deserialized_record_through_to_the_childs_own_renderer() {
      ToolCall delegateCall =
          new ToolCall("d1", "researcher", researchRequestArguments("what is the answer", 2));
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegateCall), endWithToolUse())
              .turn(new ModelEvent.TextChunk("the researcher's answer"), endTurn())
              .turn(new ModelEvent.TextChunk("writer wraps up"), endTurn());
      Agent<String> writer =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  a ->
                      a.name("writer")
                          .model("m")
                          .subagent(
                              ResearchRequest.class,
                              sub ->
                                  sub.name("researcher")
                                      .description("Delegates a structured research request.")
                                      .model("m")
                                      .renderer(researchRequestRenderer())));

      RunOutcome outcome = writer.converse().tell("investigate");
      ConversationId parentId = outcome.state().id();
      ConversationId childId = new ConversationId(parentId.value() + "/d1");

      List<Message> childMessages =
          writer.subagent("researcher").snapshot(childId).context().messages();

      assertThat(childMessages).isNotEmpty();
      assertThat(childMessages.getFirst().content())
          .containsExactly(new TextBlock("Q: what is the answer (depth 2)"));
      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
    }

    /**
     * No silent render-as-JSON default (design of record 2026-08-16 §0.5): a typed door with no
     * {@code .renderer(...)} call fails loudly at the PARENT's own {@code build()}, naming the
     * subagent whose renderer is missing.
     */
    @Test
    void a_missing_renderer_on_the_typed_door_fails_loudly_at_build_naming_the_subagent() {
      Harness harness = Nessy.harness(h -> h.provider(NEVER_CALLED));
      AgentConfig<String> builder =
          new AgentConfig<>(harness, String.class, InputRenderer.text())
              .name("writer")
              .model("m")
              .subagent(
                  ResearchRequest.class,
                  sub -> sub.name("researcher").description("Delegates research.").model("m"));

      assertThatThrownBy(builder::build)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("renderer")
          .hasMessageContaining("researcher");
    }

    /**
     * The typed door composes inside lexical nesting exactly like the String door (design of record
     * 2026-08-16 §0.5 + §0 ruling 1): A (String) delegates to B (String), B delegates to a TYPED
     * grandchild C, and the whole chain still settles bottom-up with no park needed here — proof
     * the typed door is not a special case the nesting machinery has to know about.
     */
    @Test
    void a_typed_subagent_nests_inside_a_string_delegation_tree() {
      ToolCall delegateToB =
          new ToolCall("d-ab", "b", JsonNodeFactory.instance.objectNode().put("task", "go"));
      ToolCall delegateToC =
          new ToolCall("d-bc", "c", researchRequestArguments("what is the answer", 1));
      ScriptedProvider provider =
          new ScriptedProvider()
              .turn(new ModelEvent.ToolUseEmitted(delegateToB), endWithToolUse())
              .turn(new ModelEvent.ToolUseEmitted(delegateToC), endWithToolUse())
              .turn(new ModelEvent.TextChunk("C's own answer"), endTurn())
              .turn(new ModelEvent.TextChunk("B relays C's answer"), endTurn())
              .turn(new ModelEvent.TextChunk("A wraps everything up"), endTurn());
      Agent<String> a =
          Nessy.harness(h -> h.provider(provider))
              .agent(
                  top ->
                      top.name("a")
                          .model("m")
                          .subagent(
                              b ->
                                  b.name("b")
                                      .description("delegates to b")
                                      .model("m")
                                      .subagent(
                                          ResearchRequest.class,
                                          c ->
                                              c.name("c")
                                                  .description("delegates to c")
                                                  .model("m")
                                                  .renderer(researchRequestRenderer()))));

      RunOutcome outcome = a.converse().tell("start");

      assertThat(outcome.state().status()).isEqualTo(ConversationStatus.COMPLETE);
      assertThat(provider.requests()).hasSize(5);
    }
  }
}
