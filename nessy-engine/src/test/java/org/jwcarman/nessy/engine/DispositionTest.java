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

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.Instruction;

/**
 * Which instructions become rows, and which never do.
 *
 * <p>Three answers, and each mistake has its own shape. A durable one treated as narration is work
 * silently lost to a crash. A narration treated as durable is a row per token. And an alarm treated
 * as durable is a deadline that is armed AFTER the decision that armed it commits -- a window in
 * which a settled call still holds a live reminder.
 */
@DisplayName("What an instruction is made of")
class DispositionTest {

  @Test
  @DisplayName("external work is durable")
  void work_that_leaves_the_process_is_durable() {
    assertThat(Disposition.of(new Instruction.CallModel())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.RunTool(CallId.of("c"), "look")))
        .isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.AskApprover(CallId.of("c"), "look")))
        .isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.TakeWork())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.Remember.Exchange())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.Release())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Instruction.Forget())).isEqualTo(Disposition.DURABLE);
  }

  @Test
  @DisplayName("a deadline is written inside the transition that decided it")
  void alarms_are_transactional() {
    assertThat(Disposition.of(new Instruction.SetAlarm(CallId.of("c"), Instant.EPOCH)))
        .isEqualTo(Disposition.TRANSACTIONAL);
    assertThat(Disposition.of(new Instruction.CancelAlarm(CallId.of("c"))))
        .isEqualTo(Disposition.TRANSACTIONAL);
  }

  @Test
  @DisplayName("narration is never a row")
  void narration_is_fire_and_forget() {
    assertThat(Disposition.of(new Instruction.Narrate.TurnStarted(TurnId.of("t"))))
        .isEqualTo(Disposition.NARRATION);
    assertThat(
            Disposition.of(
                new Instruction.Narrate.TurnEnded(new TurnResult.Completed(), Usage.unreported())))
        .isEqualTo(Disposition.NARRATION);
    assertThat(Disposition.of(new Instruction.Narrate.ToolCallCompleted(CallId.of("c"))))
        .isEqualTo(Disposition.NARRATION);
  }
}
