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
package org.jwcarman.nessy.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Holder for {@link ConsoleIo#standard()}, so the one reader is created on first use rather than at
 * class-load.
 */
final class StandardConsoleIo {

  static final ConsoleIo INSTANCE = create();

  private StandardConsoleIo() {}

  // Package-private rather than private: a test builds one directly, with System.in/out
  // redirected first, instead of fighting the timing of the lazily-initialized singleton above.
  static ConsoleIo create() {
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    PrintStream out = System.out;
    return new ConsoleIo() {

      @Override
      public String readLine() {
        try {
          return in.readLine();
        } catch (IOException e) {
          // Nothing a REPL can do about a broken stdin, and nothing a caller wants to catch:
          // the loop is over either way.
          throw new UncheckedIOException("could not read from the console", e);
        }
      }

      @Override
      public void write(String text) {
        out.print(text);
      }

      @Override
      public void flush() {
        out.flush();
      }
    };
  }
}
