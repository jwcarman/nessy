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

import java.time.Duration;
import java.util.List;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.DirectBackend;
import org.jwcarman.nessy.backend.QueuedBackend;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.harness.queued.DefaultQueuedHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

/** Both doors, built the same way over whatever backend a test hands in, answering "ok". */
final class Doors {

  static final AgentType TYPE = new AgentType("after-commit");

  private Doors() {}

  private static InferenceProvider ok() {
    return (request, narrator) ->
        new InferenceResult.Answer(List.of(new Block.Text("ok")), Usage.unreported());
  }

  static DefaultDirectHarnessFactory directFactory(
      DirectBackend backend, NarrationListener listener) {
    return DefaultDirectHarnessFactory.of(
        f ->
            f.backend(backend)
                .provider(ProviderId.of("test"), ok())
                .schemas(new VictoolsJsonSchemaGenerator())
                .mapper(JsonMapper.builder().build())
                .listener(listener));
  }

  static DirectHarness<String, String> direct(DefaultDirectHarnessFactory factory) {
    return factory.<String>create(
        TYPE,
        c ->
            c.systemPrompt("You are terse.")
                .inputRenderer(said -> List.of(new Block.Text(said)))
                .inference(in -> in.provider("test").model("a-model")));
  }

  static DefaultQueuedHarnessFactory queuedFactory(
      QueuedBackend backend, NarrationListener listener) {
    return DefaultQueuedHarnessFactory.of(
        engine ->
            engine
                .backend(backend)
                .provider(ProviderId.of("test"), ok())
                .inference(ProviderId.of("test"), InferenceOptions.of("a-model"))
                .listener(listener));
  }

  static QueuedHarness<String> queued(DefaultQueuedHarnessFactory factory) {
    return factory.create(
        TYPE,
        String.class,
        c ->
            c.systemPrompt("You are terse.")
                .inference(in -> in.model("a-model"))
                .effects(e -> e.pollInterval(Duration.ofMillis(20))));
  }
}
