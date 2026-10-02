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
package org.jwcarman.nessy.engine.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.inmemory.InMemoryAgentEvents;
import org.jwcarman.nessy.backend.inmemory.InMemoryPayloads;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EventStreamHistoryTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final AgentId AGENT = new AgentId(UUID.randomUUID());

  private final JacksonCodecFactory codecs = new JacksonCodecFactory(JsonMapper.builder().build());
  private final InMemoryAgentEvents events = new InMemoryAgentEvents(codecs, Clock.systemUTC());
  private final InMemoryPayloads payloads = new InMemoryPayloads(codecs);
  private Seq last = Seq.NONE;

  private EventStreamHistory history() {
    return new EventStreamHistory(events, new Transcript(payloads), TYPE, AGENT);
  }

  private void append(AgentEvent... appended) {
    events.append(TYPE, AGENT, List.of(appended), last);
    last = appended[appended.length - 1].seq();
  }

  /** A finished turn that opens at {@code id} and takes two seqs: its input and its answer. */
  private void completedTurn(long id) {
    PayloadRef input = payloads.put(List.of(new Block.Text("q" + id)));
    PayloadRef answer = payloads.put(List.of(new Block.Text("a" + id)));
    append(
        new AgentEvent.TurnStarted(new Seq(id), new TurnId(id), input, Instant.now()),
        new AgentEvent.InferenceAnswered(
            new Seq(id + 1), new TurnId(id), answer, Usage.unreported()));
  }

  private void turnUnderWay(long id) {
    PayloadRef input = payloads.put(List.of(new Block.Text("q" + id)));
    append(new AgentEvent.TurnStarted(new Seq(id), new TurnId(id), input, Instant.now()));
  }

  private static List<Long> ids(List<Turn> turns) {
    return turns.stream().map(turn -> turn.id().value()).toList();
  }

  private static List<Long> values(List<TurnId> turns) {
    return turns.stream().map(TurnId::value).toList();
  }

  @Nested
  class Turns_between_two_turns {

    @Test
    void are_returned_with_both_ends_included_oldest_first() {
      completedTurn(1);
      completedTurn(3);
      completedTurn(5);
      completedTurn(7);

      List<Turn> found = history().turnsBetween(new TurnId(3), new TurnId(5));

      assertThat(ids(found)).containsExactly(3L, 5L);
    }

    @Test
    void are_found_when_the_ids_are_not_consecutive() {
      completedTurn(1);
      completedTurn(3);
      completedTurn(5);

      List<Turn> found = history().turnsBetween(new TurnId(3), new TurnId(5));

      assertThat(ids(found)).containsExactly(3L, 5L);
    }

    @Test
    void are_whole_with_their_input_and_result() {
      completedTurn(1);

      List<Turn> found = history().turnsBetween(new TurnId(1), new TurnId(1));

      assertThat(found).hasSize(1);
      assertThat(found.getFirst().input().blocks()).containsExactly(new Block.Text("q1"));
      assertThat(found.getFirst().complete()).isTrue();
    }

    @Test
    void are_empty_when_the_range_holds_no_turn() {
      completedTurn(1);
      completedTurn(5);

      List<Turn> found = history().turnsBetween(new TurnId(2), new TurnId(4));

      assertThat(found).isEmpty();
    }
  }

  @Nested
  class Completed_turns_after_a_turn {

    @Test
    void are_every_completed_turn_when_there_is_no_turn_to_start_after() {
      completedTurn(1);
      completedTurn(3);

      List<TurnId> found = history().completedAfter(Optional.empty());

      assertThat(values(found)).containsExactly(1L, 3L);
    }

    @Test
    void are_only_the_later_ones_when_a_turn_is_named() {
      completedTurn(1);
      completedTurn(3);
      completedTurn(5);

      List<TurnId> found = history().completedAfter(Optional.of(new TurnId(3)));

      assertThat(values(found)).containsExactly(5L);
    }

    @Test
    void leave_out_the_turn_that_is_still_under_way() {
      completedTurn(1);
      completedTurn(3);
      turnUnderWay(5);

      List<TurnId> found = history().completedAfter(Optional.empty());

      assertThat(values(found)).containsExactly(1L, 3L);
    }

    @Test
    void are_empty_when_nothing_completed_follows() {
      completedTurn(1);
      turnUnderWay(3);

      List<TurnId> found = history().completedAfter(Optional.of(new TurnId(1)));

      assertThat(found).isEmpty();
    }
  }
}
