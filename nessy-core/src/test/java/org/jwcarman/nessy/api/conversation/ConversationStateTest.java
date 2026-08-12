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

import com.fasterxml.jackson.databind.node.NullNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;

class ConversationStateTest {

  private static final ConversationId ID = new ConversationId("s1");

  @Test
  void new_session_starts_empty_and_idle() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.id()).isEqualTo(ID);
    assertThat(state.pendingCalls()).isEmpty();
    assertThat(state.pendingResults()).isEmpty();
    assertThat(state.consecutiveErrors()).isZero();
    assertThat(state.modelCalls()).isZero();
    assertThat(state.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void with_model_calls_returns_a_new_instance() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed = original.withModelCalls(3);

    assertThat(changed.modelCalls()).isEqualTo(3);
    assertThat(original.modelCalls()).isZero();
  }

  @Test
  void withers_return_new_instances_and_leave_the_original_alone() {
    ConversationState original = ConversationState.newConversation(ID);

    ConversationState changed =
        original.with(ConversationStatus.AWAITING_MODEL).withConsecutiveErrors(2);

    assertThat(changed.status()).isEqualTo(ConversationStatus.AWAITING_MODEL);
    assertThat(changed.consecutiveErrors()).isEqualTo(2);

    assertThat(original.status()).isEqualTo(ConversationStatus.IDLE);
    assertThat(original.consecutiveErrors()).isZero();
  }

  @Test
  void all_lists_are_unmodifiable() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.pendingCalls()).isUnmodifiable();
    assertThat(state.pendingResults()).isUnmodifiable();
  }

  @Test
  void a_new_conversation_has_no_told_material_no_parks_and_version_zero() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.told()).isEmpty();
    assertThat(state.parkedCalls()).isEmpty();
    assertThat(state.version()).isZero();
  }

  @Test
  void with_told_replaces_only_the_told_lane() {
    List<ContentBlock> spoken = List.of(new TextBlock("hi"));
    ParkedCall parked = parkedCall();
    ConversationState seeded = seededState(parked).withVersion(3L);

    ConversationState toldChanged = seeded.withTold(List.of(spoken));

    assertThat(toldChanged.told()).isEqualTo(List.of(spoken));
    assertThat(toldChanged.parkedCalls()).isEqualTo(List.of(parked));
    assertThat(toldChanged.version()).isEqualTo(3L);
    assertThat(toldChanged.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void with_parked_calls_replaces_only_the_parked_lane() {
    List<ContentBlock> spoken = List.of(new TextBlock("hi"));
    ParkedCall replacement = parkedCall();
    ConversationState seeded = seededState(spoken).withVersion(3L);

    ConversationState parkedChanged = seeded.withParkedCalls(List.of(replacement));

    assertThat(parkedChanged.parkedCalls()).isEqualTo(List.of(replacement));
    assertThat(parkedChanged.told()).isEqualTo(List.of(spoken));
    assertThat(parkedChanged.version()).isEqualTo(3L);
    assertThat(parkedChanged.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void with_version_replaces_only_the_version() {
    List<ContentBlock> spoken = List.of(new TextBlock("hi"));
    ParkedCall parked = parkedCall();
    ConversationState seeded = seededState(spoken, parked).withVersion(3L);

    ConversationState versionChanged = seeded.withVersion(4L);

    assertThat(versionChanged.version()).isEqualTo(4L);
    assertThat(versionChanged.told()).isEqualTo(List.of(spoken));
    assertThat(versionChanged.parkedCalls()).isEqualTo(List.of(parked));
    assertThat(versionChanged.status()).isEqualTo(ConversationStatus.IDLE);
  }

  @Test
  void told_and_parked_lanes_are_unmodifiable() {
    ConversationState state = ConversationState.newConversation(ID);

    assertThat(state.told()).isUnmodifiable();
    assertThat(state.parkedCalls()).isUnmodifiable();
  }

  private static ParkedCall parkedCall() {
    return new ParkedCall(
        ParkToken.generate(), new ToolCall("call-1", "tool", NullNode.getInstance()));
  }

  private static ConversationState seededState(List<ContentBlock> spoken) {
    return ConversationState.newConversation(ID).withTold(List.of(spoken));
  }

  private static ConversationState seededState(ParkedCall parked) {
    return ConversationState.newConversation(ID).withParkedCalls(List.of(parked));
  }

  private static ConversationState seededState(List<ContentBlock> spoken, ParkedCall parked) {
    return ConversationState.newConversation(ID)
        .withTold(List.of(spoken))
        .withParkedCalls(List.of(parked));
  }
}
