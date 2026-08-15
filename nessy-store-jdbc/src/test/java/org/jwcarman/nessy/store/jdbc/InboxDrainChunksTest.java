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
package org.jwcarman.nessy.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link InboxDrainChunks#chunk}, pinned without a database at the exact boundaries a
 * batch-size-500 split cares about: a batch under the size, a batch exactly at the size, one past
 * it, and a couple of full batches plus a remainder.
 */
class InboxDrainChunksTest {

  private static List<String> ids(int count) {
    return IntStream.range(0, count).mapToObj(i -> "id-" + i).collect(Collectors.toList());
  }

  @Test
  void an_empty_drain_yields_no_batches() {
    assertThat(InboxDrainChunks.chunk(new ArrayList<>())).isEmpty();
  }

  @Test
  void one_id_is_one_batch_of_one() {
    List<List<String>> batches = InboxDrainChunks.chunk(ids(1));

    assertThat(batches).hasSize(1);
    assertThat(batches.get(0)).hasSize(1);
  }

  @Test
  void exactly_the_batch_size_is_one_full_batch() {
    List<List<String>> batches = InboxDrainChunks.chunk(ids(InboxDrainChunks.BATCH_SIZE));

    assertThat(batches).hasSize(1);
    assertThat(batches.get(0)).hasSize(InboxDrainChunks.BATCH_SIZE);
  }

  @Test
  void one_past_the_batch_size_spills_into_a_second_batch_of_one() {
    List<List<String>> batches = InboxDrainChunks.chunk(ids(InboxDrainChunks.BATCH_SIZE + 1));

    assertThat(batches).hasSize(2);
    assertThat(batches.get(0)).hasSize(InboxDrainChunks.BATCH_SIZE);
    assertThat(batches.get(1)).hasSize(1);
  }

  @Test
  void two_full_batches_plus_a_remainder() {
    int total = InboxDrainChunks.BATCH_SIZE * 2 + 1;
    List<List<String>> batches = InboxDrainChunks.chunk(ids(total));

    assertThat(batches).hasSize(3);
    assertThat(batches.get(0)).hasSize(InboxDrainChunks.BATCH_SIZE);
    assertThat(batches.get(1)).hasSize(InboxDrainChunks.BATCH_SIZE);
    assertThat(batches.get(2)).hasSize(1);
  }

  @Test
  void batches_preserve_order_and_cover_every_id_exactly_once() {
    List<String> input = ids(InboxDrainChunks.BATCH_SIZE + 250);

    List<String> flattened =
        InboxDrainChunks.chunk(input).stream().flatMap(List::stream).collect(Collectors.toList());

    assertThat(flattened).containsExactlyElementsOf(input);
  }
}
