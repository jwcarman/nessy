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
package org.jwcarman.nessy.engine.harness.direct;

import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * A {@link DirectBackend} over three stores a test already holds, so a test can keep asserting on
 * the exact {@link AgentEvents} and {@link Payloads} instance it built while still handing the
 * factory the one thing its config now takes.
 */
record FixedDirectBackend(Locks locks, AgentEvents events, Payloads payloads)
    implements DirectBackend {}
