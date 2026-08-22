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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
 * Schema, so it cannot drift from the code.
 *
 * <p>A sealed interface's schema is a {@code oneOf} over its permitted records, derived from the
 * type's own standard Jackson {@code @JsonTypeInfo}/{@code @JsonSubTypes} annotations via victools'
 * Jackson module (substrate spec §7, the 2026-08-22 repeal): the schema shown to the model and the
 * binding {@code RegistryToolCallExecutor} performs agree by construction, because both read the
 * same annotations. A sealed interface missing those annotations is rejected with a message telling
 * the caller what to add.
 */
public final class Schemas {

  private static final String ANY_OF = "anyOf";
  private static final String ONE_OF = "oneOf";
  private static final String ALL_OF = "allOf";
  private static final String DEFS = "$defs";
  private static final String SCHEMA_KEYWORD = "$schema";
  private static final String TYPE_CONST_POINTER = "/properties/type/const";

  private static final SchemaGenerator GENERATOR = generator();

  private Schemas() {}

  public static ObjectNode of(Class<?> inputType) {
    if (inputType.isInterface() && inputType.isSealed()) {
      return sealedInterfaceSchema(inputType);
    }
    return GENERATOR.generateSchema(inputType);
  }

  /**
   * Requires {@code @JsonTypeInfo}/{@code @JsonSubTypes} up front (a clearer failure than whatever
   * victools would otherwise produce for a bare, unannotated sealed interface), then lets the
   * generator's Jackson module do the actual derivation. Victools renders the polymorphic
   * combinator as {@code anyOf}; {@code oneOf} is the tighter, correct keyword for
   * discriminator-tagged mutually exclusive branches, so it is renamed here. A sealed interface
   * with exactly one permitted record collapses to a single flat schema (no combinator at all);
   * that case is wrapped into a one-branch {@code oneOf} for a uniform shape.
   *
   * <p>Two branches that share a nested record type get one shared {@code $defs} entry and a {@code
   * $ref} into it, sitting at the document root alongside {@code anyOf} — dropping it while
   * rebuilding the composed root would hand the model a dangling {@code $ref} with nothing to
   * resolve against, so every root-level key victools attached (at minimum {@code $defs}) is
   * carried onto the new root. The single-permit fallback path can carry its own nested {@code
   * $defs} too (victools attaches it to whatever object it generates the schema into); that has to
   * be lifted out of the wrapped branch and up to the new root before wrapping, since a {@code
   * $ref} is always resolved against the true document root, never against whichever branch object
   * happens to hold it.
   */
  private static ObjectNode sealedInterfaceSchema(Class<?> sealedType) {
    requireJacksonPolymorphismAnnotations(sealedType);
    ObjectNode generated = GENERATOR.generateSchema(sealedType);
    JsonNode combinator = generated.remove(ANY_OF);
    ObjectNode schema = JsonNodeFactory.instance.objectNode();
    schema.put(SCHEMA_KEYWORD, SchemaVersion.DRAFT_2020_12.getIdentifier());
    ArrayNode branches;
    if (combinator instanceof ArrayNode existing) {
      branches = existing;
      generated.remove(SCHEMA_KEYWORD);
      schema.setAll(generated);
    } else {
      generated.remove(SCHEMA_KEYWORD);
      JsonNode defs = generated.remove(DEFS);
      if (defs != null) {
        schema.set(DEFS, defs);
      }
      branches = JsonNodeFactory.instance.arrayNode().add(generated);
    }
    for (JsonNode branch : branches) {
      requireNoTypeCollision(branch, sealedType);
    }
    schema.set(ONE_OF, branches);
    return schema;
  }

  /**
   * A permitted record that declares its own {@code type} component collides with the {@code
   * "type"} discriminator {@code @JsonTypeInfo(property = "type")} injects. Jackson does not reject
   * this itself — it silently writes a duplicate {@code "type"} key on encode and drops the
   * discriminator's value on decode (verified empirically; not a documented contract) — so this is
   * rejected here, at schema-generation time, before the shape is ever shown to a model. Victools
   * signals the collision by falling back to an {@code allOf} of two conflicting property
   * definitions instead of merging them into one {@code properties} object; that {@code allOf}
   * shape is the detection signal.
   */
  private static void requireNoTypeCollision(JsonNode branch, Class<?> sealedType) {
    JsonNode allOf = branch.get(ALL_OF);
    if (allOf == null) {
      return;
    }
    String offender = null;
    for (JsonNode part : allOf) {
      JsonNode constNode = part.at(TYPE_CONST_POINTER);
      if (!constNode.isMissingNode()) {
        offender = constNode.asText();
      }
    }
    throw new IllegalArgumentException(
        "vocabulary record "
            + (offender == null ? "<unknown>" : offender)
            + " of sealed vocabulary "
            + sealedType.getSimpleName()
            + " declares a component named \"type\", which collides with the discriminator");
  }

  private static void requireJacksonPolymorphismAnnotations(Class<?> sealedType) {
    if (!sealedType.isAnnotationPresent(JsonTypeInfo.class)
        || !sealedType.isAnnotationPresent(JsonSubTypes.class)) {
      throw new IllegalArgumentException(
          "sealed interface "
              + sealedType.getSimpleName()
              + " is used as a tool input but carries no Jackson polymorphism annotations; add"
              + " @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = \"type\") and @JsonSubTypes"
              + " naming each permitted record (e.g. @JsonSubTypes.Type(value = Restart.class,"
              + " name = \"Restart\")) so both the schema and the binder can read the same"
              + " vocabulary");
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
