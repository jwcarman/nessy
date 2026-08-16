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
import org.junit.jupiter.api.Test;

/**
 * The dsl-coherence law (design of record 2026-08-16 §1, §5): a factory method takes a named
 * customizer, hands it a config with fluent setters and NO {@code build()} method, and returns the
 * finished thing — nothing half-built is representable, and no public {@code build()} survives
 * anywhere. This pins that law by scanning source text for the two banned declaration shapes — a
 * public {@code build()} and a public static {@code builder()} — the same source-scanning technique
 * {@link ZoneBoundariesTest} uses in place of a withdrawn {@code module-info.java}.
 *
 * <p><b>Coverage:</b> every sibling module, reached by relative path from this one, that ships
 * {@code src/main/java} and is part of the public nessy surface: {@code nessy-core} (this module),
 * {@code nessy-console}, the four model providers ({@code nessy-model-anthropic}, {@code
 * nessy-model-openai}, {@code nessy-model-gemini}, {@code nessy-model-bedrock}), {@code
 * nessy-model-env}, {@code nessy-testing}, {@code nessy-jdbc}, {@code nessy-tck}, {@code
 * nessy-tool-mcp}, and {@code nessy-autoconfigure}. {@code nessy-bom} ships no Java at all; {@code
 * nessy-spring-boot-starter} ships only a {@code src/main/javadoc} placeholder type, not a real
 * public class; {@code nessy-examples} is demo code with its own nested reactor, not a published
 * module. None of those three are scanned.
 *
 * <p>A method declaration only matches when the line itself, once stripped, starts with {@code
 * public}; SDK builder call sites (e.g. {@code AnthropicOkHttpClient.builder()}) and javadoc prose
 * (e.g. {@code "no public {@code build()} survives"}) never start a line that way, so neither is a
 * candidate false positive here. Package-private internals are invisible to the scan by
 * construction: this test only considers files whose top-level type is itself declared public, and
 * no exemption was needed for any file the scan reached — Tasks 1 through 3 already converted every
 * nessy-owned builder to a package-private {@code build()} reached only from its sibling factory.
 */
class NoPublicBuildersTest {

  private static final List<String> SCANNED_MODULES =
      List.of(
          "nessy-core",
          "nessy-console",
          "nessy-model-anthropic",
          "nessy-model-openai",
          "nessy-model-gemini",
          "nessy-model-bedrock",
          "nessy-model-env",
          "nessy-testing",
          "nessy-jdbc",
          "nessy-tck",
          "nessy-tool-mcp",
          "nessy-autoconfigure");

  private static final Pattern PUBLIC_TYPE_DECLARATION =
      Pattern.compile(
          "^public\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)*"
              + "(?:class|interface|record|enum|@interface)\\b");

  private static final Pattern PUBLIC_BUILD_METHOD =
      Pattern.compile("^public\\s+(?:static\\s+)?(?:<[^>]+>\\s+)?\\S.*\\bbuild\\s*\\(\\s*\\)");

  private static final Pattern PUBLIC_STATIC_BUILDER_METHOD =
      Pattern.compile("^public\\s+static\\s+(?:<[^>]+>\\s+)?\\S.*\\bbuilder\\s*\\(\\s*\\)");

  @Test
  void no_public_class_in_a_scanned_module_exposes_a_public_build_or_builder_method() {
    List<JavaFile> files = scannedJavaFiles();
    assertThat(files).isNotEmpty();

    List<String> violations =
        files.stream()
            .filter(JavaFile::declaresAPublicType)
            .flatMap(file -> file.bannedMethodSignatures().stream())
            .toList();

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
   * text.
   */
  private record JavaFile(String module, String relativePath, String content) {

    boolean declaresAPublicType() {
      return content
          .lines()
          .map(String::strip)
          .anyMatch(line -> PUBLIC_TYPE_DECLARATION.matcher(line).find());
    }

    List<String> bannedMethodSignatures() {
      return content
          .lines()
          .map(String::strip)
          .filter(
              line ->
                  PUBLIC_BUILD_METHOD.matcher(line).find()
                      || PUBLIC_STATIC_BUILDER_METHOD.matcher(line).find())
          .map(line -> "%s/%s: %s".formatted(module, relativePath, line))
          .toList();
    }
  }
}
