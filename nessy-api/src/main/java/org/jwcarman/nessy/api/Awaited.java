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

/**
 * An answer, or a promise that one is coming from somewhere else.
 *
 * <p>The two ways anything the engine asks for can respond, and the only two. A tool that returns
 * in a millisecond and an approver that waits three days for a person are the same shape from here
 * -- which is deliberate, because the engine has no business knowing the difference. What changes
 * is only whether the answer is in hand yet.
 *
 * <p><b>{@link Deferred} is not "try again later".</b> It says the work is genuinely under way
 * somewhere else -- a person has been asked, a job is queued -- and that the answer will arrive
 * against the {@link org.jwcarman.nessy.api.tool.ReplyToken} that came with the request. Repeating
 * the request would ask twice, which for a person is pestering and for a tool may be worse.
 *
 * <p>Deferring carries an obligation: whatever defers must keep the reply address, because it is
 * the only thing that can settle that call. An answer that arrives after the request's deadline has
 * passed is refused -- the agent stopped waiting, and was told so.
 *
 * @param <T> what the answer will be, when there is one
 */
public sealed interface Awaited<T> {

  /** The answer, now. */
  record Ready<T>(T value) implements Awaited<T> {}

  /**
   * Not yet, and not from here.
   *
   * <p>Carries nothing -- not even how long it expects to take. The question's deadline was fixed
   * when it was asked, from the binding's own configuration, so a deferral cannot extend it and
   * there is nothing to negotiate.
   */
  record Deferred<T>() implements Awaited<T> {}

  static <T> Awaited<T> ready(T value) {
    return new Ready<>(value);
  }

  /** Somebody else will answer. Keep the reply address; nothing else can settle the call. */
  static <T> Awaited<T> deferred() {
    return new Deferred<>();
  }
}
