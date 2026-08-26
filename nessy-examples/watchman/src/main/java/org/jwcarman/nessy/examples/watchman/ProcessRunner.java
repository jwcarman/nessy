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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link CommandRunner}: {@code ProcessBuilder}, both streams drained concurrently, and a
 * bounded wait that can actually expire.
 *
 * <p>The only class in this module that touches the host, which is why {@link ProcessRunnerTest} is
 * the only test here that starts processes. Everything above it is tested against a fake.
 *
 * <h2>Why two drain threads, and why the wait comes first</h2>
 *
 * <p>The obvious shape — read stdout to EOF, then stderr, then {@code waitFor(timeout)} —
 * deadlocks, and did. A child that fills its stderr pipe buffer (~64KB) blocks on write, so it
 * never exits, so it never closes stdout, so the stdout read never returns, so {@code waitFor} is
 * never reached and the timeout below cannot fire. Nor is the flood necessary: a child that merely
 * holds stdout open for longer than the timeout has the same effect, because the read blocks before
 * the clock is ever consulted. Either way the calling thread is stuck forever — and a read-only
 * tool runs inline on a harness thread, so one stuck command ends the rounds permanently. That is
 * spec §3's "a round that never ends".
 *
 * <p>So: start the child, start a drain on each stream, and then {@code waitFor} the timeout FIRST.
 * The clock is consulted before anything can block on it, and neither pipe can fill while the other
 * is being read.
 *
 * <p><b>Not {@code redirectErrorStream(true)}</b>, which would also cure the deadlock in one line.
 * It cures it by destroying the distinction the callers depend on: {@link
 * CommandRunner.Output#text} falls back to stderr only when stdout is empty, {@code DiskUsage}
 * reports "df failed: &lt;stderr&gt;", and {@code UpdatesPending} hands the model stdout as a
 * package list. Merging would put apt's routine stderr chatter into that list as if it were
 * packages. Two threads is more code and the right answer.
 *
 * <p>Virtual threads, because each one is a single blocked read and nothing else.
 */
public final class ProcessRunner implements CommandRunner {

  private static final int COULD_NOT_RUN = -1;

  /**
   * How long to wait for the drains after the child is gone. They should already be at EOF — the
   * pipes close when the process dies — so this is a guard against a wedged reader, not a budget.
   */
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(5);

  private final Duration timeout;

  /**
   * @param timeout how long any one command may take before it is destroyed
   */
  public ProcessRunner(Duration timeout) {
    this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
  }

  @Override
  public Output run(List<String> argv) {
    Objects.requireNonNull(argv, "argv must not be null");
    if (argv.isEmpty()) {
      throw new IllegalArgumentException("argv must not be empty");
    }
    Process process = null;
    try {
      process = new ProcessBuilder(argv).start();
      Drain stdout = Drain.of(process.getInputStream());
      Drain stderr = Drain.of(process.getErrorStream());
      boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!exited) {
        process.destroyForcibly();
        // Whatever it managed to say before it was killed is still worth handing back: a command
        // that printed three lines and then hung has told you where it hung.
        return new Output(COULD_NOT_RUN, stdout.text(), "timed out after " + timeout + ": " + argv);
      }
      return new Output(process.exitValue(), stdout.text(), stderr.text());
    } catch (IOException e) {
      return new Output(COULD_NOT_RUN, "", e.getMessage() == null ? e.toString() : e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      if (process != null) {
        process.destroyForcibly();
      }
      return new Output(COULD_NOT_RUN, "", "interrupted while running " + argv);
    }
  }

  /**
   * One stream, read whole on its own thread.
   *
   * <p>Package-private rather than private so {@code ProcessRunnerTest} can drive it with a stream
   * whose read boundaries it CHOOSES. Through a real pipe the boundaries are the kernel's, not the
   * test's, so a test that merely runs a command emitting multi-byte text cannot reliably split a
   * character — it passes against the broken decoder too, which is worse than no test.
   */
  static final class Drain {

    private final Thread thread;
    private final StringBuilder text = new StringBuilder();

    private Drain(InputStream stream) {
      this.thread = Thread.ofVirtual().name("watchman-drain").start(() -> read(stream));
    }

    /**
     * Chunk by chunk rather than {@code readAllBytes}, so that a pipe torn down mid-read keeps what
     * it already produced. On the timeout path {@code destroyForcibly} closes this pipe under the
     * reader, and "whatever it managed to say first" has to survive that — {@code readAllBytes}
     * would throw and take the lot with it.
     *
     * <p><b>Chars, not bytes</b> (fix round, 2026-08-26). This used to read into a {@code byte[]}
     * and call {@code new String(buffer, 0, count, UTF_8)} per chunk, which silently corrupts every
     * multi-byte character that straddles a chunk boundary — the trailing bytes of one chunk and
     * the leading bytes of the next each decode to U+FFFD. At an 8 KiB buffer that is roughly one
     * mangled character per 8 KiB of non-ASCII output, and {@code journalctl} is exactly the
     * realistic victim: its output is long, and it is full of the UTF-8 quotes and dashes systemd
     * writes. The corruption then reached the model as fact. An {@link InputStreamReader} holds the
     * partial sequence across reads and decodes it once it is complete, which is the whole reason
     * it exists; the incremental keep-what-you-got property is unchanged, because this still reads
     * a chunk at a time rather than the whole stream at once.
     */
    private void read(InputStream stream) {
      char[] buffer = new char[4096];
      try (Reader in = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        int count = in.read(buffer);
        while (count >= 0) {
          synchronized (text) {
            text.append(buffer, 0, count);
          }
          count = in.read(buffer);
        }
      } catch (IOException e) {
        // Deliberately swallowed. The pipe dying under a destroyForcibly IS the timeout path, not a
        // fault, and whatever was appended before it closed has already been kept. There is no
        // caller to rethrow to: this runs on a thread whose only product is the text below.
      }
    }

    static Drain of(InputStream stream) {
      return new Drain(stream);
    }

    /**
     * Everything the stream produced, after giving its reader a bounded chance to finish. A reader
     * that has not finished within {@link #DRAIN_GRACE} is abandoned rather than waited on — the
     * whole point of this class is that nothing here can block forever.
     */
    String text() throws InterruptedException {
      thread.join(DRAIN_GRACE);
      synchronized (text) {
        return text.toString();
      }
    }
  }
}
