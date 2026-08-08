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
package org.jwcarman.nessy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageTest {

  @Test
  void userMessageWrapsTextInABlock() {
    Message message = Message.user("hello");

    assertThat(message.role()).isEqualTo(Role.USER);
    assertThat(message.content()).containsExactly(new TextBlock("hello"));
  }

  @Test
  void toolResultsAreCarriedOnAUserMessage() {
    ToolResultBlock block = new ToolResultBlock("call_1", "contents", false);

    Message message = Message.toolResults(List.of(block));

    assertThat(message.role()).isEqualTo(Role.USER);
    assertThat(message.content()).containsExactly(block);
  }

  @Test
  void contentIsDefensivelyCopied() {
    List<ContentBlock> mutable = new ArrayList<>();
    mutable.add(new TextBlock("first"));

    Message message = new Message(Role.ASSISTANT, mutable);
    mutable.add(new TextBlock("sneaked in"));

    assertThat(message.content()).hasSize(1);
  }

  @Test
  void contentIsUnmodifiable() {
    Message message = Message.user("hello");

    assertThatThrownBy(() -> message.content().add(new TextBlock("nope")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void toolResultFactoriesSetTheErrorFlag() {
    assertThat(ToolResult.ok("fine").isError()).isFalse();
    assertThat(ToolResult.error("boom").isError()).isTrue();
    assertThat(ToolResult.error("boom").content()).isEqualTo("boom");
  }

  @Test
  void randomSessionIdsAreDistinct() {
    assertThat(SessionId.random()).isNotEqualTo(SessionId.random());
  }
}
