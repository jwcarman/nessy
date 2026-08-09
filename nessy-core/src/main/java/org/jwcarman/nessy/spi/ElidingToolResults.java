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
package org.jwcarman.nessy.spi;

import java.util.ArrayList;
import java.util.List;
import org.jwcarman.nessy.api.ContentBlock;
import org.jwcarman.nessy.api.Message;
import org.jwcarman.nessy.api.SessionState;
import org.jwcarman.nessy.api.ToolResultBlock;

/**
 * Elides the content of tool results in every message older than the last {@code
 * keepRecentMessages}, keeping the recent window verbatim.
 *
 * <p>The sliding boundary rewrites one old message per turn as the window advances, churning the
 * prompt-cache prefix — elision trades cache hits for context space, which is why {@link
 * ContextBuilder#identity()} is the default.
 */
final class ElidingToolResults implements ContextBuilder {

  private static final String ELIDED = "[elided]";

  private final int keepRecentMessages;

  ElidingToolResults(int keepRecentMessages) {
    if (keepRecentMessages < 0) {
      throw new IllegalArgumentException("keepRecentMessages must be at least 0");
    }
    this.keepRecentMessages = keepRecentMessages;
  }

  @Override
  public List<Message> project(SessionState state) {
    List<Message> messages = state.messages();
    int firstRecentIndex = Math.max(0, messages.size() - keepRecentMessages);
    List<Message> projected = new ArrayList<>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      Message message = messages.get(i);
      projected.add(i < firstRecentIndex ? elide(message) : message);
    }
    return projected;
  }

  private static Message elide(Message message) {
    List<ContentBlock> content = message.content();
    List<ContentBlock> elided = new ArrayList<>(content.size());
    for (ContentBlock block : content) {
      elided.add(
          block instanceof ToolResultBlock toolResult
              ? new ToolResultBlock(toolResult.toolUseId(), ELIDED, toolResult.isError())
              : block);
    }
    return new Message(message.role(), elided);
  }
}
