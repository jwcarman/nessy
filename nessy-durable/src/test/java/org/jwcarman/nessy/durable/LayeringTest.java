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
package org.jwcarman.nessy.durable;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The base of the stack imports nothing above it — checked mechanically. */
class LayeringTest {

  private static final Pattern FORBIDDEN = Pattern.compile("org\\.jwcarman\\.nessy\\.(?!durable)");

  @Test
  void theBaseImportsNothingAbove() {
    Path root = Path.of("src", "main", "java");
    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> offending =
          paths
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> FORBIDDEN.matcher(read(path)).find())
              .toList();
      assertThat(offending).isEmpty();
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
