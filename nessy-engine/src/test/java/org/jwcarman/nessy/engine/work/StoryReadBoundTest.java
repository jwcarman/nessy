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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.AgentEffect;
import org.jwcarman.nessy.backend.effect.Attempt;
import org.jwcarman.nessy.backend.effect.EffectOutcome;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryQueuedBackend;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.jwcarman.nessy.backend.turn.AgentTurns;
import tools.jackson.databind.json.JsonMapper;

/**
 * A parked row the story does not hold is looked for once more, and only once more. A story that is
 * still without the request on the second read does not fail the read and is not read a third time.
 */
class StoryReadBoundTest {

  private final InMemoryQueuedBackend memory =
      new InMemoryQueuedBackend(new JacksonCodecFactory(JsonMapper.builder().build()));

  private void parkTwoRowsOfOneAgent(AgentType type, AgentId agent) {
    Instant longAgo = Instant.now().minus(Duration.ofHours(1));
    for (int i = 0; i < 2; i++) {
      CallId callId = new CallId("call_" + i);
      memory
          .effects()
          .insert(
              type,
              agent,
              new AgentEffect.Approve(
                  new TurnId(1),
                  new Seq(2),
                  callId,
                  new ToolName("lookup"),
                  IdempotencyKey.of(UUID.randomUUID())),
              Duration.ofMinutes(30),
              new EffectOutcome.ToolFailed(callId, CallFailure.FAILED, "undispatchable"),
              Instant.now().plus(Duration.ofDays(1)),
              null,
              longAgo.plusMillis(i));
    }
    List<Attempt> claimed = memory.effects().markRunning(type, Instant.now(), 2);
    assertThat(claimed).hasSize(2);
    claimed.forEach(
        attempt ->
            memory.effects().park(attempt.effectId(), attempt.attemptsMade(), Instant.now()));
  }

  @Test
  void a_story_that_is_still_without_the_request_is_read_twice_and_the_rows_are_skipped() {
    AgentType type = new AgentType("story-read-bound");
    AgentId agent = new AgentId(UUID.randomUUID());
    parkTwoRowsOfOneAgent(type, agent);
    AtomicInteger storyReads = new AtomicInteger();
    StoredAgentWork work =
        StoredAgentWork.queued(new Counting(memory, storyReads), Clock.systemUTC());

    List<ApprovalRequest> waiting = work.waitingApprovals(type);

    assertThat(waiting).isEmpty();
    assertThat(storyReads.get())
        .as("one read, and one more for the agent, however many rows it has")
        .isEqualTo(2);
  }

  /** A backend whose stories are counted as they are read. */
  private record Counting(QueuedBackend backend, AtomicInteger storyReads)
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
          storyReads.incrementAndGet();
          return real.sinceLastTurnStarted(type, agent);
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
