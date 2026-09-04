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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.engine.agent.Input;

/**
 * The one-way door out of an effect.
 *
 * <p>Everything asynchronous the engine does leaves through here: a model answered, a tool
 * finished, a deadline passed. It carries the effect being discharged as well as what happened,
 * because recording an outcome and retiring the obligation that produced it must be one act -- and
 * an input that arrived from outside has no obligation behind it, which is what the null means.
 */
@DisplayName("The seam an effect answers through")
class DispatcherSeamTest {

  private record Sent(AgentId agentId, Input input, EffectId completing) {}

  @Test
  @DisplayName("a dispatcher receives what happened and which obligation it discharges")
  void a_dispatcher_receives_input_and_effect() {
    List<Sent> seen = new ArrayList<>();
    Dispatcher dispatcher =
        (agentId, input, completing, observability) ->
            seen.add(new Sent(agentId, input, completing));
    EffectId effect = EffectId.next();

    dispatcher.dispatch(AgentId.of("house-1"), new Input.NoWork(), effect, null);

    assertThat(seen).isNotEmpty();
    assertThat(seen).allMatch(sent -> effect.equals(sent.completing()));
    assertThat(seen).allMatch(sent -> sent.input() instanceof Input.NoWork);
  }

  @Test
  @DisplayName("an input from outside discharges nothing")
  void an_external_input_carries_no_effect() {
    List<Sent> seen = new ArrayList<>();
    Dispatcher dispatcher =
        (agentId, input, completing, observability) ->
            seen.add(new Sent(agentId, input, completing));

    dispatcher.dispatch(AgentId.of("house-1"), new Input.BacklogUpdated());

    assertThat(seen).isNotEmpty();
    assertThat(seen).allMatch(sent -> sent.completing() == null);
  }
}
