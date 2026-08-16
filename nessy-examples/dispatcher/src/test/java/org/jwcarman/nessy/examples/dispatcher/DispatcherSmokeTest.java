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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.event.ToolProgress;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.ToolResultBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.conversation.Parks;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole dispatcher story, proved once end to end (spec §4): a signal parks on {@code
 * request_field_crew}, the park token surfaces on the incident snapshot, a progress callback
 * narrates, a completion callback resumes to {@code COMPLETE}, a duplicate completion replays
 * nothing, and both a malformed signal and an unknown token refuse cleanly. Requires Docker; tagged
 * {@code container} so the offline default build never needs it.
 *
 * <p>The scripted {@link ModelProvider} copies {@code chat-web}'s {@code ChatWebSmokeTest} pattern:
 * first call emits a tool use plus {@code TOOL_USE}, second call emits plain text plus {@code
 * END_TURN} — here the second call additionally echoes the crew's outcome text back out of the
 * request's own {@link Context} (the way a real model would quote a tool result), so the completion
 * assertion below can check the transcript actually carries the outcome, not a canned string.
 * {@link DispatcherTestConfig}'s {@code Harness} bean wins over the starter's own by
 * {@code @ConditionalOnMissingBean}, wired directly on {@link Parks} (not just {@link
 * ConversationStore}) so the durable-park machinery this module exists to demonstrate is real in
 * this test too, not merely in the by-hand restart demo.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Tag("container")
@Testcontainers
class DispatcherSmokeTest {

  private static final String INCIDENT_ID = "INC-7";
  private static final String OUTCOME = "valve replaced, water restored";

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void nessy_dispatcher_smoke_test_points_the_datasource_at_the_container(
      DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
  }

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private ObjectMapper mapper;

  @Autowired private Agent<String> agent;

  @Autowired private ScriptedModelProvider provider;

  @Test
  void the_two_doors_park_narrate_resume_and_refuse_replay() {
    List<ToolProgress> heard = Collections.synchronizedList(new ArrayList<>());
    agent
        .conversation(new ConversationId("incident-" + INCIDENT_ID))
        .events()
        .subscribe(ToolProgress.class, heard::add);

    ResponseEntity<String> malformed =
        restTemplate.postForEntity(
            "/signals", new HttpEntity<>(Map.of("incidentId", INCIDENT_ID)), String.class);
    assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<String> signalResponse =
        restTemplate.postForEntity(
            "/signals",
            new HttpEntity<>(
                Map.of(
                    "incidentId", INCIDENT_ID,
                    "kind", "water-main",
                    "detail", "corner of 5th")),
            String.class);
    assertThat(signalResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(readTree(signalResponse.getBody()).get("incident").asText()).isEqualTo(INCIDENT_ID);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(parkedCard()).containsKey("token"));
    Map<String, Object> parkedCard = parkedCard();
    String token = (String) parkedCard.get("token");
    assertThat(parkedCard).containsEntry("tool", "request_field_crew");
    assertThat(incidentSnapshot().get("status").asText()).isEqualTo("PARKED");

    assertBadInputRefused(token);

    ResponseEntity<String> progressResponse =
        restTemplate.postForEntity(
            "/callbacks/" + token + "/progress",
            new HttpEntity<>(Map.of("message", "crew en route")),
            String.class);
    assertThat(progressResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(progressResponse.getBody()).get("heard").asBoolean()).isTrue();
    assertThat(heard).isNotEmpty().anyMatch(event -> "crew en route".equals(event.message()));

    ResponseEntity<String> completion =
        restTemplate.postForEntity(
            "/callbacks/" + token, new HttpEntity<>(Map.of("outcome", OUTCOME)), String.class);
    assertThat(completion.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(completion.getBody()).get("status").asText()).isEqualTo("COMPLETE");

    JsonNode afterCompletion = incidentSnapshot();
    assertThat(afterCompletion.get("status").asText()).isEqualTo("COMPLETE");
    assertThat(afterCompletion.get("parks")).isEmpty();
    JsonNode transcript = afterCompletion.get("transcript");
    assertThat(transcript)
        .isNotEmpty()
        .anyMatch(
            line ->
                "assistant".equals(line.get("role").asText())
                    && line.get("text").asText().contains(OUTCOME));

    int callsAfterCompletion = provider.callCount();
    ResponseEntity<String> duplicateCompletion =
        restTemplate.postForEntity(
            "/callbacks/" + token, new HttpEntity<>(Map.of("outcome", OUTCOME)), String.class);
    assertThat(duplicateCompletion.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(duplicateCompletion.getBody()).get("status").asText())
        .isEqualTo("COMPLETE");
    await()
        .during(Duration.ofMillis(300))
        .atMost(Duration.ofSeconds(2))
        .until(() -> provider.callCount() == callsAfterCompletion);

    assertProgressAfterSettlementDropped(token);
  }

  /** The refusal surface, in one sweep: bad bodies are 400s, unknown tokens 404 or heard:false. */
  private void assertBadInputRefused(String token) {
    ResponseEntity<String> emptyOutcomeBody =
        restTemplate.postForEntity(
            "/callbacks/" + token, new HttpEntity<>(Map.of("outcome", "")), String.class);
    assertThat(emptyOutcomeBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<String> emptyProgressBody =
        restTemplate.postForEntity(
            "/callbacks/" + token + "/progress",
            new HttpEntity<>(Map.of("message", "")),
            String.class);
    assertThat(emptyProgressBody.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<String> unknownProgress =
        restTemplate.postForEntity(
            "/callbacks/does-not-exist/progress",
            new HttpEntity<>(Map.of("message", "crew en route")),
            String.class);
    assertThat(readTree(unknownProgress.getBody()).get("heard").asBoolean()).isFalse();

    ResponseEntity<String> unknownToken =
        restTemplate.postForEntity(
            "/callbacks/does-not-exist",
            new HttpEntity<>(Map.of("outcome", OUTCOME)),
            String.class);
    assertThat(unknownToken.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** Progress against a settled wait is dropped, legally: 200 with heard:false. */
  private void assertProgressAfterSettlementDropped(String token) {
    ResponseEntity<String> progressAfterSettlement =
        restTemplate.postForEntity(
            "/callbacks/" + token + "/progress",
            new HttpEntity<>(Map.of("message", "too late")),
            String.class);
    assertThat(progressAfterSettlement.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(readTree(progressAfterSettlement.getBody()).get("heard").asBoolean()).isFalse();
  }

  private Map<String, Object> parkedCard() {
    JsonNode parks = incidentSnapshot().get("parks");
    if (parks == null || parks.isEmpty()) {
      return Map.of();
    }
    JsonNode card = parks.get(0);
    return Map.of("token", card.get("token").asText(), "tool", card.get("tool").asText());
  }

  private JsonNode incidentSnapshot() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("/incidents/{id}", String.class, INCIDENT_ID);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return readTree(response.getBody());
  }

  private JsonNode readTree(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not parse JSON body: " + json, e);
    }
  }

  @TestConfiguration
  static class DispatcherTestConfig {

    @Bean
    ScriptedModelProvider scriptedModelProvider() {
      return new ScriptedModelProvider();
    }

    @Bean
    Harness harness(
        ScriptedModelProvider provider,
        ConversationStore store,
        Parks parks,
        ObjectProvider<ObservationRegistry> observations) {
      return Nessy.harness(
          h ->
              h.provider(provider)
                  .store(store)
                  .parks(parks)
                  .observations(observations.getIfAvailable(() -> ObservationRegistry.NOOP)));
    }
  }

  /**
   * First call asks for {@code request_field_crew}; second call answers with the crew's outcome
   * echoed straight out of the request's own transcript (the tool result the harness fed back in);
   * any later call answers with terse filler text — nothing in this scenario should ever reach a
   * third call, but a scripted provider that errors on the unexpected shape would fail the test for
   * the wrong reason.
   */
  static final class ScriptedModelProvider implements ModelProvider {

    private final AtomicInteger calls = new AtomicInteger();

    int callCount() {
      return calls.get();
    }

    @Override
    public Set<Capability> capabilities() {
      return Set.of();
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      List<ModelEvent> turn =
          calls.incrementAndGet() == 1
              ? List.of(
                  new ModelEvent.ToolUseEmitted(
                      new ToolCall("c1", "request_field_crew", crewArguments())),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              : List.of(
                  new ModelEvent.TextChunk("Resolved: " + latestToolResult(request.context())),
                  new ModelEvent.TurnEnded(StopReason.END_TURN, Usage.zero()));
      Iterator<ModelEvent> events = turn.iterator();
      return new ModelStream() {
        @Override
        public Iterator<ModelEvent> iterator() {
          return events;
        }

        @Override
        public void close() {
          // scripted stream holds no resources to release
        }
      };
    }

    private static JsonNode crewArguments() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("incidentId", INCIDENT_ID);
      arguments.put("action", "dispatch a crew");
      return arguments;
    }

    private static String latestToolResult(Context context) {
      List<Message> messages = context.messages();
      for (int i = messages.size() - 1; i >= 0; i--) {
        for (ContentBlock block : messages.get(i).content()) {
          if (block instanceof ToolResultBlock toolResult) {
            return toolResult.content();
          }
        }
      }
      // Called only on the second-or-later turn, by which point the harness has always already
      // fed the tool's result back in — this is the scripted double of a real model with nothing
      // to quote, not a reachable path here.
      return "(no tool result found)";
    }
  }
}
