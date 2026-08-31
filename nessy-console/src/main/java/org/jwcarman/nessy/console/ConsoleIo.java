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
 * The terminal, as the loop sees it: a line to read and somewhere to write.
 *
 * <p>Two methods, so a test can drive the loop with a script and a buffer instead of a console.
 * That is the whole reason this exists — a REPL whose only door is {@code System.in} can be run but
 * never asserted on.
 */
interface ConsoleIo {

  /** The next line, or null at end of input. */
  String readLine();

  void write(String text);

  /** Makes anything written so far visible, which matters when a prompt has no newline. */
  void flush();

  /** The real one. */
  static ConsoleIo standard() {
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
