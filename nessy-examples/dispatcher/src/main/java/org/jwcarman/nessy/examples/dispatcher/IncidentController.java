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
package org.jwcarman.nessy.examples.dispatcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.message.Context;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The page-rebuild read (spec §2, §3): status, every open park's {@code (token, tool)} pair, and
 * the transcript — the second of the two places the park token surfaces (the app log, via {@link
 * IncidentLog}, is the first), and the one the demo script's restart scene relies on once the log
 * line is gone (spec §3's last bullet).
 */
@RestController
public final class IncidentController {

  private final Agent<String> agent;

  public IncidentController(Agent<String> agent) {
    this.agent = agent;
  }

  @GetMapping("/incidents/{id}")
  public Map<String, Object> get(@PathVariable String id) {
    ConversationSnapshot snapshot = agent.snapshot(new ConversationId("incident-" + id));
    List<Map<String, String>> parks =
        snapshot.parkedCalls().stream().map(IncidentController::park).toList();
    List<Context.Line> transcript = snapshot.context().lines();
    // LinkedHashMap, not Map.of: this module's own README shows a fixed field order
    // (status, parks, transcript) in its sample response — Map.of's iteration order is
    // unspecified (and randomized run to run), which would make that sample dishonest.
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("status", snapshot.status().name());
    body.put("parks", parks);
    body.put("transcript", transcript);
    return body;
  }

  private static Map<String, String> park(ParkedCall parked) {
    Map<String, String> card = new LinkedHashMap<>();
    card.put("token", parked.token().value());
    card.put("tool", parked.call().name());
    return card;
  }
}
