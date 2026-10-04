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

package org.jwcarman.nessy.backend.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Content, kept where nothing else has to look at it. */
@Tag("container")
@DisplayName("Payloads in a database")
class JdbcPayloadsTest {

  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  static {
    POSTGRES.start();
  }

  private static DataSource database() {
    DataSource database =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Schemas.initialize(database);
    return database;
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final JdbcClient jdbc = JdbcClient.create(database());
  private final Payloads unscoped = new JdbcPayloads(jdbc, new JacksonCodecFactory(MAPPER));

  private Payloads forSomeAgent() {
    return unscoped.forAgent(AgentId.random());
  }

  @Test
  @DisplayName("keeps content and hands it back whole")
  void round_trips() {
    Payloads payloads = forSomeAgent();
    List<Block> content =
        List.of(
            new Block.Text("first"),
            new Block.Provider("anthropic", "{\"sig\":\"x\"}"),
            new Block.Text("last"));

    PayloadRef ref = payloads.put(content);

    assertThat(payloads.get(ref)).isEqualTo(new Payloads.Resolved.Found(content));
  }

  /** The reason the reference is the content's hash rather than a number somebody handed out. */
  @Test
  @DisplayName("putting the same content twice is one reference and one row")
  void putting_twice_is_idempotent() {
    Payloads payloads = forSomeAgent();
    List<Block> content = List.of(new Block.Text("say it again"));

    PayloadRef first = payloads.put(content);
    PayloadRef again = payloads.put(content);

    assertThat(again).as("an effect retried leaves no second copy").isEqualTo(first);
  }

  @Test
  @DisplayName("different content is a different reference")
  void different_content_differs() {
    Payloads payloads = forSomeAgent();

    assertThat(payloads.put(List.of(new Block.Text("one"))))
        .isNotEqualTo(payloads.put(List.of(new Block.Text("two"))));
  }

  /** What the scoping buys: one agent cannot read another's, however the reference was obtained. */
  @Test
  @DisplayName("an agent cannot resolve another agent's content")
  void agents_do_not_share() {
    Payloads mine = forSomeAgent();
    Payloads theirs = forSomeAgent();
    List<Block> content = List.of(new Block.Text("the same words"));

    PayloadRef ref = mine.put(content);

    assertThat(mine.get(ref)).isInstanceOf(Payloads.Resolved.Found.class);
    assertThat(theirs.get(ref))
        .as("identical content, stored twice, and not shared")
        .isEqualTo(new Payloads.Resolved.Missing());
  }

  @Test
  @DisplayName("a window of references is one query, and every one gets an answer")
  void resolves_a_batch() {
    Payloads payloads = forSomeAgent();
    PayloadRef one = payloads.put(List.of(new Block.Text("one")));
    PayloadRef two = payloads.put(List.of(new Block.Text("two")));
    PayloadRef never = new PayloadRef("00".repeat(32));

    Map<PayloadRef, Payloads.Resolved> found = payloads.get(List.of(one, two, never));

    assertThat(found)
        .hasSize(3)
        .containsEntry(one, new Payloads.Resolved.Found(List.of(new Block.Text("one"))))
        .containsEntry(two, new Payloads.Resolved.Found(List.of(new Block.Text("two"))))
        .as("asked about and not there, rather than absent from the answer")
        .containsEntry(never, new Payloads.Resolved.Missing());
  }

  @Test
  @DisplayName("refuses to be used before it knows whose content it is keeping")
  void unscoped_is_refused() {
    List<Block> content = List.of(new Block.Text("whose is this?"));

    assertThatThrownBy(() -> unscoped.put(content))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not scoped");
  }

  private static JsonNode document() {
    return MAPPER.readTree(
        """
        {"tool":"refund","amount":12.5,"urgent":true,"note":null,
         "lines":[1,"two",{"three":3}],"who":{"name":"Ada","roles":["admin","approver"]}}
        """);
  }

  @Test
  @DisplayName("keeps a document and hands it back whole")
  void a_document_round_trips() {
    Payloads payloads = forSomeAgent();
    JsonNode document = document();

    PayloadRef ref = payloads.putDocument(document);

    assertThat(payloads.getDocument(ref)).isEqualTo(document);
  }

  @Test
  @DisplayName("putting the same document twice is one reference and one copy")
  void putting_the_same_document_twice_is_one_reference_and_one_copy() {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);

    PayloadRef first = payloads.putDocument(document());
    PayloadRef again = payloads.putDocument(document());

    assertThat(again).isEqualTo(first);
    assertThat(
            jdbc.sql("SELECT count(*) FROM nessy_payload WHERE agent_id = ?")
                .params(agent.value())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("a document asked for as blocks fails by name")
  void a_document_asked_for_as_blocks_fails_by_name() {
    Payloads payloads = forSomeAgent();
    PayloadRef ref = payloads.putDocument(document());

    assertThatThrownBy(() -> payloads.get(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " holds a document, not blocks");
  }

  @Test
  @DisplayName("blocks asked for as a document fail by name")
  void blocks_asked_for_as_a_document_fail_by_name() {
    Payloads payloads = forSomeAgent();
    PayloadRef ref = payloads.put(List.of(new Block.Text("words")));

    assertThatThrownBy(() -> payloads.getDocument(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " holds blocks, not a document");
  }

  @Test
  @DisplayName("a document that is not there is a fault")
  void a_document_that_is_not_there_is_a_fault() {
    Payloads payloads = forSomeAgent();
    PayloadRef never = new PayloadRef("00".repeat(32));

    assertThatThrownBy(() -> payloads.getDocument(never))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no payload behind " + never);
  }

  @Test
  @DisplayName("a batch read that meets a document fails by name")
  void a_batch_read_that_meets_a_document_fails_by_name() {
    Payloads payloads = forSomeAgent();
    PayloadRef blocks = payloads.put(List.of(new Block.Text("words")));
    PayloadRef document = payloads.putDocument(document());
    List<PayloadRef> both = List.of(blocks, document);

    assertThatThrownBy(() -> payloads.get(both))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + document + " holds a document, not blocks");
  }

  @Test
  @DisplayName("an agent cannot resolve another agent's document")
  void agents_do_not_share_documents() {
    Payloads mine = forSomeAgent();
    Payloads theirs = forSomeAgent();
    PayloadRef ref = mine.putDocument(document());
    PayloadRef theirOwn = theirs.putDocument(document());

    assertThat(theirOwn).as("the same document, the same reference").isEqualTo(ref);
    assertThat(mine.getDocument(ref)).isEqualTo(document());
    PayloadRef elsewhere = mine.putDocument(MAPPER.readTree("{\"only\":\"mine\"}"));

    assertThatThrownBy(() -> theirs.getDocument(elsewhere))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no payload behind " + elsewhere);
  }

  private String kindOf(AgentId agent, PayloadRef ref) {
    return jdbc.sql("SELECT kind FROM nessy_payload WHERE agent_id = ? AND hash = ?")
        .params(agent.value(), HexFormat.of().parseHex(ref.value()))
        .query(String.class)
        .single();
  }

  @Test
  @DisplayName("says in the row whether it holds blocks or a document")
  void the_row_says_its_kind() {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);

    PayloadRef blocks = payloads.put(List.of(new Block.Text("words")));
    PayloadRef document = payloads.putDocument(document());

    assertThat(kindOf(agent, blocks)).isEqualTo("BLOCKS");
    assertThat(kindOf(agent, document)).isEqualTo("DOCUMENT");
  }

  @Test
  @DisplayName("a row with a kind it does not know is a fault that names the reference")
  void an_unknown_kind_is_a_fault() {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);
    PayloadRef ref = payloads.put(List.of(new Block.Text("words")));
    jdbc.sql("UPDATE nessy_payload SET kind = 'MYSTERY' WHERE agent_id = ?")
        .params(agent.value())
        .update();

    assertThatThrownBy(() -> payloads.get(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " has an unknown kind: MYSTERY");
  }

  @Test
  @DisplayName("a row with a kind it does not know is a fault when read as a document too")
  void an_unknown_kind_is_a_fault_when_read_as_a_document() {
    AgentId agent = AgentId.random();
    Payloads payloads = unscoped.forAgent(agent);
    PayloadRef ref = payloads.putDocument(document());
    jdbc.sql("UPDATE nessy_payload SET kind = 'MYSTERY' WHERE agent_id = ?")
        .params(agent.value())
        .update();

    assertThatThrownBy(() -> payloads.getDocument(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " has an unknown kind: MYSTERY");
  }

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

  private Payloads under(Codec<byte[]> transform, AgentId agent) {
    return new JdbcPayloads(jdbc, new JacksonCodecFactory(MAPPER), transform).forAgent(agent);
  }

  private long rows(AgentId agent) {
    return jdbc.sql("SELECT count(*) FROM nessy_payload WHERE agent_id = ?")
        .params(agent.value())
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName(
      "the same content is one reference and one row under a transform that never writes the same bytes twice")
  void
      the_same_content_is_one_reference_and_one_row_under_a_transform_that_never_writes_the_same_bytes_twice() {
    AgentId agent = AgentId.random();
    Payloads nonced = under(new NeverTheSameBytes(), agent);
    List<Block> blocks = List.of(new Block.Text("say it again"));

    PayloadRef first = nonced.put(blocks);
    PayloadRef again = nonced.put(blocks);

    assertThat(again).isEqualTo(first);
    assertThat(rows(agent)).isEqualTo(1L);
    assertThat(nonced.get(first)).isEqualTo(new Payloads.Resolved.Found(blocks));
  }

  @Test
  @DisplayName(
      "the same document is one reference and one row under a transform that never writes the same bytes twice")
  void
      the_same_document_is_one_reference_and_one_row_under_a_transform_that_never_writes_the_same_bytes_twice() {
    AgentId agent = AgentId.random();
    Payloads nonced = under(new NeverTheSameBytes(), agent);

    PayloadRef first = nonced.putDocument(document());
    PayloadRef again = nonced.putDocument(document());

    assertThat(again).isEqualTo(first);
    assertThat(rows(agent)).isEqualTo(1L);
    assertThat(nonced.getDocument(first)).isEqualTo(document());
  }

  @Test
  @DisplayName("a reference does not depend on the storage transform")
  void a_reference_does_not_depend_on_the_storage_transform() {
    Payloads transformed = under(REVERSED, AgentId.random());
    Payloads plain = forSomeAgent();
    List<Block> blocks = List.of(new Block.Text("same words"));

    assertThat(transformed.put(blocks)).isEqualTo(plain.put(blocks));
    assertThat(transformed.putDocument(document())).isEqualTo(plain.putDocument(document()));
  }

  @Test
  @DisplayName("the stored bytes are still transformed and read back")
  void the_stored_bytes_are_still_transformed_and_read_back() {
    AgentId agent = AgentId.random();
    Payloads transformed = under(REVERSED, agent);
    List<Block> blocks = List.of(new Block.Text("same words"));

    PayloadRef blocksRef = transformed.put(blocks);
    PayloadRef documentRef = transformed.putDocument(document());

    List<byte[]> stored =
        jdbc.sql("SELECT content FROM nessy_payload WHERE agent_id = ?")
            .params(agent.value())
            .query(byte[].class)
            .list();
    assertThat(stored).hasSize(2);
    assertThat(stored).allSatisfy(row -> assertThat(row[0]).isNotEqualTo((byte) '{'));
    assertThat(transformed.get(blocksRef)).isEqualTo(new Payloads.Resolved.Found(blocks));
    assertThat(transformed.getDocument(documentRef)).isEqualTo(document());
  }

  @Test
  @DisplayName("blocks and a document never share a reference")
  void blocks_and_a_document_never_share_a_reference() {
    Payloads payloads = forSomeAgent();
    PayloadRef emptyBlocks = payloads.put(List.of());

    assertThat(emptyBlocks).isNotEqualTo(payloads.putDocument(MAPPER.createObjectNode()));
    assertThat(emptyBlocks).isNotEqualTo(payloads.putDocument(MAPPER.createArrayNode()));
  }
}
