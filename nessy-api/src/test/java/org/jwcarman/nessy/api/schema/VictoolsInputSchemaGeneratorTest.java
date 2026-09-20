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
package org.jwcarman.nessy.api.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.InputSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class VictoolsInputSchemaGeneratorTest {

  private final VictoolsInputSchemaGenerator generator = new VictoolsInputSchemaGenerator();

  // ---- fixtures ----

  record Nothing() {}

  record Dated(@JsonPropertyDescription("ISO-8601, e.g. 2026-12-25") String date) {}

  record ReadFile(
      @JsonPropertyDescription("Path relative to the workspace root") String path,
      Optional<Integer> maxLines) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
    @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
  })
  sealed interface Vocabulary permits Restart, Shutdown {}

  record Restart(@JsonPropertyDescription("Target host") String host) implements Vocabulary {}

  record Shutdown(Optional<String> reason) implements Vocabulary {}

  sealed interface UnannotatedVocabulary permits UnannotatedMember {}

  record UnannotatedMember(String value) implements UnannotatedVocabulary {}

  record NestedTarget(String name) {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({@JsonSubTypes.Type(value = OnlyMember.class, name = "OnlyMember")})
  sealed interface SinglePermitVocabulary permits OnlyMember {}

  /**
   * Its one branch references {@link NestedTarget} twice, which is what makes victools dedupe the
   * nested schema into its own {@code $defs} entry even with a single permitted record.
   */
  record OnlyMember(NestedTarget first, NestedTarget second) implements SinglePermitVocabulary {}

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = RestartTarget.class, name = "Restart"),
    @JsonSubTypes.Type(value = ShutdownTarget.class, name = "Shutdown")
  })
  sealed interface VocabularyWithSharedNestedRecord permits RestartTarget, ShutdownTarget {}

  record RestartTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  record ShutdownTarget(NestedTarget target) implements VocabularyWithSharedNestedRecord {}

  /**
   * A sealed ABSTRACT CLASS carrying the same polymorphism annotations. It never reaches the {@code
   * isInterface() && isSealed()} precondition, so it proves the {@code anyOf}-to-{@code oneOf}
   * normalization applies to every input type rather than only to sealed interfaces.
   */
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ClassRestart.class, name = "ClassRestart"),
    @JsonSubTypes.Type(value = ClassShutdown.class, name = "ClassShutdown")
  })
  abstract static sealed class ClassVocabulary permits ClassRestart, ClassShutdown {}

  static final class ClassRestart extends ClassVocabulary {
    private String host;

    public String getHost() {
      return host;
    }

    public void setHost(String host) {
      this.host = host;
    }
  }

  static final class ClassShutdown extends ClassVocabulary {
    private String reason;

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }

  // ---- helpers ----

  private static ObjectNode branchNamed(ObjectNode schema, String typeName) {
    JsonNode branches = schema.get("oneOf");
    for (int i = 0; i < branches.size(); i++) {
      JsonNode branch = branches.get(i);
      if (typeName.equals(branch.at("/properties/type/const").asString())) {
        return (ObjectNode) branch;
      }
    }
    throw new NoSuchElementException("no branch named " + typeName);
  }

  private static List<String> requiredNames(JsonNode branch) {
    JsonNode required = branch.get("required");
    assertThat(required).isNotNull();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < required.size(); i++) {
      names.add(required.get(i).asString());
    }
    return names;
  }

  private static void collectRefs(JsonNode node, List<String> refs) {
    if (node.isObject()) {
      JsonNode ref = node.get("$ref");
      if (ref != null) {
        refs.add(ref.asString());
      }
      node.properties().forEach(entry -> collectRefs(entry.getValue(), refs));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collectRefs(node.get(i), refs);
      }
    }
  }

  // ---- a tool that takes no arguments ----

  /**
   * Victools generates {@code {"type":"object"}} for a record with no components, which is valid
   * JSON Schema and is rejected on the wire: the OpenAI function-calling shape requires {@code
   * parameters.properties} to be present.
   */
  @Test
  void aNoArgumentInputTypeStillCarriesAPropertiesObject() {
    ObjectNode schema = generator.generateNode(Nothing.class);

    assertThat(schema.get("type").asString()).isEqualTo("object");
    assertThat(schema.has("properties")).isTrue();
    assertThat(schema.get("properties").isObject()).isTrue();
    assertThat(schema.get("properties").size()).isZero();
  }

  // ---- an ordinary input type ----

  @Test
  void componentsBecomeProperties() {
    ObjectNode schema = generator.generateNode(ReadFile.class);

    assertThat(schema.get("properties").has("path")).isTrue();
    assertThat(schema.get("properties").has("maxLines")).isTrue();
  }

  @Test
  void aComponentKeepsTheTypeItGenerated() {
    ObjectNode schema = generator.generateNode(Dated.class);

    assertThat(schema.get("properties").get("date").get("type").asString()).isEqualTo("string");
  }

  /** The one thing a generator cannot infer, and the part a model actually reads. */
  @Test
  void descriptionsSurviveIntoTheSchema() {
    ObjectNode schema = generator.generateNode(ReadFile.class);

    assertThat(schema.get("properties").get("path").get("description").asString())
        .isEqualTo("Path relative to the workspace root");
  }

  @Test
  void everythingIsRequiredExceptOptionals() {
    ObjectNode schema = generator.generateNode(ReadFile.class);

    assertThat(requiredNames(schema)).containsExactly("path");
  }

  @Test
  void theSchemaDescribesAnObject() {
    ObjectNode schema = generator.generateNode(ReadFile.class);

    assertThat(schema.get("type").asString()).isEqualTo("object");
  }

  // ---- a sealed interface input type ----

  @Nested
  class ASealedInterfaceInputType {

    @Test
    void theSchemaIsAOneOfWithOneBranchPerPermittedRecord() {
      ObjectNode schema = generator.generateNode(Vocabulary.class);

      assertThat(schema.has("anyOf")).isFalse();
      assertThat(schema.get("oneOf")).isNotNull();
      assertThat(schema.get("oneOf").size()).isEqualTo(2);
    }

    @Test
    void eachBranchCarriesARequiredConstDiscriminatorNamedType() {
      ObjectNode schema = generator.generateNode(Vocabulary.class);

      ObjectNode restart = branchNamed(schema, "Restart");
      ObjectNode shutdown = branchNamed(schema, "Shutdown");

      assertThat(restart.at("/properties/type/const").asString()).isEqualTo("Restart");
      assertThat(requiredNames(restart)).contains("type");
      assertThat(shutdown.at("/properties/type/const").asString()).isEqualTo("Shutdown");
      assertThat(requiredNames(shutdown)).contains("type");
    }

    @Test
    void eachBranchStillCarriesItsOwnRecordsProperties() {
      ObjectNode restart = branchNamed(generator.generateNode(Vocabulary.class), "Restart");

      assertThat(restart.get("properties").has("host")).isTrue();
      assertThat(requiredNames(restart)).contains("host");
    }

    @Test
    void anOptionalComponentIsNotRequiredOnItsBranch() {
      ObjectNode shutdown = branchNamed(generator.generateNode(Vocabulary.class), "Shutdown");

      assertThat(requiredNames(shutdown)).doesNotContain("reason");
    }

    @Test
    void theRootCarriesSchemaAndNoBranchDoes() {
      ObjectNode schema = generator.generateNode(Vocabulary.class);

      assertThat(schema.get("$schema").asString())
          .isEqualTo(SchemaVersion.DRAFT_2020_12.getIdentifier());
      JsonNode branches = schema.get("oneOf");
      for (int i = 0; i < branches.size(); i++) {
        assertThat(branches.get(i).has("$schema")).isFalse();
      }
    }

    @Test
    void anUnannotatedSealedInterfaceIsRejectedWithAMessageNamingWhatToAdd() {
      assertThatThrownBy(() -> generator.generateNode(UnannotatedVocabulary.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("UnannotatedVocabulary")
          .hasMessageContaining("@JsonTypeInfo")
          .hasMessageContaining("@JsonSubTypes");
    }

    @Test
    void twoBranchesSharingANestedRecordCarryOneSharedDefsEntryAtTheRoot() {
      ObjectNode schema = generator.generateNode(VocabularyWithSharedNestedRecord.class);

      assertThat(schema.has("$defs")).isTrue();
      assertThat(schema.get("$defs").has("NestedTarget")).isTrue();
      JsonNode branches = schema.get("oneOf");
      for (int i = 0; i < branches.size(); i++) {
        assertThat(branches.get(i).has("$defs")).isFalse();
      }
    }

    @Test
    void everyRefInASharedNestedRecordSchemaResolvesAgainstTheRoot() {
      ObjectNode schema = generator.generateNode(VocabularyWithSharedNestedRecord.class);

      List<String> refs = new ArrayList<>();
      collectRefs(schema, refs);

      assertThat(refs).isNotEmpty();
      for (String ref : refs) {
        assertThat(ref).startsWith("#/");
        assertThat(schema.at(ref.substring(1)).isMissingNode()).isFalse();
      }
    }

    /**
     * A single permitted record leaves victools nothing to choose between, so no combinator is
     * emitted and the schema is the branch itself. nessy hand-wrapped this into a one-branch {@code
     * oneOf}; that wrapping is gone, so this pins what victools actually produces.
     */
    @Test
    void aSinglePermittedRecordProducesAFlatSchemaWithNoCombinator() {
      ObjectNode schema = generator.generateNode(SinglePermitVocabulary.class);

      assertThat(schema.has("oneOf")).isFalse();
      assertThat(schema.has("anyOf")).isFalse();
      assertThat(schema.at("/properties/type/const").asString()).isEqualTo("OnlyMember");
      assertThat(schema.get("properties").has("first")).isTrue();
      assertThat(schema.get("properties").has("second")).isTrue();
    }

    /** Its {@code $defs} sit at the root because the root is the branch. */
    @Test
    void aSinglePermittedRecordsDefsSitAtTheRoot() {
      ObjectNode schema = generator.generateNode(SinglePermitVocabulary.class);

      assertThat(schema.has("$defs")).isTrue();
      assertThat(schema.get("$defs").has("NestedTarget")).isTrue();

      List<String> refs = new ArrayList<>();
      collectRefs(schema, refs);
      for (String ref : refs) {
        assertThat(schema.at(ref.substring(1)).isMissingNode()).isFalse();
      }
    }
  }

  // ---- a recursive vocabulary ----

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = Equals.class, name = "Equals"),
    @JsonSubTypes.Type(value = And.class, name = "And")
  })
  sealed interface Condition permits Equals, And {}

  record Equals(String field, String value) implements Condition {}

  record And(List<Condition> operands) implements Condition {}

  private static void collectKeys(JsonNode node, String key, List<String> found) {
    if (node.isObject()) {
      if (node.has(key)) {
        found.add(key);
      }
      node.properties().forEach(entry -> collectKeys(entry.getValue(), key, found));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        collectKeys(node.get(i), key, found);
      }
    }
  }

  @Nested
  class ARecursiveVocabulary {

    /**
     * Victools falls back to {@code $defs}/{@code $ref} on its own once a branch contains the
     * vocabulary again, so the combinator appears inside a definition as well as at the root.
     */
    @Test
    void carriesACombinatorInsideItsDefsAsWellAsAtTheRoot() {
      ObjectNode schema = generator.generateNode(Condition.class);

      assertThat(schema.has("$defs")).isTrue();
      List<String> nested = new ArrayList<>();
      collectKeys(schema.get("$defs"), "oneOf", nested);
      assertThat(nested).isNotEmpty();
    }

    /** One document speaking two keywords for the same thing is worse than either alone. */
    @Test
    void noAnyOfSurvivesAnywhereInTheDocument() {
      ObjectNode schema = generator.generateNode(Condition.class);

      List<String> strays = new ArrayList<>();
      collectKeys(schema, "anyOf", strays);
      assertThat(strays).isEmpty();
    }

    @Test
    void everyRefStillResolvesAgainstTheRoot() {
      ObjectNode schema = generator.generateNode(Condition.class);

      List<String> refs = new ArrayList<>();
      collectRefs(schema, refs);

      assertThat(refs).isNotEmpty();
      for (String ref : refs) {
        assertThat(schema.at(ref.substring(1)).isMissingNode()).isFalse();
      }
    }
  }

  // ---- a sealed abstract class input type ----

  @Nested
  class ASealedAbstractClassInputType {

    @Test
    void theSchemaIsAOneOfNotTheRawVictoolsAnyOf() {
      ObjectNode schema = generator.generateNode(ClassVocabulary.class);

      assertThat(schema.has("anyOf")).isFalse();
      assertThat(schema.get("oneOf")).isNotNull();
      assertThat(schema.get("oneOf").size()).isEqualTo(2);
    }

    @Test
    void eachBranchStillCarriesItsOwnDiscriminatorConst() {
      ObjectNode schema = generator.generateNode(ClassVocabulary.class);

      List<String> discriminators = new ArrayList<>();
      JsonNode branches = schema.get("oneOf");
      for (int i = 0; i < branches.size(); i++) {
        discriminators.add(branches.get(i).at("/properties/type/const").asString());
      }

      assertThat(discriminators).containsExactlyInAnyOrder("ClassRestart", "ClassShutdown");
    }
  }

  // ---- what the API actually hands out ----

  @Nested
  class TheSchemaItPublishes {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** Text, so no adapter inherits this class's Jackson -- or its major version. */
    @Test
    void isTextThatParsesBackToTheGeneratedTree() {
      InputSchema published = generator.generate(ReadFile.class);

      assertThat(mapper.readTree(published.json()))
          .isEqualTo(generator.generateNode(ReadFile.class));
    }

    @Test
    void survivesTheRoundTripForASealedVocabulary() {
      InputSchema published = generator.generate(Vocabulary.class);

      JsonNode parsed = mapper.readTree(published.json());

      assertThat(parsed.get("oneOf").size()).isEqualTo(2);
      assertThat(parsed.has("anyOf")).isFalse();
    }

    /** A no-argument tool still publishes the empty properties object the wire requires. */
    @Test
    void keepsTheEmptyPropertiesObjectForANoArgumentTool() {
      InputSchema published = generator.generate(Nothing.class);

      JsonNode parsed = mapper.readTree(published.json());

      assertThat(parsed.get("type").asString()).isEqualTo("object");
      assertThat(parsed.get("properties").isObject()).isTrue();
    }

    /** Two calls cannot alias: there is no node to share. */
    @Test
    void isImmutableSoNoAdapterCanCorruptItForAnother() {
      assertThat(generator.generate(ReadFile.class)).isEqualTo(generator.generate(ReadFile.class));
    }

    @Test
    void rejectsBlankJson() {
      assertThatThrownBy(() -> new InputSchema("  ")).isInstanceOf(IllegalArgumentException.class);
    }
  }

  // ---- the customizer ----

  @Nested
  class TheCustomizer {

    record Bounded(@Size(max = 10) String code) {}

    /**
     * The extension point that replaces shipping every victools module: an application adds the
     * module it wants, and the annotations that module reads, and the constraint reaches the
     * schema.
     */
    @Test
    void aModuleAddedByTheCustomizerReachesTheSchema() {
      VictoolsInputSchemaGenerator customized =
          new VictoolsInputSchemaGenerator(config -> config.with(new JakartaValidationModule()));

      ObjectNode schema = customized.generateNode(Bounded.class);

      assertThat(schema.at("/properties/code/maxLength").asInt()).isEqualTo(10);
    }

    /** Without the module, the same record generates no constraint at all. */
    @Test
    void theDefaultGeneratorIgnoresValidationAnnotations() {
      ObjectNode schema = generator.generateNode(Bounded.class);

      assertThat(schema.at("/properties/code/maxLength").isMissingNode()).isTrue();
    }

    record Tree(String name, List<Tree> children) {}

    /**
     * {@code INLINE_ALL_SCHEMAS} is the other thing victools' own OpenAI function-calling example
     * switches on, and it is reachable through the customizer -- proving the customizer carries
     * generator options, not only modules.
     */
    @Test
    void anOptionAddedByTheCustomizerInlinesInsteadOfReferencing() {
      VictoolsInputSchemaGenerator inlining =
          new VictoolsInputSchemaGenerator(config -> config.with(Option.INLINE_ALL_SCHEMAS));

      ObjectNode schema = inlining.generateNode(VocabularyWithSharedNestedRecord.class);

      assertThat(schema.has("$defs")).isFalse();
      List<String> refs = new ArrayList<>();
      collectRefs(schema, refs);
      assertThat(refs).isEmpty();
    }

    /**
     * And why it stays off by default: inlining cannot represent a type that contains itself, so
     * switching it on globally would make tree-shaped tool inputs unregisterable.
     */
    @Test
    void inliningCannotRepresentARecursiveInputType() {
      VictoolsInputSchemaGenerator inlining =
          new VictoolsInputSchemaGenerator(config -> config.with(Option.INLINE_ALL_SCHEMAS));

      assertThatThrownBy(() -> inlining.generateNode(Tree.class))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("circular reference");
    }

    @Test
    void theDefaultGeneratorHandlesARecursiveInputType() {
      ObjectNode schema = generator.generateNode(Tree.class);

      assertThat(schema.at("/properties/children/items/$ref").asString()).isEqualTo("#");
    }

    record Contested(@NotNull Optional<String> disputed) {}

    /**
     * Both this class and {@link JakartaValidationModule} answer "is this required", and they
     * disagree here: our rule says an {@link Optional} never is, {@code @NotNull} says it always
     * is. Ours is registered first and wins, so adding the module cannot silently make an optional
     * component mandatory. An application that wants the opposite registers its own required check
     * in the customizer, which runs after ours.
     */
    @Test
    void theOptionalRuleOutranksTheModulesRequiredCheck() {
      VictoolsInputSchemaGenerator customized =
          new VictoolsInputSchemaGenerator(config -> config.with(new JakartaValidationModule()));

      ObjectNode schema = customized.generateNode(Contested.class);

      assertThat(schema.has("required")).isFalse();
    }
  }
}
