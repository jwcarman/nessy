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
package org.jwcarman.nessy.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.core.Awaited;
import org.jwcarman.nessy.core.Decision;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.ToolCall;

class ApproverTest {

  private final ApprovalRequest request =
      new ApprovalRequest(
          new SessionId("s1"),
          new ToolCall("c1", "delete_everything", JsonNodeFactory.instance.objectNode()),
          "delete_everything()");

  @Test
  void approveEverythingAllows() {
    assertThat(new ApproveEverything().approve(request)).isEqualTo(Awaited.ready(Decision.allow()));
  }

  @Test
  void denyEverythingDeniesWithItsReason() {
    Awaited<Decision> awaited = new DenyEverything("read-only mode").approve(request);

    assertThat(awaited).isEqualTo(Awaited.ready(new Decision.Deny("read-only mode")));
  }
}
