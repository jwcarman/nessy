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
package org.jwcarman.nessy.spi.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The mapper-binding boundary every stored-JSON codec goes through: reads are tolerant, and every
 * Jackson failure is translated into an {@link IllegalArgumentException} naming the offense rather
 * than leaking a Jackson type past this class.
 */
@DisplayName("The mapper-binding boundary")
class CodecsTest {

  /** A bean Jackson can describe but never successfully serialize. */
  public static final class Broken {
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }

  private final Codecs codecs = new Codecs(new ObjectMapper());

  @Nested
  class Constructing {

    @Test
    void a_null_mapper_is_refused() {
      assertThatThrownBy(() -> new Codecs(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void copy_and_pin_refuses_a_null_mapper() {
      assertThatThrownBy(() -> Codecs.copyAndPin(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  class ParsingToATree {

    @Test
    void valid_json_parses_to_a_tree() {
      JsonNode tree = codecs.readTree("{\"a\":1}", "widget");

      assertThat(tree.get("a").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("malformed JSON is refused with a message naming the owner")
    void malformed_json_is_refused() {
      assertThatThrownBy(() -> codecs.readTree("not json", "widget"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("widget");
    }
  }

  @Nested
  class BindingATree {

    @Test
    void a_matching_tree_binds_to_the_type() {
      JsonNode tree = codecs.readTree("{\"value\":\"hi\"}", "widget");

      Holder bound = codecs.bind(tree, Holder.class, "widget");

      assertThat(bound.value()).isEqualTo("hi");
    }

    @Test
    @DisplayName("a tree that cannot bind is refused with a message naming the owner")
    void a_non_binding_tree_is_refused() {
      JsonNode tree = codecs.readTree("{\"value\":[1,2,3]}", "widget");

      assertThatThrownBy(() -> codecs.bind(tree, Holder.class, "widget"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("widget");
    }

    record Holder(String value) {}
  }

  @Nested
  class RenderingToATree {

    @Test
    @DisplayName("a value renders to a tree, not a serialized string")
    void a_value_renders_to_a_json_node() {
      JsonNode tree = codecs.toTree(new BindingATree.Holder("hi"));

      assertThat(tree.get("value").asText()).isEqualTo("hi");
    }
  }

  @Nested
  class WritingJson {

    @Test
    void a_value_writes_to_json() {
      String json = codecs.write(new BindingATree.Holder("hi"));

      assertThat(json).contains("\"value\"", "hi");
    }

    @Test
    @DisplayName("an encoding failure is refused with a message naming the value's type")
    void an_encoding_failure_is_refused() {
      Broken broken = new Broken();

      assertThatThrownBy(() -> codecs.write(broken))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("Broken");
    }
  }

  @Nested
  class RequiringAnArrayIfPresent {

    @Test
    void an_absent_field_is_allowed() {
      JsonNode root = JsonNodeFactory.instance.objectNode();

      Codecs.requireArrayIfPresent(root, "items", "widget");
    }

    @Test
    void an_array_field_is_allowed() {
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.putArray("items");

      Codecs.requireArrayIfPresent(root, "items", "widget");
    }

    @Test
    @DisplayName("a present, non-array field is refused and named")
    void a_scalar_field_is_refused() {
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.put("items", "not an array");

      assertThatThrownBy(() -> Codecs.requireArrayIfPresent(root, "items", "widget"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("items");
    }
  }

  @Nested
  class RequiringAnArray {

    @Test
    void an_array_field_is_allowed() {
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.putArray("items");

      Codecs.requireArray(root, "items", "widget");
    }

    @Test
    @DisplayName("a missing field is refused and named")
    void a_missing_field_is_refused() {
      JsonNode root = JsonNodeFactory.instance.objectNode();

      assertThatThrownBy(() -> Codecs.requireArray(root, "items", "widget"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("items");
    }

    @Test
    @DisplayName("a present, non-array field is refused and named")
    void a_scalar_field_is_refused() {
      ObjectNode root = JsonNodeFactory.instance.objectNode();
      root.put("items", 42);

      assertThatThrownBy(() -> Codecs.requireArray(root, "items", "widget"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("items");
    }
  }
}
