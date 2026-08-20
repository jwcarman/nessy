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
package org.jwcarman.nessy.agent.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.AgentEvent;
import org.jwcarman.nessy.agent.ModelOutcome;

class LatentSinkTest {

  private static final AgentEvent EVENT =
      new AgentEvent.ModelFinished(new ModelOutcome.Failed("late"));

  @Test
  void deliveringBeforeBindingIsAProgrammingError() {
    var sink = new LatentSink();
    assertThatThrownBy(() -> sink.deliver(EVENT)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aBoundSinkForwardsEveryDelivery() {
    var sink = new LatentSink();
    List<AgentEvent> seen = new ArrayList<>();
    sink.bind(seen::add);
    sink.deliver(EVENT);
    assertThat(seen).containsExactly(EVENT);
  }

  @Test
  void bindingTwiceIsAProgrammingError() {
    var sink = new LatentSink();
    sink.bind(event -> {});
    Sink second = event -> {};
    assertThatThrownBy(() -> sink.bind(second)).isInstanceOf(IllegalStateException.class);
  }
}
