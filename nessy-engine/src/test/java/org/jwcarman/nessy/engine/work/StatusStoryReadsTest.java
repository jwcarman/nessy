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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.jwcarman.nessy.api.QueuedHarness;
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
import org.jwcarman.nessy.engine.EngineFixture;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * How many times a status reads an agent's story, and what it reports when its first read is a step
 * older than the rows it reads after it.
 */
@Tag("container")
class StatusStoryReadsTest {

  record Query(String q) {}

  /** Each turn asks for one approved call, then answers once the call is settled. */
  private final InferenceProvider model =
      (request, _) ->
          request.context().turns().getLast().exchanges().isEmpty()
              ? new InferenceResult.Actions(
                  List.of(new Block.ToolCall("call_1", "lookup", "{\"q\":\"loch ness\"}")))
              : new InferenceResult.Answer(List.of(new Block.Text("all done")));

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

  private QueuedHarness<String> harness(AgentType type) {
    return engine
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
                                      request.fact("approver", "added this");
                                      asks.add(request);
                                      return Awaited.deferred();
                                    },
                                    a -> a.timeout(Duration.ofMinutes(30))))
                    .inference(in -> in.model("a-model"))
                    .effects(e -> e.pollInterval(Duration.ofMillis(50))));
  }

  private long deferrals(AgentType type, AgentId agent) {
    return engine.story(type, agent).stream()
        .filter(AgentEvent.ApprovalDeferred.class::isInstance)
        .count();
  }

  private void parked(AgentType type, AgentId agent, long count) {
    await().atMost(Duration.ofSeconds(20)).until(() -> deferrals(type, agent) == count);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> engine.work().waitingApprovals(type).size() == 1);
  }

  @Test
  void a_status_of_a_waiting_agent_reads_its_story_once() {
    AgentType type = new AgentType("status-reads-once-" + UUID.randomUUID());
    AgentId agent = new AgentId(UUID.randomUUID());
    harness(type).tell(agent, "what lake?");
    parked(type, agent, 1);
    AtomicInteger reads = new AtomicInteger();

    AgentStatus status =
        StoredAgentWork.queued(
                new Reading(engine.backend(), Optional.empty(), reads), Clock.systemUTC())
            .status(type, agent);

    assertThat(status.waitingApprovals()).hasSize(1);
    assertThat(reads.get()).as("one story read in the normal case").isEqualTo(1);
  }

  /**
   * The deferral event and the row's park mark are written in one step. A status whose story read
   * lands just before that step, and whose row read lands after it, finds the request in the story
   * and so never reads again -- and rebuilds the approval without the facts the approver left.
   */
  @Test
  void a_status_read_just_before_the_deferral_was_recorded_shows_the_approvers_facts() {
    AgentType type = new AgentType("status-facts-" + UUID.randomUUID());
    AgentId agent = new AgentId(UUID.randomUUID());
    harness(type).tell(agent, "what lake?");
    parked(type, agent, 1);
    List<AgentEvent> beforeTheDeferral = withoutTheDeferral(engine.story(type, agent));

    AgentStatus status =
        StoredAgentWork.queued(
                new Reading(engine.backend(), Optional.of(beforeTheDeferral), new AtomicInteger()),
                Clock.systemUTC())
            .status(type, agent);

    assertThat(status.activity()).isEqualTo(Activity.WAITING);
    assertThat(status.waitingApprovals()).hasSize(1);
    assertThat(status.waitingApprovals().getFirst().facts().path("approver").asString())
        .as("the facts the approver left, as waitingApprovals() shows them")
        .isEqualTo("added this");
  }

  /**
   * The first story read is of the turn before; between it and the row read that turn answered, the
   * next turn started, and its approval parked. The status names the old turn beside an approval of
   * the new one.
   */
  @Test
  void a_status_read_across_a_turn_names_the_turn_its_approval_belongs_to() {
    AgentType type = new AgentType("status-across-a-turn-" + UUID.randomUUID());
    AgentId agent = new AgentId(UUID.randomUUID());
    QueuedHarness<String> harness = harness(type);
    harness.tell(agent, "what lake?");
    parked(type, agent, 1);
    List<AgentEvent> firstTurn = engine.events().sinceLastTurnStarted(type, agent);
    ApprovalRequest first = asks.poll();
    harness.tell(agent, "and which loch?");
    assertThat(
            engine
                .replies()
                .approve(
                    first.agentType(),
                    first.agentId(),
                    first.idempotencyKey(),
                    ApprovalResult.approved()))
        .isEqualTo(new ReplyOutcome.Applied());
    parked(type, agent, 2);

    AgentStatus status =
        StoredAgentWork.queued(
                new Reading(engine.backend(), Optional.of(firstTurn), new AtomicInteger()),
                Clock.systemUTC())
            .status(type, agent);

    assertThat(status.activity()).isEqualTo(Activity.WAITING);
    assertThat(status.waitingApprovals()).hasSize(1);
    assertThat(status.turn())
        .as("the turn in progress is the turn of the approval it waits on")
        .contains(status.waitingApprovals().getFirst().turn());
  }

  /** The story as it stood just before the step that recorded the (only) deferral. */
  private static List<AgentEvent> withoutTheDeferral(List<AgentEvent> story) {
    List<AgentEvent> sinceTurn =
        story.subList(
            story.stream().map(AgentEvent.TurnStarted.class::isInstance).toList().lastIndexOf(true),
            story.size());
    return sinceTurn.stream()
        .takeWhile(event -> !(event instanceof AgentEvent.ApprovalDeferred))
        .toList();
  }

  /** A backend that counts story reads and may serve a step-old story on the first read. */
  private record Reading(
      QueuedBackend backend, Optional<List<AgentEvent>> firstRead, AtomicInteger reads)
      implements QueuedBackend {

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
          return reads.incrementAndGet() == 1 && firstRead.isPresent()
              ? firstRead.get()
              : real.sinceLastTurnStarted(type, agent);
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
    public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
      return backend.backlogs(inputType);
    }

    @Override
    public int queued(AgentType type, AgentId agent) {
      return backend.queued(type, agent);
    }
  }
}
