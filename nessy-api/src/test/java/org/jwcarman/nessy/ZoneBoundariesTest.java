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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A compensating control for the withdrawn JPMS module descriptor (see CHANGELOG): with no {@code
 * module-info.java} to enforce them at compile time, the zone boundaries between {@code api},
 * {@code spi}, and {@code internal} are pinned here instead, by scanning the source text itself.
 */
class ZoneBoundariesTest {

  private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

  /** The api-to-spi ban, covering the top-level spi zone. */
  @ParameterizedTest(name = "no file under api imports {0}")
  @MethodSource("apiForbiddenSpiPackages")
  void no_file_under_api_imports_spi_zone(String forbiddenPackage) {
    List<JavaFile> filesUnderApi = filesUnder("api");
    assertThat(filesUnderApi).isNotEmpty();
    for (JavaFile file : filesUnderApi) {
      assertThat(file.importsPackage(forbiddenPackage))
          .as("%s imports %s, but api may not depend on spi", file.relativePath(), forbiddenPackage)
          .isFalse();
    }
  }

  private static Stream<String> apiForbiddenSpiPackages() {
    return Stream.of("org.jwcarman.nessy.spi");
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

  private static List<JavaFile> allJavaFiles() {
    Path packageRoot = SOURCE_ROOT.resolve(Path.of("org", "jwcarman", "nessy"));
    try (Stream<Path> paths = Files.walk(packageRoot)) {
      return paths
          .filter(path -> path.toString().endsWith(".java"))
          .map(path -> new JavaFile(packageRoot.relativize(path), read(path)))
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

  @Test
  void nothing_imports_internal_because_internal_is_gone() {
    List<JavaFile> allFiles = allJavaFiles();
    assertThat(allFiles).isNotEmpty();

    List<JavaFile> importers =
        allFiles.stream()
            .filter(file -> file.importsPackage("org.jwcarman.nessy.internal"))
            .toList();
    assertThat(importers).isEmpty();
  }
}
