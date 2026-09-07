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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.Effect;

/**
 * Which effects become rows, and which never do.
 *
 * <p>Two answers, and each mistake has its own shape. A durable one treated as narration is work
 * silently lost to a crash. A narration treated as durable is a row per token.
 */
@DisplayName("What an effect is made of")
class DispositionTest {

  @Test
  @DisplayName("external work is durable")
  void work_that_leaves_the_process_is_durable() {
    assertThat(Disposition.of(new Effect.CallModel())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.RunTool(CallId.of("c"), "look")))
        .isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.AskApprover(CallId.of("c"), "look")))
        .isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.TakeWork())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.Remember.Exchange())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.Release())).isEqualTo(Disposition.DURABLE);
    assertThat(Disposition.of(new Effect.Forget())).isEqualTo(Disposition.DURABLE);
  }

  @Test
  @DisplayName("narration is never a row")
  void narration_is_fire_and_forget() {
    assertThat(Disposition.of(new Effect.Narrate.TurnStarted(TurnId.of("t"))))
        .isEqualTo(Disposition.NARRATION);
    assertThat(
            Disposition.of(
                new Effect.Narrate.TurnEnded(new TurnResult.Completed(), Usage.unreported())))
        .isEqualTo(Disposition.NARRATION);
    assertThat(Disposition.of(new Effect.Narrate.ToolCallCompleted(CallId.of("c"))))
        .isEqualTo(Disposition.NARRATION);
  }
}
