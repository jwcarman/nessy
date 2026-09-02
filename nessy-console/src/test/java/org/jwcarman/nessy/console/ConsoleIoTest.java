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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real console: the one door {@link ConsoleIo#standard()} builds over {@code System.in} and
 * {@code System.out}.
 *
 * <p>{@link ConsoleIo.Standard#create()} is exercised directly, with {@code System.in}/{@code
 * System.out} swapped first, rather than through the {@code standard()} singleton — the singleton
 * is created at most once per JVM, on whichever thread happens to touch it first, so a test that
 * relied on being that thread would be racing every other test in this module.
 */
@DisplayName("The real console")
class ConsoleIoTest {

  private final InputStream originalIn = System.in;
  private final PrintStream originalOut = System.out;

  @AfterEach
  void restore_the_real_streams() {
    System.setIn(originalIn);
    System.setOut(originalOut);
  }

  @Test
  @DisplayName("standard() hands back a real console, not null")
  void standard_is_a_real_console() {
    assertThat(ConsoleIo.standard()).isNotNull();
  }

  @Test
  void reads_a_line_typed_on_stdin() {
    System.setIn(new ByteArrayInputStream("hello there\n".getBytes(StandardCharsets.UTF_8)));

    ConsoleIo io = ConsoleIo.Standard.create();

    assertThat(io.readLine()).isEqualTo("hello there");
  }

  @Test
  @DisplayName("end of input reads back as null, the way the loop expects")
  void end_of_input_is_null() {
    System.setIn(new ByteArrayInputStream(new byte[0]));

    ConsoleIo io = ConsoleIo.Standard.create();

    assertThat(io.readLine()).isNull();
  }

  @Test
  @DisplayName("a broken stdin is reported as an unchecked exception, not swallowed")
  void a_broken_stream_is_reported_rather_than_swallowed() {
    System.setIn(
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("pipe is gone");
          }
        });

    ConsoleIo io = ConsoleIo.Standard.create();

    assertThatThrownBy(io::readLine).isInstanceOf(UncheckedIOException.class);
  }

  @Test
  void writes_go_to_stdout() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

    ConsoleIo io = ConsoleIo.Standard.create();
    io.write("hello");

    assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  @DisplayName("flush() reaches the underlying stream")
  void flush_reaches_the_underlying_stream() {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    // autoFlush disabled: only an explicit flush() should push bytes through print(), so this
    // proves flush() is the thing doing the work rather than PrintStream's own newline flushing.
    PrintStream unflushed = new PrintStream(captured, false, StandardCharsets.UTF_8);
    System.setOut(unflushed);

    ConsoleIo io = ConsoleIo.Standard.create();
    io.write("buffered");
    io.flush();

    assertThat(captured.toString(StandardCharsets.UTF_8)).isEqualTo("buffered");
  }
}
