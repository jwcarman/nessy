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
package org.jwcarman.nessy.engine.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentStatus.Activity;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A status whose two reads straddle a step: the story read before the agent's next request was
 * written, the rows read after its approval parked. That is the moment the status read's own
 * javadoc allows for ("a status may be a step behind"); what it reports then must still agree with
 * itself.
 */
@Tag("container")
class StatusReadAcrossAStepTest {

  record Query(String q) {}

  /** Request one asks call_1; once it has an exchange, request two asks call_2; then it answers. */
  private final InferenceProvider model =
      (request, _) -> {
        int exchanges =
            request.context().turns().stream().mapToInt(turn -> turn.exchanges().size()).sum();
        return switch (exchanges) {
          case 0 ->
              new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")));
          case 1 ->
              new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_2", "lookup", "{\"q\":\"loch lomond\"}")));
          default -> new InferenceResult.Answer(List.of(new Block.Text("all done")));
        };
      };

  private final ConcurrentLinkedQueue<ApprovalRequest> asks = new ConcurrentLinkedQueue<>();
  private EngineFixture engine;

  @BeforeEach
  void startEngine() {
    engine = new EngineFixture(model);
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  private static Tool<Query> lookup() {
    return new Tool<>() {
      @Override
      public Class<Query> inputType() {
        return Query.class;
      }

      @Override
      public ToolName name() {
        return new ToolName("lookup");
      }

      @Override
      public String description() {
        return "looks a thing up";
      }

      @Override
      public Awaited<ToolResult> call(ToolCallRequest<Query> request) {
        return Awaited.ready(ToolResult.ok(new Block.Text("found")));
      }
    };
  }

  private long deferrals(AgentType type, AgentId agent) {
    return engine.story(type, agent).stream()
        .filter(AgentEvent.ApprovalDeferred.class::isInstance)
        .count();
  }

  @Test
  void a_waiting_status_always_says_what_it_is_waiting_on() {
    AgentType type = new AgentType("across-a-step-" + UUID.randomUUID());
    var harness =
        engine
            .harnesses()
            .create(
                type,
                String.class,
                config ->
                    config
                        .systemPrompt("You are a test assistant.")
                        .tool(
                            lookup(),
                            t ->
                                t.action(query -> "look up " + query.q())
                                    .approver(
                                        request -> {
                                          asks.add(request);
                                          return Awaited.deferred();
                                        },
                                        a -> a.timeout(Duration.ofMinutes(30))))
                        .inference(in -> in.model("a-model"))
                        .effects(e -> e.pollInterval(Duration.ofMillis(50))));
    AgentId agent = new AgentId(UUID.randomUUID());
    harness.tell(agent, "what lake?");
    await().atMost(Duration.ofSeconds(20)).until(() -> deferrals(type, agent) == 1);
    // The story as a status read would have seen it a moment before the next request was written.
    List<AgentEvent> before = engine.events().sinceLastTurnStarted(type, agent);
    ApprovalRequest first = asks.peek();
    assertThat(
            engine
                .replies()
                .approve(
                    first.agentType(),
                    first.agentId(),
                    first.idempotencyKey(),
                    ApprovalResult.approved()))
        .isEqualTo(new ReplyOutcome.Applied());
    await().atMost(Duration.ofSeconds(20)).until(() -> deferrals(type, agent) == 2);

    AgentStatus status =
        StoredAgentWork.queued(new StoryReadEarlier(engine.backend(), before), Clock.systemUTC())
            .status(type, agent);

    assertThat(status.activity()).isEqualTo(Activity.WAITING);
    assertThat(status.waitingApprovals().size() + status.waitingToolCalls())
        .as("a WAITING status names what it waits on")
        .isPositive();
  }

  /**
   * A backend whose first story read of an agent returns what it held before the latest step was
   * written, and whose later reads are the real ones: a read that is one step old.
   */
  private record StoryReadEarlier(
      QueuedBackend backend, List<AgentEvent> earlier, Set<AgentId> served)
      implements QueuedBackend {

    StoryReadEarlier(QueuedBackend backend, List<AgentEvent> earlier) {
      this(backend, earlier, ConcurrentHashMap.newKeySet());
    }

    @Override
    public AgentEvents events() {
      AgentEvents real = backend.events();
      return new AgentEvents() {
        @Override
        public void append(
            AgentType type, AgentId agent, List<AgentEvent> events, Seq expectedLast, Instant at) {
          real.append(type, agent, events, expectedLast, at);
        }

        @Override
        public Stream<AgentEvent> streamFrom(AgentType type, AgentId agent, Seq after) {
          return real.streamFrom(type, agent, after);
        }

        @Override
        public List<Written> readWrittenFrom(AgentType type, AgentId agent, Seq after, int limit) {
          return real.readWrittenFrom(type, agent, after, limit);
        }

        @Override
        public List<AgentEvent> sinceLastTurnStarted(AgentType type, AgentId agent) {
          return served.add(agent) ? earlier : real.sinceLastTurnStarted(type, agent);
        }

        @Override
        public Instant writtenAt(AgentType type, AgentId agent, Seq seq) {
          return real.writtenAt(type, agent, seq);
        }
      };
    }

    @Override
    public Payloads payloads() {
      return backend.payloads();
    }

    @Override
    public Locks locks() {
      return backend.locks();
    }

    @Override
    public Agents agents() {
      return backend.agents();
    }

    @Override
    public Effects effects() {
      return backend.effects();
    }

    @Override
    public Chapters chapters() {
      return backend.chapters();
    }

    @Override
    public Leases leases() {
      return backend.leases();
    }

    @Override
    public AgentTurns turns() {
      return backend.turns();
    }

    @Override
    public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
      return backend.backlogs(inputType);
    }

    @Override
    public int queued(AgentType type, AgentId agent) {
      return backend.queued(type, agent);
    }
  }
}
