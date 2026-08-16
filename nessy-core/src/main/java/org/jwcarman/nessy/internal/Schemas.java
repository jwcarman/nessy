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
package org.jwcarman.nessy.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Turns a record into the JSON Schema a model needs to call it.
 *
 * <p>The record is the single source of truth. Its components become the schema's properties and
 * {@code @JsonPropertyDescription} becomes the text the model reads. Nobody hand-writes JSON
 * Schema, so it cannot drift from the code.
 *
 * <p>victools 5 generates schemas as Jackson 3 ({@code tools.jackson.databind}) nodes, but the rest
 * of nessy's wire types ({@link org.jwcarman.nessy.api.tool.ToolSpec} and every provider module)
 * are pinned to Jackson 2 ({@code com.fasterxml.jackson.databind}). Rather than push Jackson 3
 * across that whole surface, the generated tree is round-tripped through its JSON text into a
 * Jackson 2 {@link ObjectNode} here, at the one place the two Jackson generations meet.
 */
public final class Schemas {

  private static final ObjectMapper JACKSON2 = new ObjectMapper();
  private static final SchemaGenerator GENERATOR = generator();

  private Schemas() {}

  public static ObjectNode of(Class<?> inputType) {
    JsonNode generated = GENERATOR.generateSchema(inputType);
    return toJackson2(generated);
  }

  private static ObjectNode toJackson2(JsonNode generated) {
    try {
      return (ObjectNode) JACKSON2.readTree(generated.toString());
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("victools produced schema JSON Jackson 2 could not parse", e);
    }
  }

  private static SchemaGenerator generator() {
    SchemaGeneratorConfigBuilder config =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(new JacksonModule());
    config.forFields().withRequiredCheck(Schemas::isRequired);
    return new SchemaGenerator(config.build());
  }

  /** Everything a record declares is required unless it is an {@link Optional}. */
  private static boolean isRequired(FieldScope field) {
    return !Optional.class.isAssignableFrom(field.getType().getErasedType());
  }
}
