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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessy.inference.Toolset;

/**
 * Calls a model the way the lab always does: one request, no tools, nothing streamed, and the text
 * of the answer back.
 *
 * <p>A call that fails is tried again a few times, a pause longer each time, because a run of
 * hundreds of calls will meet a rate limit; after the last attempt it fails with what went wrong,
 * so a run never goes on with an answer nobody wrote.
 */
final class Models {

  /** How many times a call is made before the run gives up on it. */
  static final int ATTEMPTS = 4;

  /** The pause before the second attempt; each later pause is one longer. */
  static final Duration DEFAULT_PAUSE = Duration.ofSeconds(3);

  private Models() {}

  /** A turn nobody has answered yet, whose input is {@code text}. */
  static Turn asking(long id, String text) {
    return new Turn(
        new TurnId(id), new Input(new Seq(id), List.of(new Block.Text(text))), List.of(), null, 0);
  }

  /** A request whose whole context is one question under a system prompt. */
  static InferenceRequest ask(String system, String question, InferenceOptions options) {
    return new InferenceRequest(
        new SystemPrompt(system),
        InferenceContext.of(List.of(asking(1, question))),
        Toolset.none(),
        options);
  }

  /** The text of the answer to {@code request}, trying again if the model did not answer. */
  static String text(InferenceProvider provider, InferenceRequest request) {
    return text(provider, request, DEFAULT_PAUSE);
  }

  /**
   * As below, but a call that still has not answered after every attempt is an empty result rather
   * than a failure, for a question or a grade, where one refusal must not lose the whole run.
   */
  static Optional<String> tryText(
      InferenceProvider provider, InferenceRequest request, Duration pause) {
    try {
      return Optional.of(text(provider, request, pause));
    } catch (IllegalStateException gaveUp) {
      return Optional.empty();
    }
  }

  static String text(InferenceProvider provider, InferenceRequest request, Duration pause) {
    return retrying(
        () -> {
          InferenceResult result = provider.infer(request);
          if (result instanceof InferenceResult.Answer(var blocks, _)) {
            return Transcripts.text(blocks).strip();
          }
          throw new IllegalStateException("the model did not answer: " + result);
        },
        pause);
  }

  /**
   * What {@code call} returns, calling it again if it throws, up to {@link #ATTEMPTS} times in all.
   *
   * @throws IllegalStateException when every attempt failed, naming the last failure
   */
  static <T> T retrying(Supplier<T> call) {
    return retrying(call, DEFAULT_PAUSE);
  }

  static <T> T retrying(Supplier<T> call, Duration pause) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
      try {
        return call.get();
      } catch (RuntimeException failed) {
        last = failed;
        if (attempt < ATTEMPTS) {
          sleep(pause.multipliedBy(attempt));
        }
      }
    }
    throw new IllegalStateException(
        "gave up after %d attempts: %s".formatted(ATTEMPTS, last.getMessage()), last);
  }

  private static void sleep(Duration pause) {
    try {
      Thread.sleep(pause);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting to ask the model again");
    }
  }
}
