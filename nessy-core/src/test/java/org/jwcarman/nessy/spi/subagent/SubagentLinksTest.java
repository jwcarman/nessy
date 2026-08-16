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
package org.jwcarman.nessy.spi.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.ParkToken;
import org.jwcarman.nessy.api.conversation.ConversationId;

class SubagentLinksTest {

  private final SubagentLinks links = SubagentLinks.inMemory();

  @Nested
  class Finding {

    @Test
    void a_child_never_saved_is_not_found() {
      assertThat(links.find(ConversationId.generate())).isEmpty();
    }

    @Test
    void a_saved_link_is_found_by_its_child_id() {
      ConversationId child = ConversationId.generate();
      ParkToken parentToken = ParkToken.generate();

      links.save(child, parentToken, "parent-agent");

      assertThat(links.find(child)).contains(new SubagentLinks.Link(parentToken, "parent-agent"));
    }
  }

  @Nested
  class Saving {

    @Test
    void saving_twice_for_the_same_child_keeps_only_the_last_write() {
      ConversationId child = ConversationId.generate();
      ParkToken firstToken = ParkToken.generate();
      ParkToken secondToken = ParkToken.generate();

      links.save(child, firstToken, "first-agent");
      links.save(child, secondToken, "second-agent");

      assertThat(links.find(child)).contains(new SubagentLinks.Link(secondToken, "second-agent"));
    }
  }

  @Nested
  class Forgetting {

    @Test
    void forgetting_a_saved_link_makes_it_no_longer_findable() {
      ConversationId child = ConversationId.generate();
      links.save(child, ParkToken.generate(), "parent-agent");

      links.forget(child);

      assertThat(links.find(child)).isEmpty();
    }

    @Test
    void forgetting_twice_is_a_quiet_no_op() {
      ConversationId child = ConversationId.generate();
      links.save(child, ParkToken.generate(), "parent-agent");
      links.forget(child);

      links.forget(child);

      assertThat(links.find(child)).isEmpty();
    }

    @Test
    void forgetting_a_child_never_saved_is_a_quiet_no_op() {
      links.forget(ConversationId.generate());
    }
  }
}
