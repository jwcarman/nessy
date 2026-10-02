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
package org.jwcarman.nessy.backend.inmemory;

import org.jwcarman.codec.CodecFactory;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.backend.lock.Locks;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * A {@link DirectBackend} over {@link InMemoryLocks}, {@link InMemoryAgentEvents}, {@link
 * InMemoryPayloads}, {@link InMemoryChapters} and {@link InMemoryLeases}: everything in one process
 * and nothing written down.
 *
 * <p>For a CLI, a test, or a one-shot -- the whole reason {@code
 * DefaultDirectHarnessFactory.inMemory(...)} is one line: nothing here is durable, and nothing here
 * needs to be, because the process IS the conversation.
 */
public final class InMemoryDirectBackend implements DirectBackend {

  private final Locks locks = new InMemoryLocks();
  private final Leases leases = new InMemoryLeases();
  private final AgentEvents events;
  private final Payloads payloads;
  private final Chapters chapters;

  /**
   * The same factory every other backend is handed, so a transform an application configures
   * applies here too. Storing bytes rather than the caller's own objects is what lets this stand in
   * for a durable backend in a test without standing in for a kinder one.
   */
  public InMemoryDirectBackend(CodecFactory codecs) {
    this.events = new InMemoryAgentEvents(codecs);
    this.payloads = new InMemoryPayloads(codecs);
    this.chapters = new InMemoryChapters(codecs);
  }

  @Override
  public AgentEvents events() {
    return events;
  }

  @Override
  public Payloads payloads() {
    return payloads;
  }

  @Override
  public Locks locks() {
    return locks;
  }

  @Override
  public Chapters chapters() {
    return chapters;
  }

  @Override
  public Leases leases() {
    return leases;
  }
}
