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
package org.jwcarman.nessy.examples.watchman.pekko;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** The only place this application starts a subprocess. */
public final class ProcessRunner implements CommandRunner {

  @Override
  public Output run(List<String> argv, Duration timeout) {
    Process process;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(false).start();
    } catch (IOException e) {
      return new Output(127, "", "could not start " + argv + ": " + e.getMessage());
    }
    try {
      // Runs on a virtual thread (see BlockingWork), so blocking here costs a continuation,
      // not a platform thread -- and never a Pekko dispatcher thread.
      String stdout = read(process.getInputStream());
      String stderr = read(process.getErrorStream());
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new Output(124, stdout, "timed out after " + timeout);
      }
      return new Output(process.exitValue(), stdout, stderr);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Output(130, "", "interrupted");
    } catch (IOException e) {
      return new Output(126, "", "could not read output: " + e.getMessage());
    }
  }

  private static String read(InputStream stream) throws IOException {
    return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
