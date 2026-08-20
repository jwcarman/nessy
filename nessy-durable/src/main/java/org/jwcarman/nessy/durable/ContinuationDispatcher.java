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
package org.jwcarman.nessy.durable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps continuation types to handlers. The primitive stays generic: agent resumption is one
 * registered handler among possible many (preamble ruling 2). Unknown types at fire time are a
 * deployment bug and fail loudly, before any handler runs.
 */
public final class ContinuationDispatcher {

  private final ConcurrentMap<String, ContinuationHandler> handlers = new ConcurrentHashMap<>();

  public void register(String type, ContinuationHandler handler) {
    Objects.requireNonNull(type, "type must not be null");
    Objects.requireNonNull(handler, "handler must not be null");
    if (handlers.putIfAbsent(type, handler) != null) {
      throw new IllegalStateException("a handler is already registered for type: " + type);
    }
  }

  /**
   * A handler throw here leaves the token retired and the slot terminal — the lazy re-drive floor
   * (plan decision 3) covers delivery; the Plan-5 outbox is the prompt-delivery upgrade.
   */
  public void fire(List<Continuation> continuations, Outcome outcome) {
    Objects.requireNonNull(continuations, "continuations must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    List<ContinuationHandler> resolved = new ArrayList<>(continuations.size());
    for (Continuation continuation : continuations) {
      ContinuationHandler handler = handlers.get(continuation.type());
      if (handler == null) {
        throw new IllegalStateException(
            "no handler registered for continuation type: " + continuation.type());
      }
      resolved.add(handler);
    }
    for (int i = 0; i < continuations.size(); i++) {
      resolved.get(i).completed(continuations.get(i), outcome);
    }
  }
}
