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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The wait between {@code tell} and the first token: a {@code \r}-based spinner on its own virtual
 * thread, erased the moment something worth showing arrives. Never a raw-mode affordance — one
 * carriage return and an overwrite, the same SGR-only covenant {@link Ansi} keeps (design §1).
 *
 * <p>{@link #start()} is a complete no-op when {@link Ansi#enabled()} is false: a piped consumer (a
 * file redirect, a CI log) sees zero spinner bytes, not merely an invisible one.
 */
final class Spinner {

  private static final char[] FRAMES = {'|', '/', '-', '\\'};
  private static final long FRAME_MILLIS = 80L;

  private final Writer writer;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Thread thread;

  Spinner(Writer writer) {
    this.writer = writer;
  }

  /** Starts spinning on a fresh virtual thread; a no-op while styling is disabled. */
  void start() {
    if (!Ansi.enabled()) {
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    thread = Thread.ofVirtual().name("nessy-console-spinner").start(this::spin);
  }

  /** Stops the spinner, if running, and erases its last frame. Idempotent. */
  void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }
    thread.interrupt();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    write("\r \r");
  }

  private void spin() {
    int frame = 0;
    while (running.get()) {
      write("\r" + FRAMES[frame % FRAMES.length]);
      frame++;
      try {
        Thread.sleep(FRAME_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void write(String text) {
    try {
      writer.write(text);
      writer.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
