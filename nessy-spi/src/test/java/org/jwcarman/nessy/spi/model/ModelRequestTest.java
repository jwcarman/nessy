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
package org.jwcarman.nessy.spi.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.tool.Tool;

/**
 * The invariants of one call's worth of instructions to a provider.
 *
 * <p>{@code tools} and {@code requested} are typed as the interfaces a caller would naturally reach
 * for ({@code List}, {@code Set}), so the compact constructor's defensive copy is the only thing
 * standing between a caller's mutable collection and this supposedly-immutable record.
 */
@DisplayName("Everything a provider needs for one call")
class ModelRequestTest {

  private static ModelRequest request(
      Context context,
      String systemPrompt,
      int maxTokens,
      List<Tool<?>> tools,
      Set<Capability> requested) {
    return new ModelRequest(context, systemPrompt, maxTokens, tools, requested);
  }

  @Test
  void a_null_context_is_refused() {
    assertThatThrownBy(() -> request(null, "you are helpful", 100, List.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void a_null_system_prompt_is_refused() {
    assertThatThrownBy(() -> request(Context.empty(), null, 100, List.of(), Set.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  @DisplayName("a non-positive maxTokens is refused")
  void a_zero_max_tokens_is_refused() {
    assertThatThrownBy(() -> request(Context.empty(), "you are helpful", 0, List.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTokens");
  }

  @Test
  void a_negative_max_tokens_is_refused() {
    assertThatThrownBy(() -> request(Context.empty(), "you are helpful", -1, List.of(), Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxTokens");
  }

  @Test
  @DisplayName("the smallest legal maxTokens, 1, is accepted")
  void a_max_tokens_of_one_is_accepted() {
    ModelRequest request = request(Context.empty(), "you are helpful", 1, List.of(), Set.of());

    assertThat(request.maxTokens()).isEqualTo(1);
  }

  @Test
  @DisplayName("tools is copied, so mutating the caller's list does not reach the request")
  void tools_is_defensively_copied() {
    List<Tool<?>> mutable = new ArrayList<>();
    ModelRequest request = request(Context.empty(), "you are helpful", 100, mutable, Set.of());

    mutable.clear();

    assertThat(request.tools()).isEmpty();
    assertThatThrownBy(() -> request.tools().add(null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("requested is copied, so mutating the caller's set does not reach the request")
  void requested_is_defensively_copied() {
    Set<Capability> mutable = new HashSet<>(Set.of(Capability.THINKING));
    ModelRequest request = request(Context.empty(), "you are helpful", 100, List.of(), mutable);

    mutable.clear();

    assertThat(request.requested()).containsExactly(Capability.THINKING);
    assertThatThrownBy(() -> request.requested().add(Capability.IMAGE_INPUT))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
