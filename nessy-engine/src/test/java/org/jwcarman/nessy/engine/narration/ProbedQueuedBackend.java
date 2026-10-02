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
package org.jwcarman.nessy.engine.narration;

import org.jwcarman.codec.TypeRef;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.backend.agent.Agents;
import org.jwcarman.nessy.backend.backlog.Backlogs;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.effect.Effects;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/** A queued backend with its locks swapped for ones a test controls. */
public record ProbedQueuedBackend(QueuedBackend backend, Locks locks) implements QueuedBackend {

  @Override
  public AgentEvents events() {
    return backend.events();
  }

  @Override
  public Payloads payloads() {
    return backend.payloads();
  }

  @Override
  public Agents agents() {
    return backend.agents();
  }

  @Override
  public Effects effects() {
    return backend.effects();
  }

  @Override
  public Chapters chapters() {
    return backend.chapters();
  }

  @Override
  public Leases leases() {
    return backend.leases();
  }

  @Override
  public <I> Backlogs<I> backlogs(TypeRef<I> inputType) {
    return backend.backlogs(inputType);
  }
}
