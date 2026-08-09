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

import org.jwcarman.nessy.api.Event;
import org.jwcarman.nessy.api.SessionId;
import org.jwcarman.nessy.api.SessionState;

/**
 * Every front-end's window into the loop.
 *
 * <p>Called synchronously as each event is reduced, so a TUI paints tokens as they arrive.
 * Implementations must be quick and must not throw: this is a notification channel, not a control
 * point. The approver is where control lives.
 */
public interface AgentEventListener {

  void onEvent(SessionId id, Event event, SessionState state);
}
