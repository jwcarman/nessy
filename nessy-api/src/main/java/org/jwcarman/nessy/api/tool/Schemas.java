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
 * binding {@code RegistryToolCallExecutor} performs agree by construction via those annotations,
 * since both read the same ones — this class inspects and requires nothing beyond their presence,
 * so a caller's own mapper-level configuration (a mix-in, a custom introspector) is visible to
 * binding but invisible here, since this class builds its own generator rather than the caller's
 * mapper. A sealed interface missing the annotations themselves is rejected with a message telling
 * the caller what to add — the one check this class makes.
 */
public final class Schemas {

  private static final String ANY_OF = "anyOf";
  private static final String ONE_OF = "oneOf";
  private static final String DEFS = "$defs";
  private static final String SCHEMA_KEYWORD = "$schema";

  private static final String PROPERTIES = "properties";

  private static final String TYPE = "type";

  private static final String OBJECT = "object";

  private static final SchemaGenerator GENERATOR = generator();

  private Schemas() {}

  public static ObjectNode of(Class<?> inputType) {
    if (inputType.isInterface() && inputType.isSealed()) {
      return sealedInterfaceSchema(inputType);
    }
    return withProperties(normalizeAnyOfToOneOf(GENERATOR.generateSchema(inputType)));
  }

  /**
   * Gives an object schema an empty {@code properties} when it has none.
   *
   * <p>A record with no components generates {@code {"type":"object"}}, which is valid JSON Schema
   * and is REJECTED on the wire: the OpenAI function-calling shape requires {@code
   * parameters.properties} to be present, and a request carrying a tool without it fails with
   * {@code invalid_type ... path: function.parameters.properties}. Measured against LM Studio,
   * 2026-08-31.
   *
   * <p>A no-argument tool is an ordinary thing to want — "what time is it", "list the containers" —
   * so this belongs here rather than in every tool that happens to take nothing.
   */
  private static ObjectNode withProperties(ObjectNode schema) {
    if (OBJECT.equals(schema.path(TYPE).asText()) && !schema.has(PROPERTIES)) {
      schema.putObject(PROPERTIES);
    }
    return schema;
  }

  /**
   * Victools always names its polymorphic combinator {@code anyOf}; {@code oneOf} is the tighter,
   * correct keyword for discriminator-tagged mutually exclusive branches (the convention this class
   * uses throughout), so every schema {@link #of} returns is normalized to it — not only the
   * dedicated {@link #sealedInterfaceSchema} path. An annotated sealed <em>abstract class</em>
   * input type does not hit that path at all (it is gated on {@code isInterface()}, since that gate
   * exists to require polymorphism annotations up front for sealed interfaces specifically); it
   * carries its own {@code @JsonTypeInfo}/{@code @JsonSubTypes} regardless, so it still reaches
   * victools' Jackson module here and still gets an unrenamed {@code anyOf} back. Left un-renamed,
   * {@code AnthropicSchemas.toInputSchema} (which only copies a top-level {@code oneOf} key) would
   * silently drop every branch, handing the model an empty schema. A schema with no combinator at
   * all — the overwhelmingly common case, a plain record or any other non-polymorphic type —
   * round-trips through this method unchanged.
   */
  private static ObjectNode normalizeAnyOfToOneOf(ObjectNode schema) {
    JsonNode anyOf = schema.remove(ANY_OF);
    if (anyOf != null) {
      schema.set(ONE_OF, anyOf);
    }
    return schema;
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
    schema.set(ONE_OF, branches);
    return schema;
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
