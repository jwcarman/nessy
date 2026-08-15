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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.StopReason;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.Usage;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole chat-web wiring, proved once: a message parks on a tool call, the page survives the
 * park, a human approves through {@link ApprovalController}, and the conversation completes (spec
 * §6). Requires Docker; tagged {@code container} so the offline default build never needs it.
 *
 * <p>The scripted {@link ModelProvider} below copies {@code ListenerDeclarationsTest}'s two-turn
 * pattern from {@code nessy-core}: first call emits a tool use plus {@code TOOL_USE}, second call
 * emits plain text plus {@code END_TURN}. {@link ChatWebConfig}'s {@code Harness} bean wins over
 * the starter's own {@code NessyAutoConfiguration}-supplied one by
 * {@code @ConditionalOnMissingBean}, built on that scripted provider over the same JDBC-backed
 * {@code ConversationStore} and real pipeline {@code Memory} beans the starter's persistence
 * autoconfiguration wires from this test's Testcontainers datasource — {@code ANTHROPIC_API_KEY}
 * never enters the picture, since the real {@link
 * org.jwcarman.nessy.model.anthropic.AnthropicModelProvider} bean is never constructed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Tag("container")
@Testcontainers
class ChatWebSmokeTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @DynamicPropertySource
  static void nessy_chat_web_smoke_test_points_the_datasource_at_the_container(
      DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.docker.compose.enabled", () -> false);
    registry.add("management.tracing.export.enabled", () -> false);
    registry.add("management.otlp.metrics.export.enabled", () -> false);
  }

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private ObjectMapper mapper;

  @Test
  void the_whole_durable_story_on_one_page() {
    String conversationId = ConversationId.generate().value();

    List<SseEvent> firstTurn =
        postMessage(conversationId, "I'd like a coupon for a bad experience, please.");

    assertThat(firstTurn).isNotEmpty();
    List<String> firstNames = firstTurn.stream().map(SseEvent::name).toList();
    assertThat(firstNames).contains("tool-requested", "tool-parked", "done");
    assertThat(firstNames.indexOf("tool-requested")).isLessThan(firstNames.indexOf("tool-parked"));
    assertThat(firstNames.getLast()).isEqualTo("done");
    SseEvent doneFirst = firstTurn.getLast();
    assertThat(doneFirst.payload().get("status").asText()).isEqualTo("PARKED");

    SseEvent approvalNeeded =
        firstTurn.stream().filter(e -> e.name().equals("tool-parked")).findFirst().orElseThrow();
    String token = approvalNeeded.payload().get("token").asText();
    assertThat(token).isNotBlank();

    JsonNode loadedAfterPark = getConversation(conversationId);
    assertThat(loadedAfterPark.get("status").asText()).isEqualTo("PARKED");
    JsonNode transcriptAfterPark = loadedAfterPark.get("transcript");
    assertThat(transcriptAfterPark)
        .isNotEmpty()
        .anyMatch(line -> "user".equals(line.get("role").asText()));
    JsonNode approvalsAfterPark = loadedAfterPark.get("approvals");
    assertThat(approvalsAfterPark)
        .isNotEmpty()
        .anyMatch(card -> token.equals(card.get("token").asText()));

    List<SseEvent> secondTurn = postApproval(token, "allow");

    assertThat(secondTurn).isNotEmpty();
    List<String> secondNames = secondTurn.stream().map(SseEvent::name).toList();
    assertThat(secondNames).contains("tool-decided", "tool-completed", "delta", "done");
    SseEvent doneSecond = secondTurn.getLast();
    assertThat(doneSecond.name()).isEqualTo("done");
    assertThat(doneSecond.payload().get("status").asText()).isEqualTo("COMPLETE");
    String assembledDelta =
        secondTurn.stream()
            .filter(e -> e.name().equals("delta"))
            .map(e -> e.payload().get("text").asText())
            .reduce("", String::concat);
    assertThat(assembledDelta).contains("coupon issued");

    JsonNode loadedAfterApproval = getConversation(conversationId);
    assertThat(loadedAfterApproval.get("status").asText()).isEqualTo("COMPLETE");
    assertThat(loadedAfterApproval.get("approvals")).isEmpty();
    JsonNode transcriptAfterApproval = loadedAfterApproval.get("transcript");
    assertThat(transcriptAfterApproval)
        .isNotEmpty()
        .anyMatch(
            line ->
                "assistant".equals(line.get("role").asText())
                    && line.get("text").asText().contains("coupon issued"));
  }

  private List<SseEvent> postMessage(String conversationId, String text) {
    HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("text", text));
    ResponseEntity<String> response =
        restTemplate.postForEntity(
            "/api/conversations/{id}/messages", request, String.class, conversationId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return parseSse(response.getBody());
  }

  private List<SseEvent> postApproval(String token, String decision) {
    HttpEntity<Map<String, String>> request = new HttpEntity<>(Map.of("decision", decision));
    ResponseEntity<String> response =
        restTemplate.postForEntity("/api/approvals/{token}", request, String.class, token);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return parseSse(response.getBody());
  }

  private JsonNode getConversation(String conversationId) {
    ResponseEntity<String> response =
        restTemplate.getForEntity("/api/conversations/{id}", String.class, conversationId);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return readTree(response.getBody());
  }

  /** One {@code event:}/{@code data:} pair out of a raw {@code text/event-stream} response body. */
  private record SseEvent(String name, JsonNode payload) {}

  private List<SseEvent> parseSse(String body) {
    List<SseEvent> events = new ArrayList<>();
    if (body == null || body.isBlank()) {
      return events;
    }
    for (String block : body.split("\n\n")) {
      if (block.isBlank()) {
        continue;
      }
      String name = null;
      StringBuilder data = new StringBuilder();
      for (String line : block.split("\n")) {
        if (line.startsWith("event:")) {
          name = line.substring("event:".length()).trim();
        } else if (line.startsWith("data:")) {
          data.append(line.substring("data:".length()).trim());
        }
      }
      if (name != null) {
        events.add(new SseEvent(name, readTree(data.toString())));
      }
    }
    return events;
  }

  private JsonNode readTree(String json) {
    try {
      return mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not parse SSE payload: " + json, e);
    }
  }

  /**
   * Supplies a single {@code Harness} bean built directly on {@link ScriptedModelProvider}, over
   * the same JDBC-backed store and real memory the starter's own persistence autoconfiguration
   * wires. This bean wins over {@code NessyAutoConfiguration}'s own by
   * {@code @ConditionalOnMissingBean(Harness.class)} — and its presence is also what now keeps
   * {@code AnthropicProviderAutoConfiguration}'s real {@code AnthropicModelProvider} from ever
   * being built (it would otherwise call {@code fromEnv().build()} and throw without {@code
   * ANTHROPIC_API_KEY}): both of that class's bean methods additionally back off via
   * {@code @ConditionalOnMissingBean({ModelProvider.class, Harness.class})} once a {@code Harness}
   * bean exists, since an application that brought its own harness has, by construction, already
   * brought its own provider too. No standalone {@link ModelProvider} bean is declared here at all
   * — this single-bean shape is D3's acceptance proof.
   */
  @TestConfiguration
  static class ChatWebConfig {

    @Bean
    Harness harness(ConversationStore store, ObservationRegistry observations) {
      return Nessy.harness(new ScriptedModelProvider())
          .store(store)
          .observations(observations)
          .build();
    }
  }

  /**
   * First call asks for {@code issue_coupon}; second call answers plainly. Copies {@code
   * ListenerDeclarationsTest}'s {@code ToolCallingProvider} pattern from {@code nessy-core}.
   */
  private static final class ScriptedModelProvider implements ModelProvider {

    // ChatController and ApprovalController each run their turn on a fresh virtual thread
    // (Thread.ofVirtual().start), so the two stream() calls in this scenario are not guaranteed to
    // land on the same thread — an AtomicInteger, not a bare field, is what makes the increment
    // visible and race-free across them.
    private final AtomicInteger calls = new AtomicInteger();

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
                      new ToolCall("c1", "issue_coupon", couponArguments())),
                  new ModelEvent.TurnEnded(StopReason.TOOL_USE, Usage.zero()))
              : List.of(
                  new ModelEvent.TextChunk("coupon issued, anything else?"),
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

    private static JsonNode couponArguments() {
      ObjectNode arguments = JsonNodeFactory.instance.objectNode();
      arguments.put("customerEmail", "grumpy@example.com");
      arguments.put("amountUsd", 10);
      arguments.put("reason", "bad experience");
      return arguments;
    }
  }
}
