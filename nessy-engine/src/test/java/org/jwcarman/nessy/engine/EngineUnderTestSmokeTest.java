package org.jwcarman.nessy.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * The engine, started and driven with no container around it.
 *
 * <p>Small on purpose. What it proves is not that a turn works — the other tests do that — but that
 * the engine can be constructed from a {@code DataSource} and nothing else, which is the claim its
 * POM makes and the reason none of these tests use Spring Boot.
 */
class EngineUnderTestSmokeTest {

  @Test
  void anEngineWiredByHandRunsATurn() {
    try (EngineFixture engine =
        new EngineFixture(
            (request, narrator) ->
                new InferenceResult.Answer(
                    HistoryEntry.InferenceAnswered.text("It is 1412 metres deep.")))) {

      AgentId agentId = new AgentId(UUID.randomUUID());
      AgentType type = new AgentType("smoke");
      Harness<String> harness =
          engine
              .harnesses()
              .create(
                  String.class,
                  config ->
                      config
                          .agentType(type)
                          .systemPrompt("You are a test assistant.")
                          .effects(e -> e.pollInterval(Duration.ofMillis(50))));

      harness.observe(agentId, "how deep is Loch Ness?");

      await()
          .atMost(Duration.ofSeconds(20))
          .untilAsserted(
              () ->
                  assertThat(engine.history().entriesFrom(type, agentId, 0))
                      .as("the observation and the answer, both written down")
                      .hasSize(2));
    }
  }
}
