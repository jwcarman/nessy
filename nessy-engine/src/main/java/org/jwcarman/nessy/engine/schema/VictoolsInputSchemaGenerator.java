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
package org.jwcarman.nessy.engine.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2;
import com.github.victools.jsonschema.generator.FieldScope;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaKeyword;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.tool.InputSchemaGenerator;
import org.jwcarman.nessy.inference.tool.InputSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates input schemas with victools, from the input type alone.
 *
 * <p>The record is the single source of truth. Its components become the schema's properties and
 * {@code @JsonPropertyDescription} becomes the text the model reads. Nobody hand-writes JSON
 * Schema, so it cannot drift from the code.
 *
 * <p>A sealed interface's schema is a {@code oneOf} over its permitted records, derived from the
 * type's own standard Jackson {@code @JsonTypeInfo}/{@code @JsonSubTypes} annotations by victools'
 * Jackson module. A sealed interface missing those annotations is rejected with a message telling
 * the caller what to add -- the one check this class makes, and the only thing here that treats a
 * sealed interface differently from any other input type. Victools does the rest for both.
 *
 * <p><b>Only the Jackson module is registered.</b> It is the one that earns its place: descriptions
 * and the polymorphism the sealed path depends on. Anything else -- constraints from {@code
 * jakarta.validation}, descriptions from Swagger's {@code @Schema}, {@code
 * Option.INLINE_ALL_SCHEMAS} for a provider that will not follow a {@code $ref} -- is an
 * application's choice, made through the customizer, and arrives with that module's own
 * dependencies.
 *
 * <p>That victools is what does this is deliberately not visible from {@link InputSchemaGenerator}:
 * a tool is handed something that generates, and never learns how -- nor which Jackson it generated
 * with, which is the point of returning {@link InputSchema} rather than a node.
 */
public final class VictoolsInputSchemaGenerator implements InputSchemaGenerator {

  private final SchemaGeneratorConfig config;
  private final SchemaGenerator generator;

  /** Jackson descriptions and polymorphism, and nothing else. */
  public VictoolsInputSchemaGenerator() {
    this(Customizer.withDefaults());
  }

  /**
   * @param customizer applied <em>after</em> this class has had its say, so an application can
   *     register further modules and can override anything set here -- including the required
   *     check, which a module of its own may want to decide instead
   */
  public VictoolsInputSchemaGenerator(Customizer<SchemaGeneratorConfigBuilder> customizer) {
    SchemaGeneratorConfigBuilder builder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(new JacksonSchemaModule());
    builder.forFields().withRequiredCheck(VictoolsInputSchemaGenerator::isRequired);
    builder.forTypesInGeneral().withCustomDefinitionProvider(new JsonValueAsItsOwnType());
    customizer.customize(builder);
    this.config = builder.build();
    this.generator = new SchemaGenerator(this.config);
  }

  /**
   * A type that serialises as one value is described as that value.
   *
   * <p>Victools builds a schema by walking a type's components, and {@code @JsonValue} is not among
   * the things its Jackson module looks at for anything but enums. So a value type -- money that
   * writes itself as {@code 42.00}, an identifier that writes itself as a string -- is advertised
   * to a model as the object it is made of, and the model obligingly sends that object back.
   * Jackson then refuses it, because {@code @JsonValue} said the wire form was a scalar.
   *
   * <p>The tool call fails with a deserialisation message, which the model may or may not recover
   * from, and which is nobody's fault but the schema's: it described the Java type rather than what
   * the application will actually accept. Measured on a real model, handed a money type -- it sent
   * {@code {"amount": {"minorUnits": 99900, "currency": {...}}}}, exactly as instructed.
   *
   * <p>So a type carrying {@code @JsonValue} is described here as whatever that method returns.
   * Enums are left alone: victools' Jackson module already handles those, behind its own option,
   * and taking them here would change behaviour nobody asked to change.
   */
  private static final class JsonValueAsItsOwnType implements CustomDefinitionProviderV2 {

    @Override
    public CustomDefinition provideCustomSchemaDefinition(
        ResolvedType type, SchemaGenerationContext context) {
      Class<?> erased = type.getErasedType();
      if (erased.isEnum()) {
        return null;
      }
      return jsonValueOf(erased)
          .map(
              method ->
                  new CustomDefinition(
                      context.createDefinition(
                          context.getTypeContext().resolve(method.getGenericReturnType()))))
          .orElse(null);
    }

    /** The no-argument method a type says is its wire form, if it has one. */
    private static Optional<Method> jsonValueOf(Class<?> type) {
      return Arrays.stream(type.getMethods())
          .filter(method -> method.getParameterCount() == 0)
          .filter(method -> method.isAnnotationPresent(JsonValue.class))
          .filter(method -> method.getAnnotation(JsonValue.class).value())
          .findFirst();
    }
  }

  /**
   * Victools works in trees; the API hands out text. Serialising here is what keeps this class's
   * Jackson -- and its version of it -- from reaching any adapter.
   */
  @Override
  public InputSchema generate(Class<?> inputType) {
    return new InputSchema(generateNode(inputType).toString());
  }

  /** The generated schema as a tree, for callers inside this package that want one. */
  ObjectNode generateNode(Class<?> inputType) {
    if (inputType.isInterface() && inputType.isSealed()) {
      requireJacksonPolymorphismAnnotations(inputType);
    }
    return withProperties(normalizeAnyOfToOneOf(generator.generateSchema(inputType)));
  }

  /**
   * Keyword spellings are the version's, not ours -- {@code $defs} is {@code definitions} before
   * 2019-09, and a customizer is free to change {@link SchemaVersion}. Asking the built config is
   * victools' own idiom for post-processing a schema it generated.
   */
  private String keyword(SchemaKeyword tag) {
    return config.getKeyword(tag);
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
   * <p>A no-argument tool is an ordinary thing to want -- "what time is it", "list the containers"
   * -- so this belongs here rather than in every tool that happens to take nothing.
   */
  private ObjectNode withProperties(ObjectNode schema) {
    String properties = keyword(SchemaKeyword.TAG_PROPERTIES);
    if (keyword(SchemaKeyword.TAG_TYPE_OBJECT)
            .equals(schema.path(keyword(SchemaKeyword.TAG_TYPE)).asString())
        && !schema.has(properties)) {
      schema.putObject(properties);
    }
    return schema;
  }

  /**
   * Renames victools' polymorphic combinator from {@code anyOf} to {@code oneOf}, everywhere it
   * appears.
   *
   * <p>Victools always emits {@code anyOf}, and offers no option to change that. {@code oneOf} is
   * the tighter, correct keyword for branches tagged by a {@code const} discriminator: exactly one
   * can match, and saying so is what lets a validator reject an ambiguous argument object rather
   * than accept it. The two select identically here, so this buys strictness, not behaviour.
   *
   * <p><b>Every level, not just the root.</b> A recursive vocabulary -- one whose branches hold
   * more of the same vocabulary -- carries a combinator inside {@code $defs} as well as at the top.
   * Renaming only the root would leave one document speaking both keywords, which is worse than
   * speaking either consistently.
   *
   * <p>Renaming in place rather than rebuilding is what keeps {@code $defs} and every other key
   * victools attached: a {@code $ref} resolves against the true document root, so a rebuild that
   * forgot to carry one would leave a dangling reference.
   */
  private ObjectNode normalizeAnyOfToOneOf(ObjectNode schema) {
    renameCombinators(schema);
    return schema;
  }

  private void renameCombinators(JsonNode node) {
    if (node instanceof ObjectNode object) {
      JsonNode anyOf = object.remove(keyword(SchemaKeyword.TAG_ANYOF));
      if (anyOf != null) {
        object.set(keyword(SchemaKeyword.TAG_ONEOF), anyOf);
      }
      object.properties().forEach(entry -> renameCombinators(entry.getValue()));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        renameCombinators(node.get(i));
      }
    }
  }

  private static void requireJacksonPolymorphismAnnotations(Class<?> sealedType) {
    if (!sealedType.isAnnotationPresent(JsonTypeInfo.class)
        || !sealedType.isAnnotationPresent(JsonSubTypes.class)) {
      throw new IllegalArgumentException(
          "sealed interface "
              + sealedType.getSimpleName()
              + " is used as a tool input but carries no Jackson polymorphism"
              + " annotations; add @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property"
              + " = \"type\") and @JsonSubTypes naming each permitted record (e.g."
              + " @JsonSubTypes.Type(value = Restart.class, name = \"Restart\")) so"
              + " both the schema and the binder can read the same vocabulary");
    }
  }

  /** Everything a record declares is required unless it is an {@link Optional}. */
  private static boolean isRequired(FieldScope field) {
    return !Optional.class.isAssignableFrom(field.getType().getErasedType());
  }
}
