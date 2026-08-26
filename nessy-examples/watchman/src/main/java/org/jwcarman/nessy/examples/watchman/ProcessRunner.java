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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link CommandRunner}: {@code ProcessBuilder}, a bounded wait, and both streams read
 * whole.
 *
 * <p>The only class in this module that touches the host, and therefore the only one no test
 * exercises — a test that ran this would be testing the JDK on whatever machine happened to run the
 * build. Everything above it is tested against a fake.
 *
 * <p>A command that exceeds {@code timeout} is destroyed and reported as a failure with a message
 * naming it. An agent doing rounds every half hour must never be able to wedge itself on a hung
 * {@code docker ps}.
 */
public final class ProcessRunner implements CommandRunner {

  private static final int COULD_NOT_RUN = -1;

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
      String stdout = drain(process.getInputStream());
      String stderr = drain(process.getErrorStream());
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new Output(COULD_NOT_RUN, stdout, "timed out after " + timeout + ": " + argv);
      }
      return new Output(process.exitValue(), stdout, stderr);
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

  private static String drain(InputStream stream) throws IOException {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
