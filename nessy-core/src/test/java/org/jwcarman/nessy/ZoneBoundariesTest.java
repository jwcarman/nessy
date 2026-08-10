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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A compensating control for the withdrawn JPMS module descriptor (see CHANGELOG): with no {@code
 * module-info.java} to enforce them at compile time, the zone boundaries between {@code api},
 * {@code spi}, and {@code internal} are pinned here instead, by scanning the source text itself.
 */
class ZoneBoundariesTest {

  private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

  /** The only api types allowed to reach into internal machinery, and why. */
  private static final Set<String> SANCTIONED_API_TO_INTERNAL_IMPORTS =
      Set.of(
          "SessionId.java", // -> Identifiers, for UUIDv7 generation
          "ParkToken.java", // -> Identifiers, for UUIDv7 generation
          "Tool.java" // -> Schemas, to derive its wire ToolSpec
          );

  @Test
  void no_file_under_api_imports_spi() {
    List<JavaFile> filesUnderApi = filesUnder("api");
    assertThat(filesUnderApi).isNotEmpty();
    for (JavaFile file : filesUnderApi) {
      assertThat(file.importsPackage("org.jwcarman.nessy.spi"))
          .as(
              "%s imports org.jwcarman.nessy.spi, but api may not depend on spi",
              file.relativePath())
          .isFalse();
    }
  }

  @Test
  void files_under_api_importing_internal_are_exactly_the_sanctioned_set() {
    List<JavaFile> filesUnderApi = filesUnder("api");
    assertThat(filesUnderApi).isNotEmpty();
    for (JavaFile file : filesUnderApi) {
      if (file.importsPackage("org.jwcarman.nessy.internal")) {
        assertThat(SANCTIONED_API_TO_INTERNAL_IMPORTS)
            .as(
                "%s imports org.jwcarman.nessy.internal but is not on the sanctioned list; either"
                    + " widen the sanctioned set deliberately or remove the dependency",
                file.relativePath())
            .contains(file.fileName());
      }
    }
  }

  @Test
  void root_package_files_may_import_api_and_spi_but_not_internal() {
    for (JavaFile file : rootPackageFiles()) {
      assertThat(file.importsPackage("org.jwcarman.nessy.internal"))
          .as(
              "%s imports org.jwcarman.nessy.internal, but root-package files may only depend on"
                  + " api and spi",
              file.relativePath())
          .isFalse();
    }
  }

  /**
   * {@code spi.context} (the {@link org.jwcarman.nessy.spi.context.Projection}, {@link
   * org.jwcarman.nessy.spi.context.ContextEnricher}, {@link
   * org.jwcarman.nessy.spi.context.ContextPipeline} home) is free to depend on {@code api}, the way
   * {@code spi.model} already does, but it does not get the wider spi zone's licence to reach into
   * {@code internal}: nothing in its public signatures needs engine machinery — {@link
   * org.jwcarman.nessy.spi.context.ContextPipeline} mints its own {@code nessy.context.enrich}
   * observation directly rather than depending on {@code internal.EngineObservations}. {@code
   * TokenEstimator} (§10.8's edit algebra) lives in {@code api.message} instead, beside {@link
   * org.jwcarman.nessy.api.message.Context}, which takes it directly in {@code tokens}/{@code
   * limitTokens} — a type in {@code Context}'s own public signature cannot live in {@code spi}, per
   * the ban this class enforces.
   */
  @Test
  void no_file_under_spi_context_imports_internal() {
    List<JavaFile> filesUnderSpiContext = filesUnder("spi/context");
    assertThat(filesUnderSpiContext).isNotEmpty();
    for (JavaFile file : filesUnderSpiContext) {
      assertThat(file.importsPackage("org.jwcarman.nessy.internal"))
          .as(
              "%s imports org.jwcarman.nessy.internal, but spi.context may not",
              file.relativePath())
          .isFalse();
    }
  }

  /** The api-to-spi ban (see {@link #no_file_under_api_imports_spi}) covers spi.context too. */
  @Test
  void no_file_under_api_imports_spi_context() {
    List<JavaFile> filesUnderApi = filesUnder("api");
    assertThat(filesUnderApi).isNotEmpty();
    for (JavaFile file : filesUnderApi) {
      assertThat(file.importsPackage("org.jwcarman.nessy.spi.context"))
          .as(
              "%s imports org.jwcarman.nessy.spi.context, but api may not depend on spi",
              file.relativePath())
          .isFalse();
    }
  }

  /**
   * {@code spi.compaction} ({@link org.jwcarman.nessy.spi.compaction.Summarizer}, {@link
   * org.jwcarman.nessy.spi.compaction.CompactionStrategies} home) is free to depend on {@code api}
   * — {@link org.jwcarman.nessy.api.compaction.CompactionStrategy}'s {@code summarizing} factory
   * could not live on the {@code api} interface itself precisely because it needs this package's
   * {@code Summarizer} — but, like {@code spi.context}, it does not get the wider spi zone's
   * licence to reach into {@code internal}.
   */
  @Test
  void no_file_under_spi_compaction_imports_internal() {
    List<JavaFile> filesUnderSpiCompaction = filesUnder("spi/compaction");
    assertThat(filesUnderSpiCompaction).isNotEmpty();
    for (JavaFile file : filesUnderSpiCompaction) {
      assertThat(file.importsPackage("org.jwcarman.nessy.internal"))
          .as(
              "%s imports org.jwcarman.nessy.internal, but spi.compaction may not",
              file.relativePath())
          .isFalse();
    }
  }

  /** The api-to-spi ban (see {@link #no_file_under_api_imports_spi}) covers spi.compaction too. */
  @Test
  void no_file_under_api_imports_spi_compaction() {
    List<JavaFile> filesUnderApi = filesUnder("api");
    assertThat(filesUnderApi).isNotEmpty();
    for (JavaFile file : filesUnderApi) {
      assertThat(file.importsPackage("org.jwcarman.nessy.spi.compaction"))
          .as(
              "%s imports org.jwcarman.nessy.spi.compaction, but api may not depend on spi",
              file.relativePath())
          .isFalse();
    }
  }

  /**
   * Files whose relative path is under the given slash-separated package segment — matching both
   * the segment's direct children and anything nested deeper. A zone name is the leading path
   * segment (paths start {@code "api/..."}, not {@code "/api/..."}), so this checks a leading
   * prefix rather than a substring wrapped in slashes; the substring form matches nothing, ever,
   * for a top-level zone.
   */
  private static List<JavaFile> filesUnder(String segment) {
    String prefix = segment + "/";
    return allJavaFiles().stream().filter(file -> file.relativePath().startsWith(prefix)).toList();
  }

  /** Files directly in {@code org/jwcarman/nessy/}, not in any of its sub-packages. */
  private static List<JavaFile> rootPackageFiles() {
    return allJavaFiles().stream().filter(file -> !file.relativePath().contains("/")).toList();
  }

  private static List<JavaFile> allJavaFiles() {
    Path packageRoot = SOURCE_ROOT.resolve(Path.of("org", "jwcarman", "nessy"));
    try (Stream<Path> paths = Files.walk(packageRoot)) {
      return paths
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> new JavaFile(packageRoot.relativize(path), read(path)))
          .collect(Collectors.toList());
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
   * One source file's path (relative to {@code org/jwcarman/nessy}, {@code /}-separated) and text.
   */
  private record JavaFile(String relativePath, String fileName, String content) {

    JavaFile(Path relativePath, String content) {
      this(
          relativePath.toString().replace('\\', '/'),
          relativePath.getFileName().toString(),
          content);
    }

    boolean importsPackage(String packageName) {
      return content
          .lines()
          .anyMatch(line -> line.strip().startsWith("import " + packageName + "."));
    }
  }
}
