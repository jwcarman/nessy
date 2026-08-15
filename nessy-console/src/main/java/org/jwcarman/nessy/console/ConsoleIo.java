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
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The process-global stdin reader, shared by every real-console entry point in this module.
 *
 * <p>Two independent {@link BufferedReader}s each wrapping {@link System#in} steal from each other:
 * whichever one primes its internal buffer first can swallow bytes the other was about to read. A
 * pasted multi-line block, or piped input, is the sharp case — {@link ConsoleRepl}'s own reader
 * fills its buffer past the line the REPL just asked for, and a separately constructed {@link
 * ConsoleApprover} reader then sees end-of-stream on the very next read and denies, even though the
 * answer was sitting in the REPL's buffer the whole time.
 *
 * <p>One process, one stdin, one reader: {@link ConsoleRepl.Builder#run()} and {@link
 * ConsoleApprover}'s default constructor both read through this single, lazily-nothing-special,
 * eagerly-constructed instance rather than each wrapping {@link System#in} on their own. Neither
 * public constructor takes an argument here — this is the real-console default both fall back to;
 * every test seam in this module still takes its own injected reader, untouched by this class.
 */
final class ConsoleIo {

  private static final BufferedReader STDIN =
      new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

  private ConsoleIo() {}

  /** The one {@link BufferedReader} every real-console entry point in this module reads through. */
  static BufferedReader stdin() {
    return STDIN;
  }

  /**
   * A fresh {@link Writer} over the real process stdout — the one place in this module that names
   * {@link System#out} directly, so {@link ConsoleRepl.Builder#run()} and {@link ConsoleApprover}'s
   * default constructor both delegate here instead of each wrapping the stream themselves. This is
   * the adapter boundary a console REPL cannot avoid: the whole point of this library is writing to
   * the real terminal a human is watching, not logging, so there is no logger to route through here
   * — a fresh {@link Writer} per call (unlike {@link #stdin()}'s single shared reader) because,
   * unlike input, two writers over the same output stream do not steal bytes from each other.
   */
  static Writer stdout() {
    return new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
  }
}
