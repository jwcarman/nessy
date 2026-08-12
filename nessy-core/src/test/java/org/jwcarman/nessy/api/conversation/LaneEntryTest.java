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
package org.jwcarman.nessy.api.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;

class LaneEntryTest {

  @Test
  void told_entries_carry_content_and_a_time_ordered_id() {
    List<ContentBlock> content = List.of(new TextBlock("hello"));

    LaneEntry.Told first = LaneEntry.told(content);
    LaneEntry.Told second = LaneEntry.told(content);

    assertThat(first.id()).isNotNull();
    assertThat(second.id()).isNotNull();
    assertThat(first.id()).isNotEqualTo(second.id());
    assertThat(second.id().compareTo(first.id())).isGreaterThan(0);
  }

  @Test
  void resolved_entries_carry_their_token_and_resolution() {
    ParkToken token = ParkToken.generate();
    ToolResolution resolution = new ToolResolution.Decided(Decision.allow());

    LaneEntry.Resolved resolved = LaneEntry.resolved(token, resolution);

    assertThat(resolved.id()).isNotNull();
    assertThat(resolved.token()).isEqualTo(token);
    assertThat(resolved.resolution()).isEqualTo(resolution);
  }

  @Test
  void told_rejects_null_content() {
    assertThatThrownBy(() -> LaneEntry.told(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void resolved_rejects_null_token() {
    ToolResolution resolution = new ToolResolution.Decided(Decision.allow());

    assertThatThrownBy(() -> LaneEntry.resolved(null, resolution))
        .isInstanceOf(NullPointerException.class);
  }
}
