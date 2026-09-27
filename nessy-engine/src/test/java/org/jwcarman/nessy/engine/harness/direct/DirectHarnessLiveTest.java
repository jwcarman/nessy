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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.TypeRef;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Customizer;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.DirectHarnessConfig;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.schema.VictoolsInputSchemaGenerator;
import org.jwcarman.nessy.inference.openai.OpenAiInferenceProvider;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a real model actually answers in the shape the harness asked for.
 *
 * <p>The offline tests drive a provider that was told what to say, which proves the request carries
 * the schema and nothing about whether a model honours it. A vendor that ignores the constraint, or
 * a model whose support is per-model, passes offline and fails here.
 *
 * <p><b>Skipped, not failed, when nothing is serving.</b> Points at an OpenAI-compatible endpoint,
 * LM Studio's by default, so it costs nothing and needs no key:
 *
 * <pre>{@code
 * ./mvnw -pl :nessy-engine test -Dnessy.excludedGroups= -Dtest=DirectHarnessLiveTest
 * }</pre>
 *
 * <p>Against OpenAI instead: {@code CHAT_MODEL_URL=https://api.openai.com/v1
 * CHAT_MODEL_ID=gpt-4o-mini OPENAI_API_KEY=...}
 */
@Tag("live")
class DirectHarnessLiveTest {

  private static final AgentType TYPE = new AgentType("chat");
  private static final int MAX_TAIL = 50;

  private static final String BASE_URL =
      System.getenv().getOrDefault("CHAT_MODEL_URL", "http://localhost:1234/v1");
  private static final String MODEL =
      System.getenv().getOrDefault("CHAT_MODEL_ID", "qwen/qwen3.6-35b-a3b");

  /** Narrow on purpose: a model that answers around this has visibly not been constrained. */
  record Capital(String city, String country) {}

  record Answer(String answer, boolean confident) {}

  private static DefaultDirectHarnessFactory factory() {
    assumeTrue(serving(), "no OpenAI-compatible endpoint at " + BASE_URL);
    ObjectMapper mapper = JsonMapper.builder().build();
    return DefaultDirectHarnessFactory.of(
        config ->
            config
                .backend(new InMemoryDirectBackend(new JacksonCodecFactory(mapper)))
                .provider(OpenAiInferenceProvider.of(c -> c.apiKey(key()).baseUrl(BASE_URL)))
                .schemas(new VictoolsInputSchemaGenerator())
                .mapper(mapper));
  }

  private static Customizer<DirectHarnessConfig<String>> config() {
    return c ->
        c.systemPrompt("You are a terse assistant.")
            .inputRenderer(said -> List.of(new Block.Text(said)))
            .inference(in -> in.model(MODEL).maxTokens(4096));
  }

  /** Asks nothing of the answer's shape: no schema, prose back. */
  private static DirectHarness<String, String> harness() {
    return factory().<String>create(TYPE, config());
  }

  /** Bound to a shape at creation, the way every harness now is. */
  private static <O> DirectHarness<String, O> harness(Class<O> answers) {
    return factory().create(TYPE, answers, config());
  }

  private static <O> DirectHarness<String, O> harness(TypeRef<O> answers) {
    return factory().create(TYPE, answers, config());
  }

  private static String key() {
    return System.getenv().getOrDefault("OPENAI_API_KEY", "not-needed");
  }

  @Test
  @DisplayName("a model answers in the shape it was asked for, and the harness hands back the type")
  void an_answer_comes_back_as_the_type_that_was_asked_for() {
    Outcome<Capital> outcome =
        harness(Capital.class).ask(AgentId.random(), "What is the capital of France?");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
    Capital capital = ((Outcome.Answered<Capital>) outcome).value();
    assertThat(capital.city()).containsIgnoringCase("Paris");
    assertThat(capital.country()).containsIgnoringCase("France");
  }

  /** Asking for a shape the question fits badly still comes back as that shape. */
  @Test
  @DisplayName("the shape is honoured even when the question fits it badly")
  void the_shape_survives_a_question_that_does_not_suit_it() {
    Outcome<Answer> outcome = harness(Answer.class).ask(AgentId.random(), "Tell me a joke.");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
    assertThat(((Outcome.Answered<Answer>) outcome).value().answer()).isNotBlank();
  }

  /** A TypeRef carries what a Class cannot, and the parse has to survive the whole way back. */
  @Test
  @DisplayName("a collection asked for through a TypeRef comes back parsed")
  void a_collection_comes_back_parsed() {
    Outcome<List<Capital>> outcome =
        harness(new TypeRef<List<Capital>>() {})
            .ask(
                AgentId.random(),
                "Give me the capitals of France and Japan as a JSON array of "
                    + "objects with city and country.");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
    assertThat(((Outcome.Answered<List<Capital>>) outcome).value()).hasSizeGreaterThanOrEqualTo(1);
  }

  /** Asking for nothing in particular still works, and is still prose. */
  @Test
  @DisplayName("prose is still prose when no shape is asked for")
  void prose_still_works() {
    Outcome<String> outcome = harness().ask(AgentId.random(), "What is the capital of France?");

    assertThat(outcome).isInstanceOf(Outcome.Answered.class);
    assertThat(((Outcome.Answered<String>) outcome).value()).containsIgnoringCase("Paris");
  }

  private static boolean serving() {
    try {
      HttpResponse<Void> response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(2))
              .build()
              .send(
                  HttpRequest.newBuilder(URI.create(BASE_URL + "/models"))
                      .timeout(Duration.ofSeconds(2))
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (IOException _) {
      return false;
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
