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
package org.jwcarman.nessy.tck;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;

/**
 * The technology-compatibility kit every {@link SubagentLinks} implementation must pass (design
 * §5): round-trip, last-write-wins on a double save, idempotent forget, and an absent find — pinned
 * as law rather than left to each implementation's own judgment.
 *
 * <p>Test methods are {@code public} — a nested-subscriber discovery lesson learned elsewhere in
 * this kit (see {@link NotebookContract}): a package-private {@code @Test} method inherited into a
 * {@code @Nested} class is not always picked up the same way by every JUnit runner, so this
 * contract states its methods public rather than risk it.
 */
public abstract class SubagentLinksContract {

  /** The links registry under test — fresh and empty for each test. */
  protected abstract SubagentLinks links();

  @Test
  public void a_saved_link_is_found_by_its_child_id() {
    ConversationId child = ConversationId.generate();
    ParkToken parentToken = ParkToken.generate();

    links().save(child, parentToken);

    assertThat(links().find(child)).contains(parentToken);
  }

  @Test
  public void a_child_never_saved_is_not_found() {
    assertThat(links().find(ConversationId.generate())).isEmpty();
  }

  @Test
  public void saving_twice_for_the_same_child_keeps_only_the_last_write() {
    ConversationId child = ConversationId.generate();
    ParkToken firstToken = ParkToken.generate();
    ParkToken secondToken = ParkToken.generate();

    links().save(child, firstToken);
    links().save(child, secondToken);

    assertThat(links().find(child)).contains(secondToken);
  }

  @Test
  public void forgetting_a_saved_link_makes_it_no_longer_findable() {
    ConversationId child = ConversationId.generate();
    links().save(child, ParkToken.generate());

    links().forget(child);

    assertThat(links().find(child)).isEmpty();
  }

  @Test
  public void forgetting_a_child_never_saved_is_a_quiet_no_op() {
    ConversationId child = ConversationId.generate();

    links().forget(child);

    assertThat(links().find(child)).isEmpty();
  }

  @Test
  public void forgetting_twice_is_a_quiet_no_op() {
    ConversationId child = ConversationId.generate();
    links().save(child, ParkToken.generate());
    links().forget(child);

    links().forget(child);

    assertThat(links().find(child)).isEmpty();
  }
}
