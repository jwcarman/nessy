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

/**
 * The §10.10 layering, checked mechanically: api is vocabulary, spi is seams, and neither ever sees
 * the machine.
 */
class LayeringTest {

  @Test
  void api_never_imports_the_spi_or_the_machine() {
    assertThat(offendingFiles("nessy-api", "org.jwcarman.nessy.spi")).isEmpty();
    assertThat(offendingFiles("nessy-api", "org.jwcarman.nessy.agent")).isEmpty();
  }

  @Test
  void spi_never_imports_the_machine() {
    assertThat(offendingFiles("nessy-spi", "org.jwcarman.nessy.agent")).isEmpty();
  }

  private static List<Path> offendingFiles(String module, String forbidden) {
    List<Path> files = javaFilesIn(module);
    assertThat(files).isNotEmpty();
    return files.stream().filter(path -> read(path).contains(forbidden)).toList();
  }

  private static List<Path> javaFilesIn(String module) {
    Path root = Path.of("..", module, "src", "main", "java");
    try (Stream<Path> paths = Files.walk(root)) {
      return paths.filter(path -> path.toString().endsWith(".java")).toList();
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
}
