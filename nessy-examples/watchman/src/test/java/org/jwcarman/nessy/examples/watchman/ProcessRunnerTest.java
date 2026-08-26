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
package org.jwcarman.nessy.examples.watchman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The one test in this module that really does start processes — because {@link ProcessRunner} is
 * the one class whose whole job is starting processes. Everything above it is tested against {@code
 * FakeRunner}, and that promise is unchanged.
 *
 * <p>It exists because this class could wedge a rounds loop permanently, and did. The original
 * drained stdout to EOF and only then stderr, on the calling thread, before ever calling {@code
 * waitFor}. A child that fills its stderr pipe buffer — roughly 64KB on Linux — blocks on write,
 * therefore never exits, therefore never closes stdout, therefore the stdout read never returns,
 * therefore {@code waitFor} is never reached and {@code watchman.command-timeout} cannot fire. Not
 * a slow command: a permanently stuck thread. {@code apt list --upgradable} and {@code dnf
 * check-update} are routine stderr chatterers, and a read-only tool runs inline on a harness
 * thread, so one wedge ends the rounds forever — spec §3's "a round that never ends", arrived at
 * from the inside.
 *
 * <p>Every assertion is wrapped in {@link
 * org.junit.jupiter.api.Assertions#assertTimeoutPreemptively}: a regression here must fail the
 * build in seconds, not hang it until CI gives up. That is the same failure mode being tested, and
 * a test for a hang must not be able to hang.
 *
 * <p>POSIX only. These use {@code /bin/sh} to build the pathological child, and the box this
 * example exists for is a Linux server.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessRunnerTest {

  private static final Duration PATIENCE = Duration.ofSeconds(30);

  private static List<String> sh(String script) {
    return List.of("/bin/sh", "-c", script);
  }

  private static CommandRunner.Output run(Duration timeout, String script) {
    ProcessRunner runner = new ProcessRunner(timeout);
    List<String> argv = sh(script);
    return assertTimeoutPreemptively(PATIENCE, () -> runner.run(argv));
  }

  @Nested
  class An_ordinary_command {

    @Test
    void reports_its_exit_code_and_both_streams_separately() {
      CommandRunner.Output output = run(Duration.ofSeconds(10), "echo out; echo err >&2; exit 0");

      assertThat(output.succeeded()).isTrue();
      assertThat(output.exitCode()).isZero();
      assertThat(output.stdout()).contains("out").doesNotContain("err");
      assertThat(output.stderr()).contains("err").doesNotContain("out");
    }

    @Test
    void keeps_a_non_zero_exit_rather_than_throwing() {
      CommandRunner.Output output = run(Duration.ofSeconds(10), "echo nope >&2; exit 7");

      assertThat(output.succeeded()).isFalse();
      assertThat(output.exitCode()).isEqualTo(7);
      assertThat(output.text()).contains("nope");
    }
  }

  @Nested
  class A_command_that_never_ends {

    @Test
    void is_destroyed_when_the_timeout_expires() {
      CommandRunner.Output output = run(Duration.ofSeconds(1), "sleep 60");

      assertThat(output.succeeded()).isFalse();
      assertThat(output.stderr()).contains("timed out");
    }

    @Test
    void still_hands_back_whatever_it_managed_to_say_first() {
      CommandRunner.Output output = run(Duration.ofSeconds(2), "echo partial; sleep 60");

      assertThat(output.stdout()).contains("partial");
      assertThat(output.stderr()).contains("timed out");
    }
  }

  @Nested
  class A_command_that_floods_stderr_while_stdout_stays_open {

    /**
     * ~128KB of stderr — comfortably past any pipe buffer — written by a child that then sits there
     * with stdout still open and never exits. Under the original single-threaded drain this call
     * never returned at all.
     */
    private static final String FLOOD =
        "i=0; while [ $i -lt 2000 ]; do "
            + "echo '0123456789012345678901234567890123456789012345678901234567890123' >&2; "
            + "i=$((i+1)); done; sleep 60";

    @Test
    void does_not_wedge_and_times_out_as_promised() {
      CommandRunner.Output output = run(Duration.ofSeconds(2), FLOOD);

      assertThat(output.succeeded()).isFalse();
      assertThat(output.stderr()).contains("timed out");
    }

    @Test
    void a_flood_from_a_command_that_does_exit_is_read_whole() {
      CommandRunner.Output output =
          run(
              Duration.ofSeconds(20),
              "i=0; while [ $i -lt 2000 ]; do "
                  + "echo '0123456789012345678901234567890123456789012345678901234567890123' >&2; "
                  + "i=$((i+1)); done; exit 0");

      assertThat(output.succeeded()).isTrue();
      assertThat(output.stderr().length()).isGreaterThan(100_000);
    }
  }

  /**
   * The chunk-boundary bug (fix round, 2026-08-26). The drain used to decode each byte chunk on its
   * own — {@code new String(buffer, 0, count, UTF_8)} — so any multi-byte UTF-8 character
   * straddling a read boundary was split and both halves became U+FFFD. {@code journalctl} is the
   * realistic victim: long output, full of the UTF-8 quotes and dashes systemd writes, and the
   * corruption reached the model as fact.
   *
   * <p>These drive {@link ProcessRunner.Drain} directly rather than running a command, because
   * through a real pipe the read boundaries belong to the kernel, not to the test: a command that
   * merely emits multi-byte text passes against the BROKEN decoder too (verified — it did), which
   * is worse than no test at all. A stream that hands out one byte per read splits every multi-byte
   * sequence there is, every time.
   */
  @Nested
  class A_stream_whose_reads_split_characters {

    private static final String MIXED =
        "systemd said \u201cstarting\u201d \u2014 caf\u00e9 \uD83D\uDD25 done";

    @Test
    void a_character_split_across_reads_is_decoded_whole() throws Exception {
      ProcessRunner.Drain drain = ProcessRunner.Drain.of(oneByteAtATime(MIXED));

      assertThat(drain.text()).isEqualTo(MIXED);
    }

    @Test
    void nothing_becomes_a_replacement_character() throws Exception {
      ProcessRunner.Drain drain = ProcessRunner.Drain.of(oneByteAtATime(MIXED));

      assertThat(drain.text()).doesNotContain("\uFFFD");
    }

    /** The multi-byte characters specifically, counted, so a partial fix cannot pass. */
    @Test
    void every_multi_byte_character_survives() throws Exception {
      ProcessRunner.Drain drain = ProcessRunner.Drain.of(oneByteAtATime(MIXED));

      String text = drain.text();
      assertThat(text).contains("\u201cstarting\u201d");
      assertThat(text).contains("\u2014");
      assertThat(text).contains("caf\u00e9");
      assertThat(text).contains("\uD83D\uDD25");
    }

    /**
     * A stream that returns exactly one byte per {@code read}, which is legal for any {@link
     * InputStream} and is what a slow pipe does under load. Every multi-byte sequence is therefore
     * split at every one of its internal boundaries.
     */
    private static InputStream oneByteAtATime(String text) {
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      return new InputStream() {

        private int position;

        @Override
        public int read() {
          return position < bytes.length ? bytes[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          if (position >= bytes.length) {
            return -1;
          }
          buffer[offset] = bytes[position++];
          return 1;
        }
      };
    }
  }

  /**
   * The per-call deadline (final review, finding #4). {@code apply_updates} passes {@code
   * watchman.upgrade-timeout} because the runner's thirty-second default would destroy dpkg
   * mid-transaction.
   */
  @Nested
  class A_deadline_of_the_calls_own {

    @Test
    void beats_the_runners_default_when_it_is_longer() {
      ProcessRunner runner = new ProcessRunner(Duration.ofMillis(200));
      List<String> argv = sh("sleep 2; echo finished");

      CommandRunner.Output output =
          assertTimeoutPreemptively(PATIENCE, () -> runner.run(argv, Duration.ofSeconds(20)));

      // With the constructor's 200ms in force this would have been destroyed at once.
      assertThat(output.succeeded()).isTrue();
      assertThat(output.stdout()).contains("finished");
    }

    @Test
    void beats_the_runners_default_when_it_is_shorter() {
      ProcessRunner runner = new ProcessRunner(Duration.ofMinutes(10));
      List<String> argv = sh("sleep 60");

      CommandRunner.Output output =
          assertTimeoutPreemptively(PATIENCE, () -> runner.run(argv, Duration.ofSeconds(1)));

      assertThat(output.succeeded()).isFalse();
      assertThat(output.stderr()).contains("timed out");
    }
  }

  /**
   * A command that asks a question has nobody to ask (final review, finding #4). Without this,
   * {@code apt-get upgrade} stopping at a conffile prompt would sit there until the timeout
   * destroyed it — mid-transaction, which is the outcome the longer timeout exists to avoid.
   */
  @Nested
  class The_childs_stdin {

    @Test
    void is_closed_so_a_prompt_reads_eof_instead_of_blocking() {
      // `read` returns non-zero at EOF, so this exits promptly with the marker on stdout. If stdin
      // were left open it would block until the two-second timeout destroyed the process.
      CommandRunner.Output output =
          run(Duration.ofSeconds(2), "read line; echo \"eof=$?\"; exit 0");

      assertThat(output.succeeded()).isTrue();
      assertThat(output.stdout()).contains("eof=1");
      assertThat(output.stderr()).doesNotContain("timed out");
    }

    @Test
    void gives_a_reader_nothing_rather_than_hanging_the_round() {
      CommandRunner.Output output = run(Duration.ofSeconds(5), "cat; echo done");

      assertThat(output.succeeded()).isTrue();
      assertThat(output.stdout()).contains("done");
    }
  }

  /** The apt path's other half: a frontend that will not stop to ask anything. */
  @Nested
  class The_child_environment {

    @Test
    void tells_debian_tooling_not_to_prompt() {
      CommandRunner.Output output = run(Duration.ofSeconds(5), "echo \"$DEBIAN_FRONTEND\"");

      assertThat(output.stdout().strip()).isEqualTo("noninteractive");
    }
  }

  @Nested
  class A_command_that_does_not_exist {

    @Test
    void comes_back_as_a_message_rather_than_an_exception() {
      ProcessRunner runner = new ProcessRunner(Duration.ofSeconds(5));
      List<String> argv = List.of("no-such-command-exists-here");

      CommandRunner.Output output = assertTimeoutPreemptively(PATIENCE, () -> runner.run(argv));

      assertThat(output.succeeded()).isFalse();
      assertThat(output.stderr()).isNotBlank();
    }
  }
}
