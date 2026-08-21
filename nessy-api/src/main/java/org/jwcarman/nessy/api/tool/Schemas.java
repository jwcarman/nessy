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

import com.fasterxml.jackson.databind.JsonNode;
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
 * Turns a record — or a sealed interface of records — into the JSON Schema a model needs to call
 * it.
 *
 * <p>The record is the single source of truth. Its components become the schema's properties and
 * {@code @JsonPropertyDescription} becomes the text the model reads. Nobody hand-writes JSON
 * Schema, so it cannot drift from the code. A sealed interface's schema is a {@code oneOf} over its
 * permitted records, each gaining a required const {@code "type"} discriminator (see {@link
 * SealedInputs}).
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
   *
   * <p>Per JSON Schema 2020-12 §8.1.1, {@code "$schema"} is a root-only keyword — victools stamps
   * it onto every schema it generates, so each branch has it stripped before joining the {@code
   * oneOf}, and the composed root carries the one legitimate {@code "$schema"}.
   */
  private static ObjectNode sealedInterfaceSchema(Class<?> sealedType) {
    ArrayNode oneOf = JsonNodeFactory.instance.arrayNode();
    for (Class<?> permitted : sealedType.getPermittedSubclasses()) {
      ObjectNode branch =
          withTypeDiscriminator(GENERATOR.generateSchema(permitted), permitted.getSimpleName());
      branch.remove("$schema");
      oneOf.add(branch);
    }
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put("$schema", SchemaVersion.DRAFT_2020_12.getIdentifier());
    schema.set("oneOf", oneOf);
    return schema;
  }

  /**
   * Injects the const {@code "type"} discriminator into one permitted record's schema. A record
   * that declares its own {@code type} component collides with the discriminator and fails loudly
   * at schema-generation time rather than being silently overwritten (and later silently stripped
   * to {@code null} by {@link SealedInputs#bind}).
   */
  private static ObjectNode withTypeDiscriminator(ObjectNode recordSchema, String typeName) {
    ObjectNode properties = (ObjectNode) recordSchema.get("properties");
    if (properties == null) {
      properties = JsonNodeFactory.instance.objectNode();
      recordSchema.set("properties", properties);
    }
    if (properties.has("type")) {
      throw new IllegalArgumentException(
          "vocabulary record "
              + typeName
              + " declares a component named \"type\", which collides with the discriminator");
    }
    properties.set("type", JsonNodeFactory.instance.objectNode().put("const", typeName));

    ArrayNode required =
        recordSchema.has("required")
            ? (ArrayNode) recordSchema.get("required")
            : JsonNodeFactory.instance.arrayNode();
    if (!containsText(required, "type")) {
      required.add("type");
    }
    recordSchema.set("required", required);
    return recordSchema;
  }

  private static boolean containsText(ArrayNode array, String text) {
    for (JsonNode element : array) {
      if (element.asText().equals(text)) {
        return true;
      }
    }
    return false;
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
