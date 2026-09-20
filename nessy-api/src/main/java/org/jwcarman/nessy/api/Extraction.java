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
package org.jwcarman.nessy.api;

import java.util.Objects;

/**
 * What came back when a document was read for its fields.
 *
 * <p>Four outcomes rather than a value and an exception, because with untrusted input the other
 * three are ordinary. A model that declines to read a hostile document has behaved correctly; a
 * model that answers in prose instead of recording fields has not failed either, it has been talked
 * out of the job. Both are worth branching on, and neither is a stack trace.
 *
 * <p>The arms mirror the ways a call can end, typed: this is a view of one inference, not a new way
 * for one to finish.
 *
 * @param <T> the shape that was asked for
 */
public sealed interface Extraction<T> {

  /** What the call cost, whatever it ended as. */
  Usage usage();

  /** The fields, as the type they were asked for. */
  record Extracted<T>(T value, Usage usage) implements Extraction<T> {
    public Extracted {
      Objects.requireNonNull(value, "value must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The model would not read it, and said why in its own vocabulary.
   *
   * <p>Expected rather than exceptional: it is the outcome a document written to provoke one is
   * trying for, and the caller decides what a refused document means.
   */
  record Refused<T>(String category, Usage usage) implements Extraction<T> {
    public Refused {
      Objects.requireNonNull(category, "category must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The model answered instead of recording, so there are no fields to take.
   *
   * <p>Rare, because the call requires the tool, and worth its own arm anyway: a document that
   * talks a model out of the shape it was given is exactly what this pattern exists to notice.
   */
  record Talked<T>(String said, Usage usage) implements Extraction<T> {
    public Talked {
      Objects.requireNonNull(said, "said must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }

  /**
   * The call did not happen, or what came back could not be read as the shape asked for.
   *
   * <p>Says why in a sentence rather than carrying the provider's own failure type: what went wrong
   * is worth reading, and the vocabulary for it belongs below this.
   */
  record Failed<T>(String reason, Usage usage) implements Extraction<T> {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
