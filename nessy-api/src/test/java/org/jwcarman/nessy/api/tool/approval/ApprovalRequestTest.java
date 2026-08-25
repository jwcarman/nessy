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
package org.jwcarman.nessy.api.tool.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.authorization.Key;

class ApprovalRequestTest {

  private static final Key<String> NOTE = new Key<>(String.class, "test.note");
  private final ObjectMapper mapper = new ObjectMapper();
  private final ToolCall call =
      new ToolCall("c1", "restart", JsonNodeFactory.instance.objectNode().put("target", "eu"));

  @Test
  void aDraftFreezesIntoTheQuestionWithItsActionAndFacts() {
    ApprovalRequest request =
        ApprovalRequest.draft("ops", "prod-eu", call, mapper)
            .action("restart eu")
            .deposit(NOTE, "approved last week")
            .freeze();

    assertThat(request.agentType()).isEqualTo("ops");
    assertThat(request.agentId()).isEqualTo("prod-eu");
    assertThat(request.call()).isEqualTo(call);
    assertThat(request.action()).isEqualTo("restart eu");
    assertThat(request.facts().get(NOTE)).contains("approved last week");
  }

  @Test
  void anUnsetActionFreezesAsTheEmptyString() {
    ApprovalRequest request = ApprovalRequest.draft("ops", "prod-eu", call, mapper).freeze();

    assertThat(request.action()).isEmpty();
  }

  @Test
  void theRequestIsAJsonDocumentThatRoundTripsByteForByte() {
    ApprovalRequest original =
        ApprovalRequest.draft("ops", "prod-eu", call, mapper)
            .action("restart eu")
            .deposit(NOTE, "n")
            .freeze();
    var codec = ApprovalRequest.codec(mapper);

    byte[] bytes = codec.encode(original);
    ApprovalRequest decoded = codec.decode(bytes);

    assertThat(decoded).isEqualTo(original);
    assertThat(codec.encode(decoded)).isEqualTo(bytes);
    assertThat(decoded.facts().get(NOTE)).contains("n"); // the codec attaches the mapper
  }

  @Test
  void aDraftIsSingleUse() {
    ApprovalRequest.Draft draft = ApprovalRequest.draft("ops", "prod-eu", call, mapper);
    draft.freeze();

    assertThatThrownBy(draft::freeze).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theBuiltInKeysNameConcreteTypes() {
    assertThat(ApprovalRequest.PRINCIPAL.type()).isEqualTo(String.class);
    assertThat(ApprovalRequest.RISK.name()).isEqualTo("risk");
  }
}
