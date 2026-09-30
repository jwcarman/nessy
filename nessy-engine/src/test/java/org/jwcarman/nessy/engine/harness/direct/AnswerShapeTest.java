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
package org.jwcarman.nessy.engine.harness.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.api.OutputReader;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AnswerShapeTest {

  record Item(String name) {}

  record Pair(Item first, Item second) {}

  enum Color {
    RED,
    GREEN
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Circle.class, name = "Circle"),
    @JsonSubTypes.Type(value = Square.class, name = "Square")
  })
  sealed interface Shape permits Circle, Square {}

  record Circle(int radius) implements Shape {}

  record Square(int side) implements Shape {}

  private final ObjectMapper mapper = JsonMapper.builder().build();
  private final VictoolsJsonSchemaGenerator schemas = new VictoolsJsonSchemaGenerator();

  private AnswerShape shapeOf(TypeRef<?> type) {
    return AnswerShape.of(type.getType(), schemas, mapper);
  }

  private <T> OutputReader<T> readerFor(AnswerShape shape, TypeRef<T> type) {
    return shape.reading(OutputReader.json(mapper, type));
  }

  private JsonNode schemaOf(AnswerShape shape) {
    return mapper.readTree(shape.schema().json());
  }

  @Nested
  class AnObjectAnswer {

    @Test
    void goes_as_generated_and_is_read_as_it_comes() {
      AnswerShape shape = shapeOf(new TypeRef<Item>() {});
      OutputReader<Item> reader = readerFor(shape, new TypeRef<Item>() {});

      assertThat(schemaOf(shape).path("type").asString()).isEqualTo("object");
      assertThat(schemaOf(shape).path("properties").has("name")).isTrue();
      assertThat(schemaOf(shape).path("properties").has("value")).isFalse();
      assertThat(reader.read("{\"name\":\"a\"}")).isEqualTo(new Item("a"));
    }
  }

  @Nested
  class AListAnswer {

    @Test
    void is_wrapped_as_an_object_whose_value_is_the_typed_array() {
      AnswerShape shape = shapeOf(new TypeRef<List<Item>>() {});
      JsonNode schema = schemaOf(shape);

      assertThat(schema.path("type").asString()).isEqualTo("object");
      assertThat(schema.path("required").toString()).isEqualTo("[\"value\"]");
      assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
      assertThat(schema.at("/properties/value/type").asString()).isEqualTo("array");
      assertThat(schema.at("/properties/value/items").toString()).contains("name");
    }

    @Test
    void reads_the_value_out_of_the_wrapper() {
      AnswerShape shape = shapeOf(new TypeRef<List<Item>>() {});
      OutputReader<List<Item>> reader = readerFor(shape, new TypeRef<List<Item>>() {});

      assertThat(reader.read("{\"value\":[{\"name\":\"a\"},{\"name\":\"b\"}]}"))
          .containsExactly(new Item("a"), new Item("b"));
    }

    @Test
    void refuses_an_answer_that_left_the_wrapper_off() {
      AnswerShape shape = shapeOf(new TypeRef<List<Item>>() {});
      OutputReader<List<Item>> reader = readerFor(shape, new TypeRef<List<Item>>() {});
      assertThatThrownBy(() -> reader.read("[{\"name\":\"a\"}]"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("value");
    }
  }

  @Nested
  class AStringAnswer {

    @Test
    void is_wrapped_and_unwrapped() {
      AnswerShape shape = shapeOf(new TypeRef<String>() {});
      OutputReader<String> reader = readerFor(shape, new TypeRef<String>() {});

      assertThat(schemaOf(shape).at("/properties/value/type").asString()).isEqualTo("string");
      assertThat(reader.read("{\"value\":\"hello\"}")).isEqualTo("hello");
    }
  }

  @Nested
  class AnEnumAnswer {

    @Test
    void is_wrapped_and_unwrapped() {
      AnswerShape shape = shapeOf(new TypeRef<Color>() {});
      OutputReader<Color> reader = readerFor(shape, new TypeRef<Color>() {});

      assertThat(schemaOf(shape).at("/properties/value/enum").toString())
          .contains("RED")
          .contains("GREEN");
      assertThat(reader.read("{\"value\":\"GREEN\"}")).isEqualTo(Color.GREEN);
    }
  }

  @Nested
  class ASealedAnswer {

    @Test
    void is_wrapped_with_the_union_under_value() {
      TypeRef<Shape> type = new TypeRef<Shape>() {};
      AnswerShape shape = shapeOf(type);
      OutputReader<Shape> reader = readerFor(shape, type);
      JsonNode schema = schemaOf(shape);

      assertThat(schema.path("type").asString()).isEqualTo("object");
      assertThat(schema.at("/properties/value/oneOf").isArray()).isTrue();
      assertThat(reader.read("{\"value\":{\"type\":\"Circle\",\"radius\":3}}"))
          .isEqualTo(new Circle(3));
    }
  }

  @Nested
  class DefinitionsUnderAWrapper {

    @Test
    void are_hoisted_to_the_wrapper_root_so_refs_still_resolve() {
      AnswerShape shape = shapeOf(new TypeRef<List<Pair>>() {});
      JsonNode schema = schemaOf(shape);

      assertThat(schema.path("type").asString()).isEqualTo("object");
      assertThat(schema.has("$defs")).isTrue();
      assertThat(schema.at("/properties/value").has("$defs")).isFalse();
      assertThat(schema.toString()).contains("#/$defs/");
      assertThat(schema.at("/properties/value/$schema").isMissingNode()).isTrue();
      assertThat(schema.at("/$defs/Item/properties/name").isMissingNode()).isFalse();
    }
  }

  @Nested
  class ACallersOwnReader {

    @Test
    void still_sees_the_unwrapped_value_when_the_answer_was_wrapped() {
      OutputReader<Integer> counting = text -> mapper.readTree(text).size();
      AnswerShape shape = shapeOf(new TypeRef<List<Item>>() {});
      OutputReader<Integer> reader = shape.reading(counting);

      assertThat(reader.read("{\"value\":[{\"name\":\"a\"},{\"name\":\"b\"}]}")).isEqualTo(2);
    }
  }
}
