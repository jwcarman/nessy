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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A console that is a script and a buffer.
 *
 * <p>No mocking library (design of record): a REPL's whole observable behaviour is what it reads
 * and what it writes, so the double is two collections.
 */
final class FakeConsole implements ConsoleIo {

  private final Deque<String> lines = new ArrayDeque<>();
  private final StringBuilder written = new StringBuilder();
  private int flushes;

  FakeConsole(String... typed) {
    lines.addAll(List.of(typed));
  }

  @Override
  public String readLine() {
    // Empty means end of input, which is how a person closing a terminal looks from in here.
    return lines.poll();
  }

  @Override
  public void write(String text) {
    written.append(text);
  }

  @Override
  public void flush() {
    flushes++;
  }

  String written() {
    return written.toString();
  }

  int flushes() {
    return flushes;
  }
}
