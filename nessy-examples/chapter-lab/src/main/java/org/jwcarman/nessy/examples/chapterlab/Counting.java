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
package org.jwcarman.nessy.examples.chapterlab;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A provider that adds up what the calls through it cost, as the vendor reported it, and otherwise
 * is the provider it wraps. A call whose usage the vendor did not report adds nothing.
 *
 * <p>Two of these around one provider keep two ledgers: what writing the chapters cost, and what
 * answering and grading cost.
 *
 * <p>The cache counts are a breakdown of the input, as they are in {@link Usage}. A vendor that
 * reports neither leaves them uncounted, which is not the same as nothing having been cached.
 */
final class Counting implements InferenceProvider {

  private final InferenceProvider provider;
  private final AtomicReference<Spent> spent =
      new AtomicReference<>(new Spent(Tokens.none(), Tokens.none(), Tokens.none(), Tokens.none()));

  /**
   * What the calls so far came to.
   *
   * @param input all the input processed, cached tokens included
   * @param output the output
   * @param cacheRead the part of the input read from the vendor's cache
   * @param cacheWrite the part of the input written to the vendor's cache
   */
  record Spent(Tokens input, Tokens output, Tokens cacheRead, Tokens cacheWrite) {

    Spent plus(Usage usage) {
      return new Spent(
          input.plus(usage.inputTokens()),
          output.plus(usage.outputTokens()),
          cacheRead.plus(usage.cacheReadTokens()),
          cacheWrite.plus(usage.cacheWriteTokens()));
    }

    /** Whether any call said how much of its input was read from or written to a cache. */
    boolean cacheReported() {
      return cacheRead instanceof Tokens.Counted || cacheWrite instanceof Tokens.Counted;
    }

    /** A count as a number, nothing reported being none. */
    static long count(Tokens tokens) {
      return tokens instanceof Tokens.Counted(int count) ? count : 0;
    }
  }

  Counting(InferenceProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    InferenceResult result = provider.infer(request, narrator);
    Usage usage = result.usage();
    spent.updateAndGet(sofar -> sofar.plus(usage));
    return result;
  }

  @Override
  public void validate(InferenceOptions options) {
    provider.validate(options);
  }

  @Override
  public String vendor() {
    return provider.vendor();
  }

  /** What the calls through this provider have come to so far. */
  Spent spent() {
    return spent.get();
  }
}
