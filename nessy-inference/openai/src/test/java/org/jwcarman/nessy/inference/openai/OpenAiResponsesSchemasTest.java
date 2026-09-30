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
package org.jwcarman.nessy.inference.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The strict-mode projection of a tool's schema.
 *
 * <p>The fixtures are written by hand in the shape {@code VictoolsJsonSchemaGenerator} emits --
 * copied from its real output on 2026-09-29 -- because the generator lives in {@code nessy-engine},
 * which already depends on this module, so it cannot be a test dependency here without a reactor
 * cycle. {@code VictoolsJsonSchemaGeneratorTest} pins the generator's side: an {@code Optional}
 * component left out of {@code required}, a sealed vocabulary as a {@code oneOf} with a {@code
 * const} discriminator, shared records under {@code $defs}.
 */
class OpenAiResponsesSchemasTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private static Map<String, Object> parse(String json) {
    return MAPPER.readValue(json, new TypeReference<>() {});
  }

  private static OpenAiResponsesSchemas.Projected project(String json) {
    return OpenAiResponsesSchemas.project(json, MAPPER);
  }

  /** {@code record Lookup(String q, Optional<String> reason, int limit)}, as generated. */
  private static final String LOOKUP =
      """
      {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
       "properties":{"limit":{"type":"integer"},"q":{"type":"string"},
                     "reason":{"type":["string","null"]}},
       "required":["limit","q"]}""";

  @Nested
  class ARewrittenSchema {

    @Test
    void lists_every_property_as_required_and_forbids_any_other() {
      OpenAiResponsesSchemas.Projected projected = project(LOOKUP);

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                   "properties":{"limit":{"type":"integer"},"q":{"type":"string"},
                                 "reason":{"type":["string","null"]}},
                   "required":["limit","q","reason"],
                   "additionalProperties":false}"""));
    }

    /** The generator already widened it; widening again would write {@code "null"} twice. */
    @Test
    void leaves_an_optional_that_already_admits_null_as_it_was() {
      Object reason = ((Map<?, ?>) project(LOOKUP).schema().get("properties")).get("reason");

      assertThat(reason).isEqualTo(parse("{\"type\":[\"string\",\"null\"]}"));
    }

    @Test
    void widens_a_plain_optional_type_to_admit_null() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object","properties":{"q":{"type":"string"},"note":{"type":"string"}},
               "required":["q"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"q":{"type":"string"},"note":{"type":["string","null"]}},
                   "required":["q","note"],"additionalProperties":false}"""));
    }

    @Test
    void wraps_an_optional_reference_in_an_any_of_with_null() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                  "required":["host"]}},
               "type":"object",
               "properties":{"first":{"$ref":"#/$defs/Nested"},
                             "second":{"$ref":"#/$defs/Nested"}},
               "required":["first"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                      "required":["host"],"additionalProperties":false}},
                   "type":"object",
                   "properties":{"first":{"$ref":"#/$defs/Nested"},
                                 "second":{"anyOf":[{"$ref":"#/$defs/Nested"},{"type":"null"}]}},
                   "required":["first","second"],"additionalProperties":false}"""));
    }

    /** Review Focus 1: strict mode checks {@code enum} as well as {@code type}. */
    @Test
    void an_optional_enum_admits_null_among_its_values() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"colour":{"type":["string","null"],"enum":["RED","GREEN"]}},
               "required":[]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"colour":{"type":["string","null"],
                                           "enum":["RED","GREEN",null]}},
                   "required":["colour"],"additionalProperties":false}"""));
    }

    @Test
    void reaches_objects_nested_in_items_and_in_any_of_branches() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"hosts":{"type":"array","items":{"type":"object",
                  "properties":{"name":{"type":"string"}},"required":["name"]}},
                             "either":{"anyOf":[{"type":"object","properties":{},"required":[]},
                                                {"type":"string"}]}},
               "required":["hosts","either"]}""");

      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"type":"object",
                   "properties":{"hosts":{"type":"array","items":{"type":"object",
                      "properties":{"name":{"type":"string"}},"required":["name"],
                      "additionalProperties":false}},
                                 "either":{"anyOf":[{"type":"object","properties":{},
                                                     "required":[],"additionalProperties":false},
                                                    {"type":"string"}]}},
                   "required":["hosts","either"],"additionalProperties":false}"""));
    }

    /** {@code record Empty()}, as generated. */
    @Test
    void an_empty_record_is_strict_with_nothing_required() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
               "properties":{}}""");

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                   "properties":{},"required":[],"additionalProperties":false}"""));
    }
  }

  @Nested
  class AOneOfTheGeneratorWrote {

    /**
     * {@code record Holder(Nested first, Optional<Nested> second, List<String> tags)}, as
     * generated: an optional record is a {@code oneOf} with null. The branches are mutually
     * exclusive, so {@code anyOf} says the same thing, and it already admits null.
     */
    @Test
    void an_optional_record_becomes_an_any_of_and_stays_strict() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"$schema":"https://json-schema.org/draft/2020-12/schema",
               "$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                  "required":["host"]}},
               "type":"object",
               "properties":{"first":{"$ref":"#/$defs/Nested"},
                             "second":{"oneOf":[{"type":"null"},{"$ref":"#/$defs/Nested"}]},
                             "tags":{"type":"array","items":{"type":"string"}}},
               "required":["first","tags"]}""");

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  """
                  {"$schema":"https://json-schema.org/draft/2020-12/schema",
                   "$defs":{"Nested":{"type":"object","properties":{"host":{"type":"string"}},
                                      "required":["host"],"additionalProperties":false}},
                   "type":"object",
                   "properties":{"first":{"$ref":"#/$defs/Nested"},
                                 "second":{"anyOf":[{"type":"null"},{"$ref":"#/$defs/Nested"}]},
                                 "tags":{"type":"array","items":{"type":"string"}}},
                   "required":["first","second","tags"],"additionalProperties":false}"""));
    }

    private static final String SEALED_BRANCHES =
        """
        [{"type":"object","properties":{"host":{"type":"string"},"type":{"const":"Restart"}},
          "required":["host","type"]},
         {"type":"object","properties":{"reason":{"type":["string","null"]},
                                        "type":{"const":"Shutdown"}},
          "required":["type"]}]""";

    /**
     * A sealed {@code Command} with {@code Restart(String host)} and {@code
     * Shutdown(Optional<String> reason)}, as generated, held in a property of a record.
     */
    @Test
    void a_nested_sealed_vocabulary_becomes_an_any_of_and_stays_strict() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              "{\"type\":\"object\",\"properties\":{\"command\":{\"oneOf\":"
                  + SEALED_BRANCHES
                  + "}},\"required\":[\"command\"]}");

      assertThat(projected.strict()).isTrue();
      assertThat(projected.schema())
          .isEqualTo(
              parse(
                  "{\"type\":\"object\",\"properties\":{\"command\":{\"anyOf\":"
                      + """
                      [{"type":"object","properties":{"host":{"type":"string"},
                                                      "type":{"const":"Restart"}},
                        "required":["host","type"],"additionalProperties":false},
                       {"type":"object","properties":{"reason":{"type":["string","null"]},
                                                      "type":{"const":"Shutdown"}},
                        "required":["reason","type"],"additionalProperties":false}]"""
                      + "}},\"required\":[\"command\"],\"additionalProperties\":false}"));
    }

    /** Strict mode requires the root of a tool schema to be an object, not a union. */
    @Test
    void a_union_at_the_root_goes_as_generated_naming_the_root() {
      String json = "{\"oneOf\":" + SEALED_BRANCHES + "}";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword())
          .hasValueSatisfying(k -> assertThat(k).contains("root"));
      assertThat(projected.schema()).isEqualTo(parse(json));
    }

    @Test
    void an_any_of_at_the_root_goes_as_generated_naming_the_root() {
      String json = "{\"anyOf\":" + SEALED_BRANCHES + "}";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword()).contains("anyOf at the root");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }

    @Test
    void a_bare_ref_at_the_root_goes_as_generated_naming_the_root() {
      String json =
          "{\"$ref\":\"#/$defs/Q\",\"$defs\":{\"Q\":{\"type\":\"object\","
              + "\"properties\":{\"a\":{\"type\":\"string\"}},\"required\":[\"a\"]}}}";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword()).contains("$ref at the root");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }

    @Test
    void a_scalar_root_goes_as_generated_naming_the_root() {
      String json = "{\"type\":\"string\"}";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword()).contains("type string at the root");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }
  }

  @Nested
  class ASchemaStrictModeCannotExpress {

    /**
     * Review Focus 2: {@code Map<String, String>} is an open object strict mode cannot describe.
     */
    @Test
    void an_open_map_s_additional_properties_is_refused() {
      String json =
          """
          {"type":"object",
           "properties":{"labels":{"type":"object","additionalProperties":{"type":"string"}}},
           "required":["labels"]}""";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.strict()).isFalse();
      assertThat(projected.refusedKeyword()).contains("additionalProperties");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }

    @Test
    void a_keyword_outside_the_subset_is_named_wherever_it_sits() {
      OpenAiResponsesSchemas.Projected projected =
          project(
              """
              {"type":"object",
               "properties":{"name":{"type":"string","minLength":1}},
               "required":["name"]}""");

      assertThat(projected.refusedKeyword()).contains("minLength");
    }

    @Test
    void an_unsupported_keyword_inside_a_one_of_branch_still_falls_back_as_generated() {
      String json =
          """
          {"type":"object","properties":{"either":{"oneOf":[{"type":"object",
             "properties":{"n":{"type":"string","minLength":1}},"required":["n"]},
             {"type":"null"}]}},"required":["either"]}""";

      OpenAiResponsesSchemas.Projected projected = project(json);

      assertThat(projected.refusedKeyword()).contains("minLength");
      assertThat(projected.schema()).isEqualTo(parse(json));
    }
  }
}
