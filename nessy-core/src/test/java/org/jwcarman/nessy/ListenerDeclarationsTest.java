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

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ContextOverflowException;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;

class ListenerDeclarationsTest {

  record Nothing() {}

  /** First call asks for the noisy tool; second call answers plainly. */
  private static final class ToolCallingProvider implements ModelProvider {

    private int calls;

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      calls++;
      List<ModelEvent> turn =
          calls == 1
              ? List.of(
                  new ModelEvent.ToolUseEmitted(
                      new ToolCall("c1", "noisy", JsonNodeFactory.instance.objectNode())),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              : List.of(
                  new ModelEvent.TextChunk("done"),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
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
  }

  /** A tool that reports progress through its context before finishing. */
  private static final class NoisyTool implements Tool<Nothing> {
    @Override
    public String name() {
      return "noisy";
    }

    @Override
    public String description() {
      return "Reports progress, then finishes";
    }

    @Override
    public Class<Nothing> inputType() {
      return Nothing.class;
    }

    @Override
    public Awaited<ToolResult> execute(Nothing input, ToolContext context) {
      context.events().emit(new ToolProgress(context.conversationId(), "c1", "halfway"));
      return Awaited.ready(ToolResult.ok("done"));
    }
  }

  @Test
  void the_sync_sugar_hears_the_whole_gated_tool_story() {
    ConcurrentLinkedQueue<String> heard = new ConcurrentLinkedQueue<>();
    Agent<String> agent =
        Nessy.harness(h -> h.provider(new ToolCallingProvider()))
            .agent(
                a ->
                    a.name("listener")
                        .model("fake-model")
                        .tools(ToolGrant.grant(new NoisyTool(), UsagePolicy.requireApproval()))
                        .onAgentTold(fact -> heard.add("told"))
                        .onApprovalRequested(
                            event -> heard.add("approval:" + event.request().call().name()))
                        .onToolProgress(event -> heard.add("progress:" + event.message()))
                        .onToolFinished(fact -> heard.add("finished:" + fact.call().name()))
                        .onModelResponded(fact -> heard.add("responded:" + fact.reason())));

    agent.converse().tell("go");

    assertThat(heard)
        .containsExactly(
            "told",
            "responded:TOOL_USE",
            "approval:noisy",
            "progress:halfway",
            "finished:noisy",
            "responded:END_TURN");
  }

  @Test
  void the_model_call_failed_sugar_hears_the_overflow() {
    ConcurrentLinkedQueue<String> heard = new ConcurrentLinkedQueue<>();
    ModelProvider overflowing =
        new ModelProvider() {
          @Override
          public Set<Capability> capabilities() {
            return Set.of();
          }

          @Override
          public ModelStream stream(ModelRequest request) {
            throw new ContextOverflowException("too long");
          }
        };
    Agent<String> agent =
        Nessy.harness(h -> h.provider(overflowing))
            .agent(
                a ->
                    a.name("listener")
                        .model("fake-model")
                        .onModelCallFailed(fact -> heard.add("failed:" + fact.reason())));

    agent.converse().tell("go");

    assertThat(heard).containsExactly("failed:too long");
  }

  @Test
  void the_async_sugar_hears_the_same_story_off_thread() throws InterruptedException {
    CountDownLatch heard = new CountDownLatch(5);
    Agent<String> agent =
        Nessy.harness(h -> h.provider(new ToolCallingProvider()))
            .agent(
                a ->
                    a.name("listener")
                        .model("fake-model")
                        .tools(ToolGrant.grant(new NoisyTool(), UsagePolicy.requireApproval()))
                        .onAgentToldAsync(fact -> heard.countDown())
                        .onApprovalRequestedAsync(event -> heard.countDown())
                        .onToolProgressAsync(event -> heard.countDown())
                        .onToolFinishedAsync(fact -> heard.countDown())
                        .onModelRespondedAsync(fact -> heard.countDown()));

    agent.converse().tell("go");

    assertThat(heard.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void the_async_model_call_failed_sugar_hears_the_overflow_off_thread()
      throws InterruptedException {
    CountDownLatch heard = new CountDownLatch(1);
    ModelProvider overflowing =
        new ModelProvider() {
          @Override
          public Set<Capability> capabilities() {
            return Set.of();
          }

          @Override
          public ModelStream stream(ModelRequest request) {
            throw new ContextOverflowException("too long");
          }
        };
    Agent<String> agent =
        Nessy.harness(h -> h.provider(overflowing))
            .agent(
                a ->
                    a.name("listener")
                        .model("fake-model")
                        .onModelCallFailedAsync(fact -> heard.countDown()));

    agent.converse().tell("go");

    assertThat(heard.await(5, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void harness_level_sugar_seeds_into_every_agent_it_builds() {
    ConcurrentLinkedQueue<String> heard = new ConcurrentLinkedQueue<>();
    Agent<String> agent =
        Nessy.harness(
                h ->
                    h.provider(new ToolCallingProvider())
                        .onModelResponded(fact -> heard.add("harness:" + fact.reason())))
            .agent(
                a ->
                    a.name("listener")
                        .model("fake-model")
                        .tools(ToolGrant.grant(new NoisyTool(), UsagePolicy.requireApproval())));

    agent.converse().tell("go");

    assertThat(heard).containsExactly("harness:TOOL_USE", "harness:END_TURN");
  }
}
