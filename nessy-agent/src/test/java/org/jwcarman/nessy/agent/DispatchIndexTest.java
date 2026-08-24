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
package org.jwcarman.nessy.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.agent.DispatchEntry.DispatchKind;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;
import org.jwcarman.nessy.spi.substrate.Substrate;

class DispatchIndexTest {

  private final Substrate substrate = new InMemorySubstrate();
  private final DispatchIndex index =
      new DispatchIndex(substrate, new ObjectMapper(), "dispatch/assistant");

  private final CallAddress address = new CallAddress("assistant", "scope-1", "resp-7", "call-3");

  @Test
  void anUnknownCallHasNoEntry() {
    assertThat(index.find(address)).isEmpty();
  }

  @Test
  void aRecordedCallIsFoundAgain() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    assertThat(index.find(address)).contains(new DispatchEntry("comp-1", DispatchKind.APPROVAL));
  }

  @Test
  void recordingAgainReplacesTheEntry() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));
    index.record(address, new DispatchEntry("comp-2", DispatchKind.TOOL));

    assertThat(index.find(address)).contains(new DispatchEntry("comp-2", DispatchKind.TOOL));
  }

  @Test
  void aDifferentCallHasItsOwnEntry() {
    var other = new CallAddress("assistant", "scope-1", "resp-7", "call-4");
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    assertThat(index.find(other)).isEmpty();
  }

  @Test
  void theDeleteOpRemovesTheEntry() {
    index.record(address, new DispatchEntry("comp-1", DispatchKind.APPROVAL));

    index.deleteOp(address).ifPresent(op -> substrate.batch(List.of(op)));

    assertThat(index.find(address)).isEmpty();
  }

  @Test
  void theDeleteOpForAnAbsentEntryIsHarmless() {
    index.deleteOp(address).ifPresent(op -> substrate.batch(List.of(op)));

    assertThat(index.find(address)).isEmpty();
  }
}
