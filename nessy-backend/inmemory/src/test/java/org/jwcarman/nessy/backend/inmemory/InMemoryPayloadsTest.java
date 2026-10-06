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
package org.jwcarman.nessy.backend.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.Codec;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Payloads held in this process")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryPayloadsTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private final Payloads payloads = new InMemoryPayloads(new JacksonCodecFactory(MAPPER));

  private static JsonNode document() {
    return MAPPER.readTree(
        """
        {"tool":"refund","amount":12.5,"urgent":true,"note":null,
         "lines":[1,"two",{"three":3}],"who":{"name":"Ada","roles":["admin","approver"]}}
        """);
  }

  @Test
  void a_document_round_trips() {
    JsonNode document = document();

    PayloadRef ref = payloads.putDocument(document);

    assertThat(payloads.getDocument(ref)).isEqualTo(document);
  }

  @Test
  void putting_the_same_document_twice_is_one_reference_and_one_copy() {
    PayloadRef first = payloads.putDocument(document());
    PayloadRef again = payloads.putDocument(document());

    assertThat(again).isEqualTo(first);
  }

  @Test
  void a_document_asked_for_as_blocks_fails_by_name() {
    PayloadRef ref = payloads.putDocument(document());

    assertThatThrownBy(() -> payloads.get(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " holds a document, not blocks");
  }

  @Test
  void blocks_asked_for_as_a_document_fail_by_name() {
    PayloadRef ref = payloads.put(List.of(new Block.Text("words")));

    assertThatThrownBy(() -> payloads.getDocument(ref))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + ref + " holds blocks, not a document");
  }

  @Test
  void a_document_that_is_not_there_is_a_fault() {
    PayloadRef never = new PayloadRef("00".repeat(32));

    assertThatThrownBy(() -> payloads.getDocument(never))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no payload behind " + never);
  }

  @Test
  void a_batch_read_that_meets_a_document_fails_by_name() {
    PayloadRef blocks = payloads.put(List.of(new Block.Text("words")));
    PayloadRef document = payloads.putDocument(document());
    List<PayloadRef> both = List.of(blocks, document);

    assertThatThrownBy(() -> payloads.get(both))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("payload " + document + " holds a document, not blocks");
  }

  @Test
  void the_same_fields_in_another_order_are_another_reference() {
    ObjectNode ab = MAPPER.createObjectNode();
    ab.put("a", 1);
    ab.put("b", 2);
    ObjectNode ba = MAPPER.createObjectNode();
    ba.put("b", 2);
    ba.put("a", 1);

    assertThat(payloads.putDocument(ab)).isNotEqualTo(payloads.putDocument(ba));
  }

  @Test
  void a_document_that_is_json_null_is_refused() {
    JsonNode nothing = NullNode.getInstance();

    assertThatThrownBy(() -> payloads.putDocument(nothing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a document must not be JSON null or missing");
  }

  @Test
  void a_missing_node_is_refused() {
    JsonNode missing = MissingNode.getInstance();

    assertThatThrownBy(() -> payloads.putDocument(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("a document must not be JSON null or missing");
  }

  @Test
  void an_empty_document_and_empty_blocks_survive_a_mapper_that_leaves_out_empty_values() {
    JsonMapper sparse =
        JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                value -> JsonInclude.Value.construct(Include.NON_EMPTY, Include.NON_EMPTY))
            .build();
    Payloads sparsePayloads = new InMemoryPayloads(new JacksonCodecFactory(sparse));
    JsonNode empty = sparse.createObjectNode();

    PayloadRef document = sparsePayloads.putDocument(empty);
    PayloadRef blocks = sparsePayloads.put(List.of());

    assertThat(sparsePayloads.getDocument(document)).isEqualTo(empty);
    assertThat(sparsePayloads.get(blocks)).isEqualTo(new Payloads.Resolved.Found(List.of()));
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

  private static Payloads under(Codec<byte[]> transform) {
    return new InMemoryPayloads(new JacksonCodecFactory(MAPPER), transform);
  }

  @Test
  void
      the_same_content_is_one_reference_under_a_transform_that_never_writes_the_same_bytes_twice() {
    Payloads nonced = under(new NeverTheSameBytes());
    List<Block> blocks = List.of(new Block.Text("say it again"));

    PayloadRef first = nonced.put(blocks);
    PayloadRef again = nonced.put(blocks);

    assertThat(again).isEqualTo(first);
    assertThat(nonced.get(first)).isEqualTo(new Payloads.Resolved.Found(blocks));
  }

  @Test
  void
      the_same_document_is_one_reference_under_a_transform_that_never_writes_the_same_bytes_twice() {
    Payloads nonced = under(new NeverTheSameBytes());

    PayloadRef first = nonced.putDocument(document());
    PayloadRef again = nonced.putDocument(document());

    assertThat(again).isEqualTo(first);
    assertThat(nonced.getDocument(first)).isEqualTo(document());
  }

  @Test
  void a_reference_does_not_depend_on_the_storage_transform() {
    Payloads transformed = under(REVERSED);
    List<Block> blocks = List.of(new Block.Text("same words"));

    assertThat(transformed.put(blocks)).isEqualTo(payloads.put(blocks));
    assertThat(transformed.putDocument(document())).isEqualTo(payloads.putDocument(document()));
  }

  @Test
  void the_stored_content_reads_back_through_the_transform() {
    Payloads transformed = under(REVERSED);
    List<Block> blocks = List.of(new Block.Text("same words"));

    PayloadRef blocksRef = transformed.put(blocks);
    PayloadRef documentRef = transformed.putDocument(document());

    assertThat(transformed.get(blocksRef)).isEqualTo(new Payloads.Resolved.Found(blocks));
    assertThat(transformed.getDocument(documentRef)).isEqualTo(document());
  }

  @Test
  void blocks_and_a_document_never_share_a_reference() {
    PayloadRef emptyBlocks = payloads.put(List.of());

    assertThat(emptyBlocks)
        .isNotEqualTo(payloads.putDocument(MAPPER.createObjectNode()))
        .isNotEqualTo(payloads.putDocument(MAPPER.createArrayNode()));
  }
}
