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
import java.util.concurrent.atomic.AtomicLong;
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
 */
final class Counting implements InferenceProvider {

  private final InferenceProvider provider;
  private final AtomicLong input = new AtomicLong();
  private final AtomicLong output = new AtomicLong();

  Counting(InferenceProvider provider) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    InferenceResult result = provider.infer(request, narrator);
    Usage usage = result.usage();
    add(input, usage.inputTokens());
    add(output, usage.outputTokens());
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

  /** The input tokens reported so far. */
  long inputTokens() {
    return input.get();
  }

  /** The output tokens reported so far. */
  long outputTokens() {
    return output.get();
  }

  private static void add(AtomicLong total, Tokens tokens) {
    if (tokens instanceof Tokens.Counted(int count)) {
      total.addAndGet(count);
    }
  }
}
