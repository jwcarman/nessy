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
package org.jwcarman.nessy.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContinuationDispatcherTest {

  private final ContinuationDispatcher dispatcher = new ContinuationDispatcher();

  private record Delivery(Continuation continuation, Outcome outcome) {}

  @Test
  void aRegisteredHandlerReceivesEachMatchingContinuationInOrder() {
    List<Delivery> seen = new ArrayList<>();
    dispatcher.register("A", (c, o) -> seen.add(new Delivery(c, o)));
    var first = new Continuation("A", "one");
    var second = new Continuation("A", "two");
    var outcome = new Outcome.Success("v");
    dispatcher.fire(List.of(first, second), outcome);
    assertThat(seen).containsExactly(new Delivery(first, outcome), new Delivery(second, outcome));
  }

  @Test
  void registeringADuplicateTypeIsAProgrammingError() {
    dispatcher.register("A", (c, o) -> {});
    ContinuationHandler second = (c, o) -> {};
    assertThatThrownBy(() -> dispatcher.register("A", second))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void firingAnUnregisteredTypeFailsLoudlyBeforeAnySideEffect() {
    List<Delivery> seen = new ArrayList<>();
    dispatcher.register("A", (c, o) -> seen.add(new Delivery(c, o)));
    var known = new Continuation("A", "one");
    var unknown = new Continuation("GHOST", "boo");
    var continuations = List.of(known, unknown);
    var outcome = new Outcome.Success("v");
    assertThatThrownBy(() -> dispatcher.fire(continuations, outcome))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GHOST");
    assertThat(seen).isEmpty();
  }

  @Test
  void anEmptyListFiresNothing() {
    dispatcher.fire(List.of(), new Outcome.Success("v"));
  }
}
