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
package org.jwcarman.nessy.engine.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;

@DisplayName("What an agent does with what the model said")
class ModelLogicTest {

  private static final Usage NOTHING_MEASURED = new Usage(null, null, null, null);

  private static AgentState calling() {
    return AgentState.idle().taking(TurnId.of("turn-1"), "claim-1");
  }

  private static Decision answered(StopReason stopReason) {
    return AgentLogic.decide(
        calling(), new Input.ModelAnswered.Answered(stopReason, NOTHING_MEASURED));
  }

  @Nested
  class Prose {

    @Test
    void an_answer_ends_the_turn() {
      Decision decision = answered(StopReason.END_TURN);

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then())
          .containsExactly(
              new Instruction.Remember.Answer(),
              new Instruction.Narrate.TurnEnded(new TurnResult.Completed(), NOTHING_MEASURED),
              new Instruction.Release(),
              new Instruction.TakeWork());
    }

    @Test
    void an_answer_cut_off_at_the_ceiling_is_truncated_rather_than_completed() {
      Decision decision = answered(StopReason.MAX_TOKENS);

      assertThat(decision.then())
          .contains(
              new Instruction.Narrate.TurnEnded(new TurnResult.Truncated(), NOTHING_MEASURED));
    }

    @Test
    void the_claim_id_survives_the_turn_so_the_next_take_can_sweep_it() {
      assertThat(answered(StopReason.END_TURN).next().observation()).isEqualTo("claim-1");
    }

    @Test
    void remembering_happens_before_releasing_the_claims_it_is_written_from() {
      List<Instruction> then = answered(StopReason.END_TURN).then();

      assertThat(then.indexOf(new Instruction.Remember.Answer()))
          .isLessThan(then.indexOf(new Instruction.Release()));
    }
  }

  @Nested
  class ToolRequests {

    private static Decision asked() {
      return AgentLogic.decide(
          calling(),
          new Input.ModelAnswered.Asked(
              List.of(
                  new Input.CallSummary(CallId.of("a"), "send_email"),
                  new Input.CallSummary(CallId.of("b"), "read_file")),
              NOTHING_MEASURED));
    }

    @Test
    void every_requested_call_starts_out_being_approved() {
      assertThat(asked().next().working().calls())
          .containsEntry(CallId.of("a"), new CallState.Approving("send_email"))
          .containsEntry(CallId.of("b"), new CallState.Approving("read_file"));
    }

    @Test
    void asking_the_approver_is_what_it_does_about_each_one() {
      assertThat(asked().then())
          .contains(
              new Instruction.AskApprover(CallId.of("a"), "send_email"),
              new Instruction.AskApprover(CallId.of("b"), "read_file"));
    }

    @Test
    void a_turn_working_tools_has_not_ended() {
      assertThat(asked().next().busy()).isTrue();
      assertThat(asked().then()).noneMatch(Instruction.Release.class::isInstance);
    }
  }

  @Nested
  class Trouble {

    @Test
    void a_refusal_ends_the_turn_rather_than_retrying_it() {
      Decision decision =
          AgentLogic.decide(
              calling(),
              new Input.ModelAnswered.Refused("safety", "not going to do that", NOTHING_MEASURED));

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then())
          .contains(
              new Instruction.Narrate.TurnEnded(
                  new TurnResult.Refused("safety", "not going to do that"), NOTHING_MEASURED));
    }

    @Test
    void a_failed_call_ends_the_turn_and_says_why() {
      Decision decision = AgentLogic.decide(calling(), new Input.ModelFailed("connection reset"));

      assertThat(decision.next().busy()).isFalse();
      assertThat(decision.then())
          .contains(
              new Instruction.Narrate.TurnEnded(
                  new TurnResult.Failed("connection reset"), Usage.unreported()));
    }
  }
}
