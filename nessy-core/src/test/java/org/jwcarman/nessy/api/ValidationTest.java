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
package org.jwcarman.nessy.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolSpec;
import org.jwcarman.nessy.spi.Reducer;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelSettings;

/**
 * Pins the validating constructors on the public records.
 *
 * <p>These guards are API: relaxing one after 1.0 is a behavior break, so they need tests that fail
 * when someone deletes them.
 */
class ValidationTest {

  @Test
  void aBlankSessionIdIsRejected() {
    assertThatThrownBy(() -> new SessionId("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankParkTokenIsRejected() {
    assertThatThrownBy(() -> new ParkToken(" ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aNullTerminationPolicyIsRejected() {
    assertThatThrownBy(() -> new Reducer(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void aToolCallWithoutAnIdIsRejected() {
    assertThatThrownBy(() -> new ToolCall(null, "echo", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aToolCallWithoutANameIsRejected() {
    assertThatThrownBy(() -> new ToolCall("c1", " ", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aToolResultBlockWithoutContentIsRejected() {
    assertThatThrownBy(() -> new ToolResultBlock("c1", null, false))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aToolSpecWithoutANameIsRejected() {
    assertThatThrownBy(() -> new ToolSpec("", "does things", JsonNodeFactory.instance.objectNode()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aModelSettingsWithoutAModelIsRejected() {
    assertThatThrownBy(() -> new ModelSettings(null, "", 1024, Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aModelSettingsWithoutTokensToSpendIsRejected() {
    assertThatThrownBy(() -> new ModelSettings("fake-model", "", 0, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aModelRequestWithoutAModelIsRejected() {
    assertThatThrownBy(() -> new ModelRequest(List.of(), "system", " ", 1024, List.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
