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
package org.jwcarman.nessy.spi.execute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationEvent;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.event.ApprovalRequested;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolRegistry;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.api.turn.TurnEvent;

class GatedToolCallExecutorTest {

  private final ConversationId id = ConversationId.generate();
  private final ConversationState state = ConversationState.newConversation(id);
  private final List<TurnEvent> observed = new ArrayList<>();

  private static ToolCall echoCall(String value) {
    var args = JsonNodeFactory.instance.objectNode();
    args.put("value", value);
    return new ToolCall("c1", "echo", args);
  }

  private static ToolResult resultOf(Awaited<ConversationEvent> outcome) {
    var event = ((Awaited.Ready<ConversationEvent>) outcome).value();
    return ((ConversationEvent.ToolFinished) event).result();
  }

  private GatedToolCallExecutor executorFor(ToolGrant grant, Approver approver) {
    ToolRegistry registry = ToolRegistry.of(grant.tool());
    Map<String, ToolGrant> grants = new LinkedHashMap<>();
    grants.put(grant.tool().name(), grant);
    return new GatedToolCallExecutor(
        registry,
        grants,
        approver,
        new ObjectMapper(),
        EventEmitter.noop(),
        ObservationRegistry.NOOP);
  }

  record EchoInput(String value) {}

  /** A tool that echoes its input back, and can be told to throw instead. */
  static final class EchoTool implements Tool<EchoInput> {

    private final boolean explodes;

    EchoTool(boolean explodes) {
      this.explodes = explodes;
    }

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
      if (explodes) {
        throw new IllegalStateException("kaboom");
      }
      return Awaited.ready(ToolResult.ok("echoed:" + input.value()));
    }
  }

  /** An approver that records every request it is asked, then answers with a scripted decision. */
  static final class RecordingApprover implements Approver {

    private final Awaited<Decision> scripted;
    private final List<ApprovalRequest> requests = new ArrayList<>();

    RecordingApprover(Awaited<Decision> scripted) {
      this.scripted = scripted;
    }

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      requests.add(request);
      return scripted;
    }
  }

  /**
   * A registry whose {@code find} resolves a tool its own {@code specs()} never advertised — the
   * exotic case {@link GatedToolCallExecutor}'s constructor-time belt cannot catch, since the tool
   * simply is not among the specs it inspects.
   */
  static final class ExoticRegistry implements ToolRegistry {

    private final Tool<?> tool;

    ExoticRegistry(Tool<?> tool) {
      this.tool = tool;
    }

    @Override
    public Optional<Tool<?>> find(String name) {
      return name.equals(tool.name()) ? Optional.of(tool) : Optional.empty();
    }

    @Override
    public List<ToolSpec> specs() {
      return List.of();
    }
  }

  /** A tool that always parks, to exercise the executor's own park path (not the approver's). */
  static final class ParkingTool implements Tool<EchoInput> {

    private final ParkToken token;

    ParkingTool(ParkToken token) {
      this.token = token;
    }

    @Override
    public String name() {
      return "echo";
    }

    @Override
    public String description() {
      return "Always parks";
    }

    @Override
    public Class<EchoInput> inputType() {
      return EchoInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(EchoInput input, ToolContext context) {
      return Awaited.parked(token);
    }
  }

  /**
   * An approver that appends a marker to a shared journal when consulted, then answers with a
   * scripted decision — lets a test interleave the approver's own consultation with whatever else
   * writes into the same journal (the emitter, here) to assert relative order.
   */
  static final class JournalingApprover implements Approver {

    private final List<Object> journal;
    private final Awaited<Decision> scripted;

    JournalingApprover(List<Object> journal, Awaited<Decision> scripted) {
      this.journal = journal;
      this.scripted = scripted;
    }

    @Override
    public Awaited<Decision> approve(ApprovalRequest request) {
      journal.add("approver consulted");
      return scripted;
    }
  }

  /** An emitter that appends every event it is handed to a shared journal, in arrival order. */
  static final class RecordingEmitter implements EventEmitter {

    private final List<Object> journal;

    RecordingEmitter(List<Object> journal) {
      this.journal = journal;
    }

    @Override
    public void emit(Object event) {
      journal.add(event);
    }
  }

  @Nested
  class Construction {

    @Test
    void a_grant_map_missing_a_registered_tool_is_rejected() {
      ToolRegistry registry = ToolRegistry.of(new EchoTool(false));
      Map<String, ToolGrant> emptyGrants = Map.of();

      assertThatThrownBy(
              () ->
                  new GatedToolCallExecutor(
                      registry,
                      emptyGrants,
                      Approver.allowAll(),
                      new ObjectMapper(),
                      EventEmitter.noop(),
                      ObservationRegistry.NOOP))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("echo");
    }
  }

  @Nested
  class PolicyAllow {

    @Test
    void runs_the_tool_without_consulting_the_approver() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), UsagePolicy.allow()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(approver.requests).isEmpty();
      assertThat(resultOf(outcome)).isEqualTo(ToolResult.ok("echoed:hi"));
    }
  }

  @Nested
  class PolicyDeny {

    @Test
    void yields_a_denial_without_consulting_the_approver() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(
              ToolGrant.grant(new EchoTool(false), UsagePolicy.deny("not for you")), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(approver.requests).isEmpty();
      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).isEqualTo("Denied: not for you");
    }

    @Test
    void a_throwing_policy_fails_closed_to_denial() {
      UsagePolicy explodingPolicy =
          (call, s) -> {
            throw new IllegalStateException("policy blew up");
          };
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), explodingPolicy), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(approver.requests).isEmpty();
      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).contains("policy blew up");
    }
  }

  @Nested
  class RequireApproval {

    @Test
    void an_approver_allow_runs_the_tool() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(
              ToolGrant.grant(new EchoTool(false), UsagePolicy.requireApproval()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(approver.requests).hasSize(1);
      assertThat(resultOf(outcome)).isEqualTo(ToolResult.ok("echoed:hi"));
    }

    @Test
    void an_approver_deny_yields_a_denial() {
      RecordingApprover approver =
          new RecordingApprover(Awaited.ready(new Decision.Deny("policy says no")));
      GatedToolCallExecutor executor =
          executorFor(
              ToolGrant.grant(new EchoTool(false), UsagePolicy.requireApproval()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(approver.requests).hasSize(1);
      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).isEqualTo("Denied: policy says no");
    }

    @Test
    void a_parked_approver_makes_execute_return_parked() {
      ParkToken token = ParkToken.generate();
      RecordingApprover approver = new RecordingApprover(Awaited.parked(token));
      GatedToolCallExecutor executor =
          executorFor(
              ToolGrant.grant(new EchoTool(false), UsagePolicy.requireApproval()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(outcome).isEqualTo(Awaited.parked(token));
    }
  }

  @Nested
  class UnknownAndUngrantedTools {

    @Test
    void an_unknown_tool_yields_the_no_such_tool_error() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), UsagePolicy.allow()), approver);
      ToolCall call = new ToolCall("c1", "mystery", JsonNodeFactory.instance.objectNode());

      Awaited<ConversationEvent> outcome = executor.execute(call, state, observed::add);

      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).isEqualTo("No such tool: mystery");
    }

    @Test
    void a_registered_but_ungranted_call_is_denied() {
      EchoTool tool = new EchoTool(false);
      GatedToolCallExecutor executor =
          new GatedToolCallExecutor(
              new ExoticRegistry(tool),
              Map.of(),
              new RecordingApprover(Awaited.ready(Decision.allow())),
              new ObjectMapper(),
              EventEmitter.noop(),
              ObservationRegistry.NOOP);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).isEqualTo("no grant for tool: echo");
    }
  }

  @Nested
  class ThrowingTool {

    @Test
    void becomes_an_errored_result_not_an_exception() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(true), UsagePolicy.allow()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).contains("kaboom");
    }
  }

  @Nested
  class ToolParksMidInvoke {

    @Test
    void a_tool_that_parks_makes_execute_return_parked_without_narrating_completion() {
      ParkToken token = ParkToken.generate();
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new ParkingTool(token), UsagePolicy.allow()), approver);

      Awaited<ConversationEvent> outcome = executor.execute(echoCall("hi"), state, observed::add);

      assertThat(outcome).isEqualTo(Awaited.parked(token));
      assertThat(observed).hasSize(1);
      assertThat(observed.getFirst()).isInstanceOf(TurnEvent.ToolCallDecided.class);
    }
  }

  @Nested
  class ApprovalRequestEmission {

    @Test
    void narrates_approval_requested_to_the_emitter_before_consulting_the_approver() {
      List<Object> journal = new ArrayList<>();
      RecordingEmitter emitter = new RecordingEmitter(journal);
      JournalingApprover approver =
          new JournalingApprover(journal, Awaited.ready(Decision.allow()));
      ToolGrant grant = ToolGrant.grant(new EchoTool(false), UsagePolicy.requireApproval());
      ToolRegistry registry = ToolRegistry.of(grant.tool());
      Map<String, ToolGrant> grants = Map.of(grant.tool().name(), grant);
      GatedToolCallExecutor executor =
          new GatedToolCallExecutor(
              registry, grants, approver, new ObjectMapper(), emitter, ObservationRegistry.NOOP);
      ToolCall call = echoCall("hi");

      executor.execute(call, state, observed::add);

      assertThat(journal).hasSize(2);
      assertThat(journal.get(0)).isInstanceOf(ApprovalRequested.class);
      assertThat(journal.get(1)).isEqualTo("approver consulted");
      ApprovalRequested requested = (ApprovalRequested) journal.get(0);
      assertThat(requested.conversationId()).isEqualTo(id);
      assertThat(requested.request().call()).isEqualTo(call);
    }
  }

  @Nested
  class ObserverNarration {

    @Test
    void hears_tool_call_decided_then_tool_call_completed_in_order() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(
              ToolGrant.grant(new EchoTool(false), UsagePolicy.requireApproval()), approver);

      executor.execute(echoCall("hi"), state, observed::add);

      assertThat(observed).isNotEmpty();
      assertThat(observed).hasSize(2);
      assertThat(observed.get(0)).isInstanceOf(TurnEvent.ToolCallDecided.class);
      assertThat(observed.get(1)).isInstanceOf(TurnEvent.ToolCallCompleted.class);
    }
  }

  @Nested
  class Resume {

    @Test
    void decided_allow_invokes_and_yields_the_result() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), UsagePolicy.allow()), approver);

      Awaited<ConversationEvent> outcome =
          executor.resume(
              echoCall("hi"), new ToolResolution.Decided(Decision.allow()), state, observed::add);

      assertThat(resultOf(outcome)).isEqualTo(ToolResult.ok("echoed:hi"));
    }

    @Test
    void decided_deny_yields_a_denial() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), UsagePolicy.allow()), approver);

      Awaited<ConversationEvent> outcome =
          executor.resume(
              echoCall("hi"),
              new ToolResolution.Decided(new Decision.Deny("no longer allowed")),
              state,
              observed::add);

      ToolResult result = resultOf(outcome);
      assertThat(result.isError()).isTrue();
      assertThat(result.content()).isEqualTo("Denied: no longer allowed");
    }

    @Test
    void completed_yields_that_result_directly() {
      RecordingApprover approver = new RecordingApprover(Awaited.ready(Decision.allow()));
      GatedToolCallExecutor executor =
          executorFor(ToolGrant.grant(new EchoTool(false), UsagePolicy.allow()), approver);
      ToolResult delivered = ToolResult.ok("arrived from elsewhere");

      Awaited<ConversationEvent> outcome =
          executor.resume(
              echoCall("hi"), new ToolResolution.Completed(delivered), state, observed::add);

      assertThat(resultOf(outcome)).isEqualTo(delivered);
    }
  }
}
