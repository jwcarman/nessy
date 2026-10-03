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
package org.jwcarman.nessy.engine.narration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.backend.inmemory.InMemoryLocks;
import org.jwcarman.nessy.engine.narration.AfterCommit.Step;

/**
 * What a listener is told, and when: after the step that wrote it commits, never if it rolled back,
 * and in the order the steps committed in, for each agent.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AfterCommitTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final long PATIENCE_SECONDS = 20;

  private final AgentId agent = AgentId.random();
  private final AgentId other = AgentId.random();
  private final List<String> heard = new CopyOnWriteArrayList<>();
  private final AfterCommit narration =
      new AfterCommit(
          (_, agentId, event) ->
              heard.add(label(agentId) + ":" + ((Narration.TurnEnded) event).turn().value()));

  private String label(AgentId who) {
    return who.equals(agent) ? "agent" : "other";
  }

  private static Narration.TurnEnded ended(long turn) {
    return new Narration.TurnEnded(new TurnId(turn));
  }

  private static String told(String who, long turn) {
    return who + ":" + turn;
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_step_that_commits {

    @Test
    void is_heard_in_the_order_it_narrated_once_the_lock_has_returned() {
      List<String> heardInside = new CopyOnWriteArrayList<>();

      narration.locked(
          new InMemoryLocks(),
          TYPE,
          agent,
          step -> {
            step.narrate(ended(1));
            step.narrate(ended(2));
            heardInside.addAll(heard);
            return null;
          });

      assertThat(heardInside).as("told nothing while the step was still inside the lock").isEmpty();
      assertThat(heard).containsExactly(told("agent", 1), told("agent", 2));
    }

    @Test
    void is_heard_even_when_the_work_caught_an_exception_of_its_own() {
      narration.locked(
          new InMemoryLocks(),
          TYPE,
          agent,
          step -> {
            step.narrate(ended(1));
            try {
              throw new IllegalStateException("handled inside the work");
            } catch (IllegalStateException _) {
              step.narrate(ended(2));
            }
            return null;
          });

      assertThat(heard).containsExactly(told("agent", 1), told("agent", 2));
    }

    @Test
    void hands_back_what_the_work_returned() {
      String returned = narration.locked(new InMemoryLocks(), TYPE, agent, _ -> "done");

      assertThat(returned).isEqualTo("done");
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_step_that_fails {

    @Test
    void whose_work_throws_after_narrating_is_never_heard() {
      InMemoryLocks locks = new InMemoryLocks();

      assertThatThrownBy(
              () ->
                  narration.locked(
                      locks,
                      TYPE,
                      agent,
                      step -> {
                        step.narrate(ended(1));
                        throw new IllegalStateException("the work failed");
                      }))
          .isInstanceOf(IllegalStateException.class);
      narration.narrate(TYPE, agent, ended(2));

      assertThat(heard)
          .as("the later narration is told and the lost one never is")
          .containsExactly(told("agent", 2));
    }

    @Test
    void whose_commit_fails_is_never_heard_though_the_work_finished() {
      CommitProbe locks = new CommitProbe(new InMemoryLocks()).failTheCommitOfCall(1);

      assertThatThrownBy(
              () ->
                  narration.locked(
                      locks,
                      TYPE,
                      agent,
                      step -> {
                        step.narrate(ended(1));
                        return null;
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("the commit of call 1 failed");
      narration.narrate(TYPE, agent, ended(2));

      assertThat(heard).containsExactly(told("agent", 2));
    }

    @Test
    void whose_work_caught_its_own_exception_is_still_not_heard_when_the_commit_fails() {
      CommitProbe locks = new CommitProbe(new InMemoryLocks()).failTheCommitOfCall(1);

      assertThatThrownBy(
              () ->
                  narration.locked(
                      locks,
                      TYPE,
                      agent,
                      step -> {
                        step.narrate(ended(1));
                        try {
                          throw new IllegalArgumentException("handled inside the work");
                        } catch (IllegalArgumentException _) {
                          return null;
                        }
                      }))
          .isInstanceOf(IllegalStateException.class);
      narration.narrate(TYPE, agent, ended(2));

      assertThat(heard).containsExactly(told("agent", 2));
    }

    @Test
    void does_not_hold_up_what_was_queued_behind_it() {
      Step failing = narration.reserve(TYPE, agent);
      failing.narrate(ended(1));
      narration.narrate(TYPE, agent, ended(2));
      assertThat(heard).as("waiting behind the step").isEmpty();

      failing.cancel();

      assertThat(heard).containsExactly(told("agent", 2));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Order {

    @Test
    void two_steps_of_one_agent_are_heard_in_commit_order_though_the_first_thread_is_slow() {
      CommitProbe locks = new CommitProbe(new InMemoryLocks());
      CommitProbe.Hold held = locks.holdTheReturnOfCall(1);
      CompletableFuture<Void> first =
          CompletableFuture.runAsync(
              () ->
                  narration.locked(
                      locks,
                      TYPE,
                      agent,
                      step -> {
                        step.narrate(ended(1));
                        return null;
                      }));
      held.awaitReached();

      narration.locked(
          locks,
          TYPE,
          agent,
          step -> {
            step.narrate(ended(2));
            return null;
          });

      assertThat(heard).as("the second step committed, but the first is ahead of it").isEmpty();
      held.release();
      first.orTimeout(PATIENCE_SECONDS, TimeUnit.SECONDS).join();
      assertThat(heard).containsExactly(told("agent", 1), told("agent", 2));
    }

    @Test
    void narration_from_outside_any_step_is_heard_at_once_when_nothing_is_ahead_of_it() {
      narration.narrate(TYPE, agent, ended(1));

      assertThat(heard).containsExactly(told("agent", 1));
    }

    @Test
    void narration_from_outside_a_step_does_not_overtake_a_step_reserved_before_it() {
      Step reserved = narration.reserve(TYPE, agent);
      reserved.narrate(ended(1));

      narration.narrate(TYPE, agent, ended(2));
      assertThat(heard).isEmpty();
      reserved.release();

      assertThat(heard).containsExactly(told("agent", 1), told("agent", 2));
    }

    @Test
    void a_step_that_resolves_first_waits_for_the_one_reserved_ahead_of_it() {
      Step first = narration.reserve(TYPE, agent);
      Step second = narration.reserve(TYPE, agent);
      first.narrate(ended(1));
      second.narrate(ended(2));

      second.release();
      assertThat(heard).isEmpty();
      first.release();

      assertThat(heard).containsExactly(told("agent", 1), told("agent", 2));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class Another_agent {

    @Test
    void is_not_held_up_by_a_step_that_has_not_resolved() {
      Step reserved = narration.reserve(TYPE, agent);
      reserved.narrate(ended(1));

      narration.narrate(TYPE, other, ended(7));
      narration.locked(
          new InMemoryLocks(),
          TYPE,
          other,
          step -> {
            step.narrate(ended(8));
            return null;
          });

      assertThat(heard)
          .as("the other agent is heard while this one is still waiting")
          .containsExactly(told("other", 7), told("other", 8));
      reserved.release();
      assertThat(heard).endsWith(told("agent", 1));
    }
  }

  @Nested
  @DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
  class A_step_that_is_never_resolved {

    @Test
    void is_dropped_after_the_bound_so_what_is_behind_it_is_heard() throws InterruptedException {
      CountDownLatch behind = new CountDownLatch(1);
      AfterCommit impatient =
          new AfterCommit(
              (_, agentId, event) -> {
                heard.add(label(agentId) + ":" + ((Narration.TurnEnded) event).turn().value());
                behind.countDown();
              },
              Duration.ofMillis(50));
      Step lost = impatient.reserve(TYPE, agent);
      lost.narrate(ended(1));
      impatient.narrate(TYPE, agent, ended(2));

      assertThat(behind.await(PATIENCE_SECONDS, TimeUnit.SECONDS)).isTrue();

      assertThat(heard).as("what was lost is not told").containsExactly(told("agent", 2));
      lost.release();
      assertThat(heard).as("a late release changes nothing").containsExactly(told("agent", 2));
      impatient.close();
    }
  }
}
