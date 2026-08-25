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
package org.jwcarman.nessy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The dsl-coherence law (design of record 2026-08-16 §1, §5): a factory method takes a named
 * customizer, hands it a config with fluent setters and NO {@code build()} method, and returns the
 * finished thing — nothing half-built is representable, and no public {@code build()} survives
 * anywhere. This pins that law by scanning source text for the banned declaration shapes — a public
 * {@code build(...)}, a public static {@code builder(...)}, and their implicitly-public
 * interface-member equivalents — the same source-scanning technique {@link ZoneBoundariesTest} uses
 * in place of a withdrawn {@code module-info.java}.
 *
 * <p><b>Coverage:</b> every sibling module, reached by relative path from this one, that ships
 * {@code src/main/java} and is part of the public nessy surface: {@code nessy-api} (this module),
 * {@code nessy-spi}, the four model providers ({@code nessy-model-anthropic}, {@code
 * nessy-model-openai}, {@code nessy-model-gemini}, {@code nessy-model-bedrock}), {@code
 * nessy-model-discovery}, {@code nessy-testing}, {@code nessy-tool-mcp}, and {@code nessy-intent}.
 * {@code nessy-bom} ships no Java at all; {@code nessy-spring-boot-starter} ships only a {@code
 * src/main/javadoc} placeholder type, not a real public class; {@code nessy-examples} is demo code
 * with its own nested reactor, not a published module. None of those three are scanned.
 *
 * <p><b>What the technique actually catches, precisely:</b>
 *
 * <ul>
 *   <li>A public (optionally static, optionally generic) {@code build(...)} or a public static
 *       {@code builder(...)} declared on a public top-level class or record, any arity, as long as
 *       the whole signature up to the opening parenthesis fits on one source line.
 *   <li>The same two shapes declared <em>without</em> the {@code public} keyword — an abstract
 *       method ({@code Foo build();}), a default method ({@code default Foo build() { ... }}), or a
 *       static method ({@code static Foo builder() { ... }}) — but only inside a file whose
 *       top-level type is itself a public {@code interface}, where the JLS makes such members
 *       public whether or not the modifier is written.
 * </ul>
 *
 * <p>A method declaration only matches when the line itself, once stripped, starts with the
 * expected keyword ({@code public}, or inside an interface file, anything that is not itself {@code
 * public}/{@code private}/{@code static} for the bare-{@code build} case); a call site reached
 * through a dot (e.g. {@code AnthropicOkHttpClient.builder()}, {@code realBuilder.build()}, {@code
 * return config.build();}) never satisfies that anchor and is excluded by construction, not by a
 * name-based guess — {@link #The_pattern_positive_control} proves both directions with fixture
 * source text.
 *
 * <p><b>Known blind spot, honestly stated:</b> a signature whose {@code public}/{@code static}
 * keyword, return type, or method name is wrapped onto its own line by a formatter is invisible to
 * this line-anchored scan; the codebase's actual zero-arg {@code build()}/{@code builder()}
 * signatures are short enough that google-java-format never wraps them, so this has not mattered in
 * practice, but it is not a technique-level guarantee. Package-private internals in ordinary
 * classes are excluded by the top-level-public-type gate, not by any assumption about what a
 * package-private method happens to be named.
 */
class NoPublicBuildersTest {

  private static final List<String> SCANNED_MODULES =
      List.of(
          "nessy-api",
          "nessy-spi",
          "nessy-model-anthropic",
          "nessy-model-openai",
          "nessy-model-gemini",
          "nessy-model-bedrock",
          "nessy-model-discovery",
          "nessy-testing",
          "nessy-tool-mcp",
          "nessy-intent");

  private static final Pattern PUBLIC_TYPE_DECLARATION =
      Pattern.compile(
          "^public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
              + "(?:class|interface|record|enum|@interface)\\b");

  private static final Pattern PUBLIC_INTERFACE_DECLARATION =
      Pattern.compile("^public\\s+(?:sealed\\s+|non-sealed\\s+)*interface\\b");

  /** A public (optionally static, optionally generic) {@code build(...)}, any arity. */
  private static final Pattern PUBLIC_BUILD_METHOD =
      Pattern.compile("^public\\s+(?:static\\s+)?(?:<[^>]+>\\s+)?\\S.*\\bbuild\\s*\\(");

  /** A public static (optionally generic) {@code builder(...)}. */
  private static final Pattern PUBLIC_STATIC_BUILDER_METHOD =
      Pattern.compile("^public\\s+static\\s+(?:<[^>]+>\\s+)?\\S.*\\bbuilder\\s*\\(");

  /**
   * An interface member named {@code build} written without an explicit {@code public} — an
   * abstract declaration ({@code Foo build();}) or a default method ({@code default Foo build()
   * {}}) — which the JLS makes public regardless. Excludes lines starting with {@code public}
   * (already caught above), {@code private} (Java 9+ private interface methods are genuinely not
   * public), or {@code static} (covered by the builder-only static pattern below, not this one).
   * The negative lookbehind on the dot keeps a call site like {@code return x.build();} from
   * matching: no abstract or default declaration ever names its own method through a receiver.
   */
  private static final Pattern INTERFACE_IMPLICIT_INSTANCE_BUILD_METHOD =
      Pattern.compile(
          "^(?!public\\b)(?!private\\b)(?!static\\b)(?:<[^>]+>\\s+)?\\S.*(?<!\\.)\\bbuild\\s*\\("
              + "[^)]*\\)\\s*[;{]\\s*$");

  /**
   * An interface static factory named {@code builder} written without an explicit {@code public} —
   * {@code static Foo builder() { ... }} — which the JLS makes public regardless. A static
   * interface method always carries a body, so this only matches the {@code {}-terminated} form.
   */
  private static final Pattern INTERFACE_IMPLICIT_STATIC_BUILDER_METHOD =
      Pattern.compile(
          "^(?!public\\b)(?!private\\b)static\\s+(?:<[^>]+>\\s+)?\\S.*(?<!\\.)\\bbuilder\\s*\\("
              + "[^)]*\\)\\s*\\{\\s*$");

  @Test
  void no_public_class_in_a_scanned_module_exposes_a_public_build_or_builder_method() {
    List<JavaFile> files = scannedJavaFiles();
    assertThat(files).isNotEmpty();

    List<String> violations =
        files.stream().flatMap(file -> file.bannedMethodSignatures().stream()).toList();

    assertThat(violations)
        .as(
            "the dsl-coherence law (design of record 2026-08-16 §1) forbids a public build() and"
                + " a public static builder() anywhere on the public surface — every construction"
                + " path is a named-customizer factory instead; if one of these is a legitimate"
                + " survivor, exempt it explicitly here with a reason")
        .isEmpty();
  }

  private static List<JavaFile> scannedJavaFiles() {
    return SCANNED_MODULES.stream().flatMap(module -> javaFilesIn(module).stream()).toList();
  }

  private static List<JavaFile> javaFilesIn(String module) {
    Path root = Path.of("..", module, "src", "main", "java");
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("expected module source root is missing: " + root);
    }
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> new JavaFile(module, root.relativize(path).toString(), read(path)))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One source file's owning module, path (relative to that module's {@code src/main/java}), and
   * text. {@code module}/{@code relativePath} may be a synthetic label rather than a real on-disk
   * location — {@link The_pattern_positive_control} builds fixture instances directly to prove the
   * two banned shapes, including their interface-implicit forms, are actually caught.
   */
  private record JavaFile(String module, String relativePath, String content) {

    boolean declaresAPublicType() {
      return content
          .lines()
          .map(String::strip)
          .anyMatch(line -> PUBLIC_TYPE_DECLARATION.matcher(line).find());
    }

    boolean declaresAPublicInterface() {
      return content
          .lines()
          .map(String::strip)
          .anyMatch(line -> PUBLIC_INTERFACE_DECLARATION.matcher(line).find());
    }

    List<String> bannedMethodSignatures() {
      if (!declaresAPublicType()) {
        return List.of();
      }
      boolean isPublicInterface = declaresAPublicInterface();
      return content
          .lines()
          .map(String::strip)
          .filter(
              line ->
                  PUBLIC_BUILD_METHOD.matcher(line).find()
                      || PUBLIC_STATIC_BUILDER_METHOD.matcher(line).find()
                      || (isPublicInterface
                          && (INTERFACE_IMPLICIT_INSTANCE_BUILD_METHOD.matcher(line).find()
                              || INTERFACE_IMPLICIT_STATIC_BUILDER_METHOD.matcher(line).find())))
          .map(line -> "%s/%s: %s".formatted(module, relativePath, line))
          .toList();
    }
  }

  /**
   * S1/S2 (final review, dsl-coherence): without this, the only proof the two regexes above are
   * correct was a manual redness experiment recorded in a task ledger — if a pattern silently
   * stopped matching, the architecture test would report zero violations forever. These fixtures
   * plant every shape the law bans, including the interface-implicit and multi-arity forms, and
   * assert the scanner still names them; a broken pattern now fails this suite, not just a one-time
   * manual check.
   */
  @Nested
  class The_pattern_positive_control {

    @Test
    void a_public_instance_build_method_on_a_class_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureConfig.java",
              """
              public final class FixtureConfig {

                public FixtureConfig build() {
                  return this;
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureConfig.java: public FixtureConfig build() {");
    }

    @Test
    void a_public_static_builder_method_on_a_class_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureConfig.java",
              """
              public final class FixtureConfig {

                public static FixtureConfig builder() {
                  return new FixtureConfig();
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureConfig.java: public static FixtureConfig builder() {");
    }

    @Test
    void a_public_build_method_that_takes_arguments_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureConfig.java",
              """
              public final class FixtureConfig {

                public FixtureConfig build(String extra) {
                  return this;
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly(
              "fixture/FixtureConfig.java: public FixtureConfig build(String extra) {");
    }

    @Test
    void an_implicitly_public_abstract_build_method_on_an_interface_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureBuilder.java",
              """
              public interface FixtureBuilder {

                FixtureBuilder build();
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureBuilder.java: FixtureBuilder build();");
    }

    @Test
    void an_implicitly_public_abstract_build_method_with_arguments_on_an_interface_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureBuilder.java",
              """
              public interface FixtureBuilder {

                FixtureBuilder build(String extra);
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureBuilder.java: FixtureBuilder build(String extra);");
    }

    @Test
    void an_implicitly_public_default_build_method_on_an_interface_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureBuilder.java",
              """
              public interface FixtureBuilder {

                default FixtureBuilder build() {
                  return this;
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureBuilder.java: default FixtureBuilder build() {");
    }

    @Test
    void an_implicitly_public_static_builder_method_on_an_interface_is_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureBuilder.java",
              """
              public interface FixtureBuilder {

                static FixtureBuilder builder() {
                  return null;
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures())
          .containsExactly("fixture/FixtureBuilder.java: static FixtureBuilder builder() {");
    }

    @Test
    void an_sdk_builder_call_site_is_not_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureAnthropicLike.java",
              """
              public final class FixtureAnthropicLike {

                private FixtureAnthropicLike() {
                  var clientBuilder = AnthropicOkHttpClient.builder().apiKey(apiKey);
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures()).isEmpty();
    }

    @Test
    void a_package_private_build_call_site_through_a_config_receiver_is_not_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureFactory.java",
              """
              public final class FixtureFactory {

                public static FixtureConfig create() {
                  return config.build();
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures()).isEmpty();
    }

    @Test
    void a_package_private_build_method_on_a_non_public_class_is_not_flagged() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureInternal.java",
              """
              final class FixtureInternal {

                static FixtureInternal build(FixtureConfig config) {
                  return new FixtureInternal();
                }

                FixtureInternal build() {
                  return this;
                }
              }
              """);

      assertThat(fixture.declaresAPublicType()).isFalse();
      assertThat(fixture.bannedMethodSignatures()).isEmpty();
    }

    @Test
    void a_bare_build_call_inside_a_default_method_body_is_not_mistaken_for_a_declaration() {
      var fixture =
          new JavaFile(
              "fixture",
              "FixtureBuilder.java",
              """
              public interface FixtureBuilder {

                default FixtureBuilder buildViaDelegate() {
                  return realBuilder.build();
                }
              }
              """);

      assertThat(fixture.bannedMethodSignatures()).isEmpty();
    }
  }
}
