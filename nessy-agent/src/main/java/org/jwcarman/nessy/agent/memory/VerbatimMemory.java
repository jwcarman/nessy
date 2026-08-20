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
package org.jwcarman.nessy.agent.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.agent.spi.Memory;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;

/**
 * Remembers everything, verbatim, in order — the cli() default (§7.1). Thread-safe because
 * completions arrive on executor threads while the shell commits on others; a synchronized list is
 * entirely adequate at conversation cadence.
 */
public final class VerbatimMemory implements Memory {

  private final List<Message> messages = new ArrayList<>();

  @Override
  public synchronized void remember(Message message) {
    messages.add(Objects.requireNonNull(message, "message must not be null"));
  }

  @Override
  public synchronized Context recall() {
    return Context.of(List.copyOf(messages));
  }
}
