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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.backend.payload.Payloads;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("An in-memory backend given a storage transform")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InMemoryBackendStorageTransformTest {

  private static final JacksonCodecFactory VALUES =
      new JacksonCodecFactory(JsonMapper.builder().build());
  private static final List<Block> BLOCKS = List.of(new Block.Text("say it again"));

  private static void assertOneReferenceAndReadsBack(Payloads payloads) {
    PayloadRef first = payloads.put(BLOCKS);
    PayloadRef again = payloads.put(BLOCKS);

    assertThat(again).isEqualTo(first);
    assertThat(payloads.get(first)).isEqualTo(new Payloads.Resolved.Found(BLOCKS));
  }

  @Test
  void the_direct_door_keeps_the_same_content_under_one_reference() {
    assertOneReferenceAndReadsBack(
        new InMemoryDirectBackend(VALUES, new NeverTheSameBytes()).payloads());
  }

  @Test
  void the_queued_door_keeps_the_same_content_under_one_reference() {
    assertOneReferenceAndReadsBack(
        new InMemoryQueuedBackend(VALUES, new NeverTheSameBytes()).payloads());
  }
}
