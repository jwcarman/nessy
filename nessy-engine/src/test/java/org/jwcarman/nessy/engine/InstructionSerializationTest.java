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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.TurnResult;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.engine.agent.Instruction;

/**
 * An instruction is a row now, so it has to come back the same instruction it went in as.
 *
 * <p>{@code @JsonSubTypes} compiles whether or not it can actually deserialize -- a typo'd class
 * reference or a missing no-args-friendly shape is a wiring mistake Jackson only reports at
 * runtime, on the first row that hits it. This is that check, run once per arm rather than left for
 * Task 6 to discover against a live effect row.
 */
@DisplayName("An instruction round-trips through the wire")
class InstructionSerializationTest {

  static Stream<Instruction> instructions() {
    return Stream.of(
        new Instruction.TakeWork(),
        new Instruction.CallModel(),
        new Instruction.AskApprover(CallId.of("c1"), "look_up"),
        new Instruction.RunTool(CallId.of("c1"), "look_up"),
        new Instruction.Remember.Input(),
        new Instruction.Remember.Answer(),
        new Instruction.Remember.Exchange(),
        new Instruction.Release(),
        new Instruction.SetAlarm(CallId.of("c1"), Instant.EPOCH),
        new Instruction.CancelAlarm(CallId.of("c1")),
        new Instruction.Forget(),
        new Instruction.Narrate.TurnStarted(TurnId.of("t1")),
        new Instruction.Narrate.TurnEnded(new TurnResult.Completed(), Usage.unreported()),
        new Instruction.Narrate.ApprovalDecided(CallId.of("c1"), new ApprovalResult.Approved()),
        new Instruction.Narrate.ToolCallCompleted(CallId.of("c1")));
  }

  @ParameterizedTest
  @MethodSource("instructions")
  @DisplayName("comes back equal to itself, as its own concrete type")
  void round_trips_through_the_configured_mapper(Instruction instruction) throws Exception {
    String written = EngineMapper.INSTANCE.writeValueAsString(instruction);

    Instruction read = EngineMapper.INSTANCE.readValue(written, Instruction.class);

    assertThat(read).isEqualTo(instruction);
  }

  @ParameterizedTest
  @MethodSource("instructions")
  @DisplayName("names its concrete type on the wire, not the sealed interface")
  void carries_a_discriminator(Instruction instruction) throws Exception {
    String written = EngineMapper.INSTANCE.writeValueAsString(instruction);

    assertThat(written).contains("\"do\"");
  }

  @Test
  @DisplayName("every arm of the sealed grammar is covered by the fixture above")
  void the_fixture_is_not_missing_an_arm() {
    List<Instruction> fixture = instructions().toList();

    assertThat(fixture).isNotEmpty();
    assertThat(fixture).extracting(Object::getClass).doesNotHaveDuplicates().hasSize(15);
  }
}
