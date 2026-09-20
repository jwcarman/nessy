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

  /**
   * The fields, as the type they were asked for.
   *
   * <p>What the document claimed, in the shape it was asked for. Not what is true: the shape was
   * enforced and the content was not, so this is something to check against a trusted source before
   * anything is done on the strength of it.
   */
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
   * <p>Not rare. Requiring a tool is a request, and a server may not enforce it: measured against
   * LM Studio on 2026-09-20, a model shown a document with none of the fields in it answered in
   * prose five times out of six rather than recording nothing. That is the useful answer -- it says
   * which fields were missing -- and it is why this is an arm rather than a fault.
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
   *
   * <p><b>The reason may quote what the model produced.</b> A type refusing its own invariant says
   * which value it refused, and that value came from the document. Treat it as the untrusted text
   * it is: worth logging, not worth rendering anywhere it could be read as markup.
   */
  record Failed<T>(String reason, Usage usage) implements Extraction<T> {
    public Failed {
      Objects.requireNonNull(reason, "reason must not be null");
      Objects.requireNonNull(usage, "usage must not be null");
    }
  }
}
