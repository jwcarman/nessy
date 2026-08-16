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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.UnknownParkTokenException;
import org.jwcarman.nessy.api.WrongAgentException;
import org.jwcarman.nessy.api.approval.ApprovalRequest;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.Usage;
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

class CallbackRouterTest {

  /**
   * A model that replays one scripted text turn per call — enough to build a real {@link Agent}.
   */
  private static final class FakeProvider implements ModelProvider {

    private final Deque<String> replies;

    FakeProvider(String... replies) {
      this.replies = new ArrayDeque<>(List.of(replies));
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          List.of(
              new ModelEvent.TextChunk(replies.removeFirst()),
              new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
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

  /** A model that replays one scripted tool-use turn per call — enough to force a park. */
  private static final class ScriptedProvider implements ModelProvider {

    private final Deque<List<ModelEvent>> turns = new ArrayDeque<>();

    ScriptedProvider turn(ModelEvent... events) {
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
          // intentionally empty: this fake stream holds no resources to release
        }
      };
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }
  }

  record SearchInput(String query) {}

  /** A tool that always succeeds once invoked — the gate is what parks, not the tool itself. */
  private static final class SearchTool implements Tool<SearchInput> {

    @Override
    public String name() {
      return "search";
    }

    @Override
    public String description() {
      return "Searches for something";
    }

    @Override
    public Class<SearchInput> inputType() {
      return SearchInput.class;
    }

    @Override
    public Awaited<ToolResult> execute(SearchInput input, ToolContext context) {
      return Awaited.ready(ToolResult.ok("found:" + input.query()));
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

  private Agent<String> agentNamed(String name) {
    return Nessy.harness(h -> h.provider(new FakeProvider("hi")))
        .agent(a -> a.name(name).model("m"));
  }

  private final CallbackRouter router = new CallbackRouter();

  @Nested
  class Registering {

    @Test
    void registering_a_second_agent_under_a_name_already_taken_throws() {
      Agent<String> first = agentNamed("keeper");
      Agent<String> second = agentNamed("keeper");
      router.register(first);

      assertThatThrownBy(() -> router.register(second))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keeper");
    }
  }

  @Nested
  class Resuming {

    @Test
    void resuming_a_registered_agent_delegates_to_its_own_resume_door() {
      Agent<String> agent = agentNamed("keeper");
      router.register(agent);
      ParkToken unknownToken = ParkToken.generate();
      ToolResolution allow = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> router.resume("keeper", unknownToken, allow))
          .isInstanceOf(UnknownParkTokenException.class);
    }

    @Test
    void resuming_a_name_no_agent_was_ever_registered_under_throws_naming_it() {
      ParkToken token = ParkToken.generate();
      ToolResolution allow = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> router.resume("nobody", token, allow))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("nobody");
    }

    /**
     * The single-agent test above can't tell name-correct routing from grab-any-agent routing —
     * only one candidate was ever on file. Two real agents registered, a real token minted under
     * agent-a's own park, then resumed by agent-b's name: {@code resume} routes by the name it was
     * given, not by whichever agent happens to be registered, and the mismatch surfaces as the same
     * {@link WrongAgentException} an agent's own doors already refuse a foreign token with
     * (precedented at {@code AgentDoorsTest.Cross_agent_refusal}).
     */
    @Test
    void resuming_by_the_wrong_name_is_refused_naming_both_agents() {
      ToolCall call = new ToolCall("a1", "search", JsonNodeFactory.instance.objectNode());
      ScriptedProvider provider =
          new ScriptedProvider().turn(new ModelEvent.ToolUseEmitted(call), endWithToolUse());
      ParkingApprover approver = new ParkingApprover();
      Harness harness = Nessy.harness(h -> h.provider(provider));
      Agent<String> agentA =
          harness.agent(
              a ->
                  a.name("agent-a")
                      .model("model-a")
                      .tools(ToolGrant.grant(new SearchTool(), UsagePolicy.requireApproval()))
                      .approver(approver));
      Agent<String> agentB = harness.agent(a -> a.name("agent-b").model("model-b"));
      router.register(agentA);
      router.register(agentB);
      agentA.converse().tell("search for a");
      ParkToken tokenA = approver.token();
      ToolResolution.Decided decided = new ToolResolution.Decided(Decision.allow());

      assertThatThrownBy(() -> router.resume("agent-b", tokenA, decided))
          .isInstanceOf(WrongAgentException.class)
          .hasMessageContaining("agent-a")
          .hasMessageContaining("agent-b");
    }
  }

  private static ModelEvent.TurnEnded endWithToolUse() {
    return new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero());
  }
}
