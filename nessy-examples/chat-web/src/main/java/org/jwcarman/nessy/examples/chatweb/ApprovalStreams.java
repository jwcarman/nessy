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
package org.jwcarman.nessy.examples.chatweb;

import java.time.Duration;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.narration.odyssey.AgentStreams;
import org.jwcarman.odyssey.core.Odyssey;
import org.jwcarman.odyssey.core.OdysseyStream;
import org.jwcarman.odyssey.core.TtlPolicy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The desk's questions, one stream per agent, beside the agent's own.
 *
 * <p>A card is the desk's to say, not the engine's, so it does not go on the engine's stream of
 * {@code AgentEvent}s; it goes on this one, typed as what it is. Journaled like the other, so a
 * page that opens after the question was asked still finds it -- and so does one that reconnects.
 */
@Component
public class ApprovalStreams {

  private static final TtlPolicy A_DAY =
      new TtlPolicy(Duration.ofDays(1), Duration.ofDays(1), Duration.ofHours(1));

  private final Odyssey odyssey;

  ApprovalStreams(Odyssey odyssey) {
    this.odyssey = odyssey;
  }

  private OdysseyStream<ApprovalDesk.Card> stream(AgentId agentId) {
    return odyssey.stream(
        AgentStreams.nameOf(ChatConfiguration.TYPE, agentId) + "/approvals",
        ApprovalDesk.Card.class,
        A_DAY);
  }

  public void asked(AgentId agentId, ApprovalDesk.Card card) {
    stream(agentId).publish("approval", card);
  }

  public SseEmitter resume(AgentId agentId, String lastEventId) {
    OdysseyStream<ApprovalDesk.Card> stream = stream(agentId);
    return lastEventId == null || lastEventId.isBlank()
        ? stream.subscribe()
        : stream.resume(lastEventId);
  }
}
