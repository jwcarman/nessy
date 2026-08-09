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
package org.jwcarman.nessy.api.event;

import java.util.Objects;
import org.jwcarman.nessy.api.SessionId;

/** A summarization call failed; compaction was skipped and the turn proceeds uncompacted. */
public record CompactionFailed(SessionId sessionId, String reason) {

  public CompactionFailed {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
  }
}
