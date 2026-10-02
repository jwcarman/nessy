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
package org.jwcarman.nessy.backend;

import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * Everything the direct door needs from underneath, chosen together so that they agree.
 *
 * <p>A backend is one unit of configuration rather than independent beans, because its stores
 * (events, payloads, locks, chapters and leases) have to be the same kind of thing or the door
 * breaks in ways nothing warns about: durable events over in-memory payloads is a story of
 * references to content that no longer exists after a restart, and JDBC stores paired with
 * in-memory locks are correct in one process and silently wrong the moment a second one exists.
 * Handing a caller one object closes the combinatorial surface a config offering {@code
 * .events(x).payloads(y).locks(z)} would open.
 */
public interface DirectBackend {

  AgentEvents events();

  Payloads payloads();

  Locks locks();

  Chapters chapters();

  Leases leases();
}
