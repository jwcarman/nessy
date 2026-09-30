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
package org.jwcarman.nessy.inference.openai;

import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.RateLimitException;
import org.jwcarman.nessy.inference.Failure;

/**
 * What a failed OpenAI call means, for both adapters: the one thing this module knows and nothing
 * above it does.
 */
final class OpenAiFailures {

  private OpenAiFailures() {}

  /**
   * Decides what a failed call means, which is the one thing this class knows and nothing above it
   * does.
   *
   * <p>Grounded in the SDK's own retry classification: {@code
   * com.openai.core.http.RetryingHttpClient} retries a raw {@link java.io.IOException} or {@link
   * OpenAIRetryableException} unconditionally, and otherwise by status code (408, 409, 429, or any
   * 5xx) <em>before</em> the response is ever translated into a typed exception -- so by the time
   * one of those surfaces here, the SDK's own budget ({@code maxRetries}, default 2) is already
   * spent. What is still worth another attempt from further out is {@link RateLimitException},
   * {@link InternalServerException}, {@link OpenAIIoException} and {@link
   * OpenAIRetryableException}.
   *
   * <p>Everything else is {@link Failure.Permanent}: a 400, 401, 403, 404 or 422 means the request
   * itself is wrong, and repeating it unchanged only repeats the failure.
   *
   * <p><b>Nothing is classified {@link Failure.Rejected} here.</b> That is the one classification
   * that authorises throwing away something a person said, and it should rest on a measured marker
   * in a particular server's response rather than on a guess about what a 400 meant. None has been
   * measured on this wire, so none is claimed.
   */
  static Failure classify(OpenAIException e) {
    if (e instanceof RateLimitException
        || e instanceof InternalServerException
        || e instanceof OpenAIRetryableException) {
      return new Failure.Transient("model call failed: " + e.getMessage());
    }
    // Transport-level: the request may or may not have been processed before the connection
    // went. Unknown rather than transient, because repeating it is safe exactly when repeating
    // the work is safe, and that is not this class's call to make.
    if (e instanceof OpenAIIoException) {
      return new Failure.Unknown("no answer from the model: " + e.getMessage());
    }
    return new Failure.Permanent("model call failed: " + e.getMessage());
  }
}
