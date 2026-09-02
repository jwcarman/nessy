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
package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.backlog.BacklogCoalescer;
import org.jwcarman.nessy.api.backlog.BacklogItem;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.engine.agent.AgentLogic;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.Decision;
import org.jwcarman.nessy.engine.agent.Input;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * The two facts that only work together, pinned in one place.
 *
 * <p>{@code AgentLogic.onWorkTaken} drops a reply arriving outside {@code AwaitingWork} because two
 * takes really can be outstanding at once and the second is trusted to name the SAME row as the
 * first — {@code BacklogStore.take} is stranded-first, so a row already marked taken comes straight
 * back rather than being claimed again. {@code IdleLogicTest} pins the logic's half in isolation
 * and {@code BacklogStoreTest} pins the store's half in isolation, and the design doc calls that
 * out by name: nothing fails if one of them drifts while the other does not, because they live in
 * different files and nothing ties them together. This test drives a REAL store and feeds its real
 * replies to the real logic, so a change to either half that breaks the pairing fails here even
 * though each file alone still passes.
 */
@DisplayName("A second take in flight names the row the first one is already working")
class StrandedTakeInvariantTest {

  private static final AgentId AGENT = AgentId.of("racer");

  private DataSource dataSource;
  private Claims claims;

  @BeforeEach
  void freshDatabase() {
    dataSource = TestDatabase.fresh();
    claims = new Claims(dataSource);
  }

  private BacklogStore<String> store() {
    ObjectMapper mapper = EngineMapper.create();
    BacklogCoalescer<String> keepAll =
        (waiting, arrival) -> {
          List<BacklogItem<String>> all = new ArrayList<>(waiting);
          all.add(arrival);
          return all;
        };
    return new BacklogStore<>(
        dataSource,
        claims,
        JsonCodec.of(mapper, String.class),
        JsonCodec.of(mapper, UserMessage.class),
        UserMessage::of,
        keepAll,
        Clock.system(ZoneOffset.UTC));
  }

  @Test
  @DisplayName("the second reply starts no second turn, because it names the row the first started")
  void a_duplicate_take_reply_is_dropped_because_the_row_it_names_is_already_being_worked() {
    BacklogStore<String> store = store();
    store.offer(AGENT, "door opened");

    // Two takes really can be outstanding at once: recovery re-asks without knowing whether the
    // first ask was ever recorded. Both go to the SAME real store, exactly as they would from two
    // instructions issued before either answer comes back.
    BacklogStore.TakeResult first = store.take(AGENT, null);
    BacklogStore.TakeResult second = store.take(AGENT, null);

    assertThat(first).isInstanceOf(BacklogStore.TakeResult.Work.class);
    assertThat(second)
        .as("stranded-first: the second take hands back the row the first one already claimed")
        .isEqualTo(first);

    BacklogStore.TakeResult.Work firstWork = (BacklogStore.TakeResult.Work) first;
    BacklogStore.TakeResult.Work secondWork = (BacklogStore.TakeResult.Work) second;

    // The agent is now AwaitingWork, having asked once. The first reply starts the turn.
    AgentState awaiting = AgentState.idle().asking();
    Decision started =
        AgentLogic.decide(
            awaiting, new Input.WorkTaken(firstWork.turnId(), firstWork.observationClaim()));

    assertThat(started.next().busy()).as("a turn is now running").isTrue();

    // The second reply, naming the SAME row, arrives after the turn has already started. Were
    // `take` not stranded-first, this would name a fresh row and this decision would start a
    // second turn on top of the first one.
    Decision duplicate =
        AgentLogic.decide(
            started.next(),
            new Input.WorkTaken(secondWork.turnId(), secondWork.observationClaim()));

    assertThat(duplicate.next())
        .as("the duplicate reply changes nothing about the running turn")
        .isEqualTo(started.next());
    assertThat(duplicate.then()).as("and issues no further instructions").isEmpty();
  }
}
