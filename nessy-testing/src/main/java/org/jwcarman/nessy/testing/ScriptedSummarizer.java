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
package org.jwcarman.nessy.testing;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.spi.compaction.Summarizer;

/**
 * A summarizer that says exactly what you told it to.
 *
 * <p>Mirrors {@link ScriptedModelProvider}'s idiom: a queue of scripted outcomes, one per call,
 * consumed in order and failing loudly rather than silently once exhausted. It also records every
 * head it was handed, so tests can assert on what the strategy chose to summarize.
 */
public final class ScriptedSummarizer implements Summarizer {

  private final List<Outcome> script;
  private final List<Context> heads = new ArrayList<>();
  private int next;

  private ScriptedSummarizer(List<Outcome> script) {
    this.script = List.copyOf(script);
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public String summarize(Context head) {
    if (next >= script.size()) {
      throw new IllegalStateException(
          "script exhausted: the harness asked for summarization "
              + (next + 1)
              + " of "
              + script.size());
    }
    heads.add(head);
    return script.get(next++).resolve();
  }

  /** Every head this summarizer was handed, oldest first. */
  public List<Context> heads() {
    return List.copyOf(heads);
  }

  private sealed interface Outcome {

    String resolve();

    record Ok(String text) implements Outcome {
      @Override
      public String resolve() {
        return text;
      }
    }

    record Throwing(RuntimeException exception) implements Outcome {
      @Override
      public String resolve() {
        throw exception;
      }
    }
  }

  public static final class Builder {

    private final List<Outcome> script = new ArrayList<>();

    public Builder summary(String text) {
      script.add(new Outcome.Ok(text));
      return this;
    }

    /** The next call throws {@code exception} instead of returning a summary. */
    public Builder throwing(RuntimeException exception) {
      script.add(new Outcome.Throwing(exception));
      return this;
    }

    public ScriptedSummarizer build() {
      return new ScriptedSummarizer(script);
    }
  }
}
