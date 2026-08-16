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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Captures everything handed to it, so tests can assert on it. Wire it up as a declared listener —
 * {@code .listen(Object.class, recorder)} on a {@link org.jwcarman.nessy.HarnessConfig}/{@link
 * org.jwcarman.nessy.AgentConfig} — or as a conversation-local subscription via {@code
 * Conversation#events()}.
 */
public final class RecordingSubscriber implements Consumer<Object> {

  private final List<Object> received = new CopyOnWriteArrayList<>();

  @Override
  public void accept(Object event) {
    received.add(event);
  }

  public List<Object> all() {
    return Collections.unmodifiableList(received);
  }

  public <E> List<E> ofType(Class<E> type) {
    return received.stream().filter(type::isInstance).map(type::cast).toList();
  }
}
