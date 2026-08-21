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
package org.jwcarman.nessy.api.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.util.Optional;

/**
 * Turns a record into the JSON Schema a model needs to call it.
 *
 * <p>The record is the single source of truth. Its components become the schema's properties and
 * {@code @JsonPropertyDescription} becomes the text the model reads. Nobody hand-writes JSON
 * Schema, so it cannot drift from the code.
 */
public final class Schemas {

  private static final SchemaGenerator GENERATOR = generator();

  private Schemas() {}

  public static ObjectNode of(Class<?> inputType) {
    if (inputType.isInterface() && inputType.isSealed()) {
      return sealedInterfaceSchema(inputType);
    }
    return GENERATOR.generateSchema(inputType);
  }

  /**
   * A sealed interface's schema is a {@code oneOf} over its permitted records, each carrying a
   * required const discriminator property {@code "type"} holding the record's simple name — the
   * shape {@link SealedInputs#bind} reads back. One level of sealing is the contract; a nested
   * sealed member is left to victools' default handling.
   */
  private static ObjectNode sealedInterfaceSchema(Class<?> sealedType) {
    ArrayNode oneOf = JsonNodeFactory.instance.arrayNode();
    for (Class<?> permitted : sealedType.getPermittedSubclasses()) {
      oneOf.add(
          withTypeDiscriminator(GENERATOR.generateSchema(permitted), permitted.getSimpleName()));
    }
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.set("oneOf", oneOf);
    return schema;
  }

  private static ObjectNode withTypeDiscriminator(ObjectNode recordSchema, String typeName) {
    ObjectNode properties = (ObjectNode) recordSchema.get("properties");
    if (properties == null) {
      properties = JsonNodeFactory.instance.objectNode();
      recordSchema.set("properties", properties);
    }
    properties.set("type", JsonNodeFactory.instance.objectNode().put("const", typeName));

    ArrayNode required =
        recordSchema.has("required")
            ? (ArrayNode) recordSchema.get("required")
            : JsonNodeFactory.instance.arrayNode();
    required.add("type");
    recordSchema.set("required", required);
    return recordSchema;
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
