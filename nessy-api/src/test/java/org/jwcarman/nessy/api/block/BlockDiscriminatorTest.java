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
package org.jwcarman.nessy.api.block;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.UserMessage;

/**
 * Why the markers carry no discriminator of their own.
 *
 * <p>{@link Block} declares the wire names once. A field typed with a MARKER — and every message
 * holds one — resolves through the root by inheritance, so repeating the names on each marker would
 * be four places to spell {@code text} and four chances to spell it differently.
 *
 * <p>These tests are what makes that safe to rely on: if Jackson ever stopped inheriting, a stored
 * transcript would become unreadable, and this would say so first.
 */
@DisplayName("Block wire names")
class BlockDiscriminatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  @DisplayName("a marker-typed field resolves through the root's names")
  void a_block_round_trips_when_the_field_is_typed_by_a_marker() throws Exception {
    UserMessage written = UserMessage.of("hi");

    String json = MAPPER.writeValueAsString(written);
    UserMessage read = MAPPER.readValue(json, UserMessage.class);

    assertThat(json).contains("\"type\":\"text\"");
    assertThat(read.content()).isNotEmpty();
    assertThat(read.content().getFirst()).isInstanceOf(TextBlock.class);
    assertThat(read).isEqualTo(written);
  }

  @Test
  void a_block_round_trips_through_the_root_too() throws Exception {
    Block written = new ImageBlock("image/png", "AAAA");

    Block read = MAPPER.readValue(MAPPER.writeValueAsString(written), Block.class);

    assertThat(read).isEqualTo(written);
  }

  @Test
  void the_root_names_every_concrete_block() {
    JsonSubTypes declared = Block.class.getAnnotation(JsonSubTypes.class);
    assertThat(declared).isNotNull();

    List<Class<?>> named =
        Arrays.stream(declared.value()).map(JsonSubTypes.Type::value).collect(Collectors.toList());

    assertThat(named)
        .containsExactlyInAnyOrder(
            TextBlock.class,
            ImageBlock.class,
            CommentaryBlock.class,
            ProviderBlock.class,
            ToolCallBlock.class,
            ToolResultBlock.class);
  }

  @Test
  void the_markers_declare_no_names_of_their_own() {
    List<Class<?>> markers =
        List.of(
            UserContentBlock.class,
            AnswerContentBlock.class,
            ExchangeContentBlock.class,
            AmbientContentBlock.class,
            ToolResultContentBlock.class);

    assertThat(markers).isNotEmpty();
    assertThat(markers)
        .allSatisfy(
            marker ->
                assertThat(marker.getAnnotation(JsonSubTypes.class))
                    .as("%s should inherit Block's names, not repeat them", marker.getSimpleName())
                    .isNull());
  }

  @Test
  void a_message_names_its_role_rather_than_its_type() throws Exception {
    String json = MAPPER.writeValueAsString((ContextMessage) UserMessage.of("hi"));

    assertThat(json).contains("\"role\":\"user\"");
  }
}
