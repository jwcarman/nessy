package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.engine.agent.AgentState;
import org.jwcarman.nessy.engine.agent.CallState;
import org.jwcarman.nessy.engine.agent.Phase;

/**
 * What an agent's state looks like coming back off disk.
 *
 * <p>The erasure this class used to exist for is gone: the observation type stopped at the backlog
 * store, so there is no {@code JavaType} to record and no registry to consult. What is left worth
 * testing is the sealed hierarchy — a phase and its call states are polymorphic, and Jackson gets
 * that right only because every arm is named on the wire.
 */
@DisplayName("An agent's state on disk")
class StateSerializerTest {

  private final StateSerializer serializer = new StateSerializer();

  @Test
  void an_idle_state_round_trips() {
    AgentState written = AgentState.idle();

    Object read = serializer.fromBinary(serializer.toBinary(written), serializer.manifest(written));

    assertThat(read).isEqualTo(written);
  }

  @Test
  @DisplayName("a phase comes back as its own arm, not as the interface")
  void a_turn_calling_the_model_round_trips() {
    AgentState written = AgentState.idle().taking("turn-1", "observation");

    AgentState read =
        (AgentState)
            serializer.fromBinary(serializer.toBinary(written), serializer.manifest(written));

    assertThat(read.phase()).isInstanceOf(Phase.CallingModel.class);
    assertThat(read.turnId()).isEqualTo("turn-1");
    assertThat(read.observation()).isEqualTo("observation");
  }

  @Test
  @DisplayName("every kind of waiting survives, which is what recovery reads to decide")
  void the_call_states_round_trip() {
    AgentState written =
        AgentState.idle()
            .taking("turn-1", "observation")
            .at(
                new Phase.WorkingTools(
                    Map.of(
                        "a", new CallState.Approving("send_email"),
                        "b", new CallState.Running("read_file"),
                        "c", new CallState.Parked(),
                        "d", new CallState.Completed())));

    AgentState read =
        (AgentState)
            serializer.fromBinary(serializer.toBinary(written), serializer.manifest(written));

    assertThat(read.working().calls())
        .containsEntry("a", new CallState.Approving("send_email"))
        .containsEntry("b", new CallState.Running("read_file"))
        .containsEntry("c", new CallState.Parked())
        .containsEntry("d", new CallState.Completed());
  }

  @Test
  void what_a_turn_has_cost_survives_a_restart() {
    AgentState written =
        AgentState.idle().taking("turn-1", "observation").spending(new Usage(10, 20, null, null));

    AgentState read =
        (AgentState)
            serializer.fromBinary(serializer.toBinary(written), serializer.manifest(written));

    assertThat(read.usage()).isEqualTo(new Usage(10, 20, null, null));
  }

  @Test
  void the_manifest_is_a_version_because_there_is_only_one_type_now() {
    assertThat(serializer.manifest(AgentState.idle())).isEqualTo("agent-state-v2");
  }

  @Test
  void state_written_by_an_engine_this_one_does_not_know_is_refused() {
    byte[] bytes = serializer.toBinary(AgentState.idle());

    assertThatThrownBy(() -> serializer.fromBinary(bytes, "agent-state-v1:watchman"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown state manifest");
  }
}
