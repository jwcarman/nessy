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
package org.jwcarman.nessy.api.tool;

import java.util.Objects;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.event.ToolProgress;

/** What a tool learns about the invocation it is serving. */
public record ToolContext(ToolCall call, EventEmitter events) {

  public ToolContext {
    Objects.requireNonNull(call, "call must not be null");
    Objects.requireNonNull(events, "events must not be null");
  }

  /**
   * Reports progress from inside a long-running tool. The framework supplies both ids — a tool
   * cannot know its provider-assigned call id, and no longer needs to (spec §2: nothing untrusted
   * arrives, so there is nothing to distrust).
   */
  public void progress(String message) {
    events.emit(new ToolProgress(call.id(), message));
  }
}
