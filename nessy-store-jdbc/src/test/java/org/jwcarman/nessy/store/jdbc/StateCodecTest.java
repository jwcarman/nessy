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
package org.jwcarman.nessy.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Decision;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.ToolResolution;
import org.jwcarman.nessy.api.conversation.AgendaItem;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationState;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.ImageBlock;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.RedactedThinkingBlock;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.message.ThinkingBlock;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.message.ToolUseBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The wire shape of the two durable jsonb payloads: {@link ConversationState} and {@link
 * AgendaItem}. Every round trip goes through a mapper the codec copies rather than mutates, so a
 * caller's own {@link ObjectMapper} is never left carrying these mixins after handing it in.
 */
class StateCodecTest {

  private final StateCodec codec = new StateCodec(new ObjectMapper());

  private static ToolCall toolCall(String id) {
    return new ToolCall(id, "echo", JsonNodeFactory.instance.objectNode().put("text", "hi"));
  }

  private AgendaItem.Told told(ContentBlock block) {
    return AgendaItem.told(List.of(block));
  }

  @Nested
  class Every_content_block_variant {

    @Test
    void a_text_block_round_trips() {
      AgendaItem.Told entry = told(new TextBlock("hello"));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_thinking_block_round_trips() {
      AgendaItem.Told entry = told(new ThinkingBlock("reasoning", "sig-1"));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_redacted_thinking_block_round_trips() {
      AgendaItem.Told entry = told(new RedactedThinkingBlock("opaque-bytes"));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_tool_use_block_round_trips() {
      AgendaItem.Told entry = told(new ToolUseBlock(toolCall("c1")));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_tool_result_block_round_trips() {
      AgendaItem.Told entry = told(new ToolResultBlock("c1", "42", false));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void an_image_block_round_trips() {
      AgendaItem.Told entry = told(new ImageBlock("image/png", "YmFzZTY0"));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }
  }

  @Nested
  class A_full_state_with_debt_parks_and_told {

    @Test
    void round_trips() {
      ConversationId id = ConversationId.generate();
      ParkedCall parked = new ParkedCall(ParkToken.generate(), toolCall("c2"));
      ConversationState state =
          new ConversationState(
              id,
              List.of(toolCall("c1")),
              List.of(new ToolResultBlock("c0", "done", false)),
              2,
              5,
              new Usage(100, 200, 10),
              "model refused to continue (REFUSAL)",
              List.of(
                  List.of(new TextBlock("hi"), new ThinkingBlock("thinking", "sig")),
                  List.of(new ImageBlock("image/png", "YmFzZTY0"))),
              List.of(parked),
              7L,
              ConversationStatus.FAILED);

      ConversationState decoded = codec.readState(codec.writeState(state));

      assertThat(decoded).isEqualTo(state);
    }
  }

  @Nested
  class Agenda_entries {

    @Test
    void a_told_entry_round_trips() {
      AgendaItem.Told entry = AgendaItem.told(List.of(new TextBlock("interjected")));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_resolved_entry_carrying_an_allow_decision_round_trips() {
      AgendaItem.Resolved entry =
          AgendaItem.resolved(ParkToken.generate(), new ToolResolution.Decided(Decision.allow()));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_resolved_entry_carrying_a_deny_decision_round_trips() {
      AgendaItem.Resolved entry =
          AgendaItem.resolved(
              ParkToken.generate(), new ToolResolution.Decided(new Decision.Deny("not today")));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }

    @Test
    void a_resolved_entry_carrying_a_completed_tool_result_round_trips() {
      AgendaItem.Resolved entry =
          AgendaItem.resolved(
              ParkToken.generate(), new ToolResolution.Completed(ToolResult.ok("42")));

      AgendaItem decoded = codec.readAgendaItem(codec.writeAgendaItem(entry));

      assertThat(decoded).isEqualTo(entry);
    }
  }

  @Nested
  class A_message {

    @Test
    void a_message_round_trips_through_the_codec() {
      Message message =
          Message.assistant(
              List.of(
                  new ThinkingBlock("hmm", "sig"),
                  new TextBlock("hi"),
                  new ToolUseBlock(toolCall("c1"))));

      Message decoded = codec.readMessage(codec.writeMessage(message));

      assertThat(decoded).isEqualTo(message);
    }

    @Test
    void an_unknown_message_payload_fails_loudly() {
      String payload = "{\"role\":\"USER\",\"content\":[{\"type\":\"bogus\"}]}";

      assertThatThrownBy(() -> codec.readMessage(payload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(InvalidTypeIdException.class);
    }
  }

  @Nested
  class Unknown_json {

    @Test
    void fails_loudly_not_null_for_a_content_block() {
      String payload =
          "{\"type\":\"told\",\"id\":\"e1\",\"content\":[{\"type\":\"bogus\",\"text\":\"hi\"}]}";

      assertThatThrownBy(() -> codec.readAgendaItem(payload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void fails_loudly_not_null_for_an_agenda_entry() {
      String payload = "{\"type\":\"bogus\",\"id\":\"e1\"}";

      assertThatThrownBy(() -> codec.readAgendaItem(payload))
          .isInstanceOf(IllegalArgumentException.class)
          .hasCauseInstanceOf(InvalidTypeIdException.class);
    }
  }

  /**
   * §7's sealing-has-teeth promise, held against the codec: a new permitted subclass added to one
   * of these sealed types compiles fine and only explodes at first runtime serialization if the
   * mixin's {@code @JsonSubTypes} forgets to list it. These four tests pin that the mixin's
   * registered set is always exactly the sealed type's own {@link Class#getPermittedSubclasses()} —
   * so a drift between the two fails loudly here, in a fast offline test, rather than in production
   * JSON.
   */
  @Nested
  class Sealed_grammar_coverage {

    @Test
    void the_codec_registers_every_permitted_content_block() {
      assertRegistersEveryPermittedSubclass(ContentBlock.class, "ContentBlockMixin");
    }

    @Test
    void the_codec_registers_every_permitted_agenda_entry() {
      assertRegistersEveryPermittedSubclass(AgendaItem.class, "AgendaItemMixin");
    }

    @Test
    void the_codec_registers_every_permitted_tool_resolution() {
      assertRegistersEveryPermittedSubclass(ToolResolution.class, "ToolResolutionMixin");
    }

    @Test
    void the_codec_registers_every_permitted_decision() {
      assertRegistersEveryPermittedSubclass(Decision.class, "DecisionMixin");
    }

    private void assertRegistersEveryPermittedSubclass(
        Class<?> sealedType, String mixinSimpleName) {
      Class<?>[] permitted = sealedType.getPermittedSubclasses();
      assertThat(permitted).isNotEmpty();

      JsonSubTypes subTypes = mixinNamed(mixinSimpleName).getAnnotation(JsonSubTypes.class);
      assertThat(subTypes).isNotNull();
      Set<Class<?>> registered =
          Arrays.stream(subTypes.value()).map(JsonSubTypes.Type::value).collect(Collectors.toSet());
      assertThat(registered).isNotEmpty();

      assertThat(registered).containsExactlyInAnyOrder(permitted);
    }

    private Class<?> mixinNamed(String simpleName) {
      return Arrays.stream(StateCodec.class.getDeclaredClasses())
          .filter(candidate -> candidate.getSimpleName().equals(simpleName))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  void the_callers_mapper_is_never_mutated() throws Exception {
    ObjectMapper callersMapper = new ObjectMapper();

    new StateCodec(callersMapper);
    String directlySerialized = callersMapper.writeValueAsString(new TextBlock("hi"));

    // The codec's own mapper would tag this with a "type" discriminator; the caller's mapper,
    // never mutated, still writes the plain record shape it always did.
    assertThat(directlySerialized).doesNotContain("\"type\"");
  }
}
