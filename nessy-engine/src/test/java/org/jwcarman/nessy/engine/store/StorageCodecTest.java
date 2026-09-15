package org.jwcarman.nessy.engine.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.spi.Codec;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Harness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.engine.EngineUnderTest;
import org.jwcarman.nessy.engine.history.HistoryEntry;
import org.jwcarman.nessy.spi.inference.InferenceResult;

/**
 * Something done to every stored byte, after Jackson: the rows are not JSON any more, and the
 * engine reads them back all the same.
 */
@DisplayName("A storage codec")
class StorageCodecTest {

  private static final AgentType CHAT = new AgentType("chat");

  /** Not a cipher; enough to make a row unreadable to anyone who does not undo it. */
  private static final Codec<byte[]> REVERSED =
      new Codec<>() {
        @Override
        public byte[] encode(byte[] bytes) {
          return reverse(bytes);
        }

        @Override
        public byte[] decode(byte[] bytes) {
          return reverse(bytes);
        }
      };

  private static byte[] reverse(byte[] bytes) {
    byte[] out = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      out[i] = bytes[bytes.length - 1 - i];
    }
    return out;
  }

  private EngineUnderTest engine;
  private Harness<String> harness;

  @BeforeEach
  void startEngine() {
    engine =
        new EngineUnderTest(
            (request, narrator) ->
                new InferenceResult.Answer(List.of(new Block.Text("a lake monster"))),
            REVERSED);
    harness =
        engine
            .harnesses()
            .create(
                String.class,
                config ->
                    config
                        .agentType(CHAT)
                        .systemPrompt("You are a test assistant.")
                        .effects(e -> e.pollInterval(Duration.ofMillis(100))));
  }

  @AfterEach
  void stopEngine() {
    engine.close();
  }

  @Test
  void every_row_is_written_through_it_and_read_back_through_it() {
    AgentId agentId = new AgentId(UUID.randomUUID());
    harness.observe(agentId, "what is nessy?");

    // The turn completes: state, story and effect rows all went through the codec both ways.
    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(engine.history().entriesFrom(CHAT, agentId, 0))
                    .filteredOn(HistoryEntry.InferenceAnswered.class::isInstance)
                    .hasSize(1));

    // And what is on disk is not JSON: a reader without the codec gets nothing.
    byte[] state =
        engine
            .jdbc()
            .sql("SELECT payload FROM nessy_agent_state WHERE agent_id = ?")
            .params(agentId.value())
            .query(byte[].class)
            .single();
    assertThat(state[0]).as("a JSON object would begin with '{'").isNotEqualTo((byte) '{');
    assertThat(new String(reverse(state), java.nio.charset.StandardCharsets.UTF_8)).startsWith("{");
    List<byte[]> story =
        engine
            .jdbc()
            .sql("SELECT payload FROM nessy_agent_history WHERE agent_id = ?")
            .params(agentId.value())
            .query(byte[].class)
            .list();
    assertThat(story).isNotEmpty().allSatisfy(row -> assertThat(row[0]).isNotEqualTo((byte) '{'));
  }
}
