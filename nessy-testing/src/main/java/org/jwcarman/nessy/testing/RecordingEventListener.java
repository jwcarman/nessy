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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jwcarman.nessy.core.Event;
import org.jwcarman.nessy.core.SessionId;
import org.jwcarman.nessy.core.SessionState;
import org.jwcarman.nessy.engine.AgentEventListener;

/** Captures everything the loop emitted, so tests can assert on it. */
public final class RecordingEventListener implements AgentEventListener {

  private final List<Event> events = new ArrayList<>();
  private final List<SessionState> states = new ArrayList<>();

  @Override
  public void onEvent(SessionId id, Event event, SessionState state) {
    events.add(event);
    states.add(state);
  }

  public List<Event> events() {
    return Collections.unmodifiableList(events);
  }

  public List<SessionState> states() {
    return Collections.unmodifiableList(states);
  }
}
