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
    for (JavaFile file : filesUnder("api")) {
      assertThat(file.importsPackage("org.jwcarman.nessy.spi"))
          .as(
              "%s imports org.jwcarman.nessy.spi, but api may not depend on spi",
              file.relativePath())
          .isFalse();
    }
  }

  @Test
  void files_under_api_importing_internal_are_exactly_the_sanctioned_set() {
    for (JavaFile file : filesUnder("api")) {
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

  private static List<JavaFile> filesUnder(String zone) {
    return allJavaFiles().stream()
        .filter(file -> file.relativePath().contains("/" + zone + "/"))
        .toList();
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
