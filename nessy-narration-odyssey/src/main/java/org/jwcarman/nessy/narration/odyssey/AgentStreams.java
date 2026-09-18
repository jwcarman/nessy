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
package org.jwcarman.nessy.narration.odyssey;

import java.util.Objects;
import org.jwcarman.nessy.api.AgentEvent;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.odyssey.core.OdysseyStream;
import org.jwcarman.odyssey.core.TtlPolicy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * One Odyssey stream of {@link AgentEvent}s per agent instance, named {@code nessy/<type>/<id>}.
 *
 * <p>The narrator publishes through it and a page subscribes or resumes through it. It carries the
 * engine's events and nothing else: an application with things of its own to say about an agent
 * says them on a stream of its own, typed for what they are, rather than smuggling them in here as
 * something they are not.
 */
public class AgentStreams {

  private final Odyssey odyssey;
  private final TtlPolicy ttl;

  public AgentStreams(Odyssey odyssey, TtlPolicy ttl) {
    this.odyssey = Objects.requireNonNull(odyssey, "odyssey must not be null");
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
  }

  /** The stream's name: the type and the id, because an id names an agent only within its type. */
  public static String nameOf(AgentType agentType, AgentId agentId) {
    return "nessy/" + agentType.value() + "/" + agentId.value();
  }

  public OdysseyStream<AgentEvent> stream(AgentType agentType, AgentId agentId) {
    return odyssey.stream(nameOf(agentType, agentId), AgentEvent.class, ttl);
  }

  /** From now on. */
  public SseEmitter subscribe(AgentType agentType, AgentId agentId) {
    return stream(agentType, agentId).subscribe();
  }

  /**
   * From after the event a browser last saw, which it hands back as {@code Last-Event-ID} when it
   * reconnects; from now on when it has seen nothing.
   */
  public SseEmitter resume(AgentType agentType, AgentId agentId, String lastEventId) {
    OdysseyStream<AgentEvent> stream = stream(agentType, agentId);
    return lastEventId == null || lastEventId.isBlank()
        ? stream.subscribe()
        : stream.resume(lastEventId);
  }
}
