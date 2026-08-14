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
package org.jwcarman.nessy.examples.watchman;

import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;

/**
 * The bound (spec §4): freedom of retention, rule of law at the border. Retention delegates whole
 * to {@link TranscriptMemory}; {@code recall} hands the loop only the last {@code window} messages
 * via {@link Context#keepRecent}, whose cut is pair-safe by construction — the trimmed context is
 * always wire-legal, no tool exchange ever split. The watchman's horizon is its window: it
 * remembers recent rounds, not its whole life, which is why an endless conversation cannot grow the
 * model call.
 */
public final class WindowedMemory implements Memory {

  private final Memory delegate = new TranscriptMemory(Transcript.inMemory());
  private final int window;

  public WindowedMemory(int window) {
    if (window < 1) {
      throw new IllegalArgumentException("window must be at least 1");
    }
    this.window = window;
  }

  @Override
  public void remember(ConversationId id, Message message) {
    delegate.remember(id, message);
  }

  @Override
  public Context recall(ConversationId id) {
    return delegate.recall(id).keepRecent(window);
  }
}
