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

/**
 * SGR styling ({@code ESC[...m}) — nothing more. No cursor addressing, no raw mode, no alternate
 * screen: the v1 hard line (design §1) is styling yes, terminal takeover no.
 *
 * <p>{@link #enabled()} is computed once, from three checks any well-behaved terminal program
 * makes: is a real console attached ({@link System#console()}), has the caller opted out ({@code
 * NO_COLOR}, per the <a href="https://no-color.org">NO_COLOR convention</a> — any value at all
 * counts), and is the terminal one that cannot render SGR at all ({@code TERM=dumb}). When styling
 * is disabled, every wrapper below is an exact pass-through: piping this library's output to a file
 * yields clean, colorless text, byte for byte.
 */
public final class Ansi {

  /**
   * The ANSI escape character ({@code ESC}, {@code 0x1B}) that opens every SGR sequence below,
   * spelled with an explicit {@code \u001B} escape rather than a bare control character embedded in
   * the source -- readable in a diff and an editor, and it satisfies java:S2479 (raw control
   * characters in string literals) without suppressing the rule.
   */
  private static final String ESC = "\u001B[";

  private static final String RESET = ESC + "0m";
  private static final String BOLD = ESC + "1m";
  private static final String DIM = ESC + "2m";
  private static final String ITALIC = ESC + "3m";
  private static final String CYAN = ESC + "36m";
  private static final String YELLOW = ESC + "33m";
  private static final String RED = ESC + "31m";
  private static final String GREEN = ESC + "32m";

  private static final boolean DETECTED = detect();

  /**
   * The test-only override seam: {@code null} defers to {@link #DETECTED}; {@code true}/{@code
   * false} forces the answer regardless of the real environment. Package-private on purpose — no
   * application code outside this module's own tests should ever need to fake a terminal.
   */
  private static Boolean override;

  private Ansi() {}

  /** Whether SGR styling is on right now — cached at class-load, overridable by tests. */
  public static boolean enabled() {
    Boolean forced = override;
    return forced != null ? forced : DETECTED;
  }

  static void overrideEnabled(Boolean value) {
    override = value;
  }

  private static boolean detect() {
    if (System.console() == null) {
      return false;
    }
    if (System.getenv("NO_COLOR") != null) {
      return false;
    }
    return !"dumb".equals(System.getenv("TERM"));
  }

  /** {@code text}, wrapped bold — the approval prompt's own emphasis. */
  public static String bold(String text) {
    return style(BOLD, text);
  }

  /** {@code text}, wrapped dim — tool-activity lines, the muted half of the default look. */
  public static String dim(String text) {
    return style(DIM, text);
  }

  /**
   * {@code text}, wrapped italic — combined with {@link #dim} for the default renderer's thinking.
   */
  public static String italic(String text) {
    return style(ITALIC, text);
  }

  /**
   * {@code text}, wrapped cyan. Part of the small palette (design §2), not yet claimed by a line.
   */
  public static String cyan(String text) {
    return style(CYAN, text);
  }

  /**
   * {@code text}, wrapped yellow — paired with {@link #bold} for the approval prompt's highlight.
   */
  public static String yellow(String text) {
    return style(YELLOW, text);
  }

  /** {@code text}, wrapped red — a failed turn's ending line. */
  public static String red(String text) {
    return style(RED, text);
  }

  /**
   * {@code text}, wrapped green. Part of the small palette (design §2), not yet claimed by a line.
   */
  public static String green(String text) {
    return style(GREEN, text);
  }

  private static String style(String code, String text) {
    return enabled() ? code + text + RESET : text;
  }
}
