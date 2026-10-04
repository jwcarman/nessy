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
package org.jwcarman.nessy.engine.story;

import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** One scripted turn on the direct door, and the story events a listener heard while it ran. */
final class StoryTurn {

  private StoryTurn() {}

  /**
   * Runs one turn that answers "ok" and returns the story events the listener heard, oldest first.
   */
  static List<Narrated> heard(AgentType type, AgentId agent, DirectBackend backend, Clock clock) {
    List<Narrated> heard = new CopyOnWriteArrayList<>();
    NarrationListener recording = heard::add;
    try (DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(backend)
                    .provider(
                        ProviderId.of("test"),
                        (request, narrator) ->
                            new InferenceResult.Answer(
                                List.of(new Block.Text("ok")), Usage.unreported()))
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(JsonMapper.builder().build())
                    .clock(clock)
                    .listener(recording))) {
      DirectHarness<String, String> harness =
          factory.<String>create(
              type,
              c ->
                  c.systemPrompt("You are terse.")
                      .inputRenderer(said -> List.of(new Block.Text(said)))
                      .inference(in -> in.provider("test").model("a-model")));
      harness.ask(agent, "hello");
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                heard.stream()
                    .anyMatch(
                        narrated ->
                            narrated.event() instanceof Narration.Answered
                                && narrated.agentId().equals(agent)));
    return heard.stream().filter(narrated -> narrated.event() instanceof Narration.Story).toList();
  }
}
