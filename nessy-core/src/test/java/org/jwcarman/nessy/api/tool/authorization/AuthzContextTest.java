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
package org.jwcarman.nessy.api.tool.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

class AuthzContextTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "spend", JsonNodeFactory.instance.objectNode());

  private static AuthzContext freshContext() {
    return AuthzContext.of("test-agent", CALL);
  }

  @Nested
  class Harness_known_facts {

    @Test
    void carries_conversation_id_agent_name_call_and_state_as_given() {
      AuthzContext context = freshContext();

      assertThat(context.agentName()).isEqualTo("test-agent");
      assertThat(context.call()).isEqualTo(CALL);
    }
  }

  @Nested
  class Deposits {

    private static final Key<String> COLOR = new Key<>(String.class, "color");

    @Test
    void a_key_nobody_deposited_into_is_empty() {
      AuthzContext context = freshContext();

      assertThat(context.get(COLOR)).isEmpty();
    }

    @Test
    void with_returns_a_new_context_that_answers_the_deposited_value() {
      AuthzContext context = freshContext();

      AuthzContext extended = context.with(COLOR, "blue");

      assertThat(extended.get(COLOR)).contains("blue");
    }

    @Test
    void with_never_mutates_the_context_it_was_called_on() {
      AuthzContext context = freshContext();

      context.with(COLOR, "blue");

      assertThat(context.get(COLOR)).isEmpty();
    }
  }

  @Nested
  class Principal_and_declared_intent_sugar {

    @Test
    void principal_is_empty_until_an_enricher_deposits_one() {
      AuthzContext context = freshContext();

      assertThat(context.principal()).isEmpty();
      assertThat(context.principal(String.class)).isEmpty();
    }

    @Test
    void principal_typed_recovery_hits_on_a_matching_class_token() {
      AuthzContext context = freshContext().with(AuthzContext.PRINCIPAL_KEY, "ada");

      assertThat(context.principal()).contains("ada");
      assertThat(context.principal(String.class)).contains("ada");
    }

    @Test
    void principal_typed_recovery_misses_on_a_mismatched_class_token() {
      AuthzContext context = freshContext().with(AuthzContext.PRINCIPAL_KEY, "ada");

      assertThat(context.principal(Integer.class)).isEmpty();
    }

    @Test
    void declared_intent_is_empty_until_spi_intent_deposits_one() {
      AuthzContext context = freshContext();

      assertThat(context.declaredIntent()).isEmpty();
      assertThat(context.declaredIntent(String.class)).isEmpty();
    }

    @Test
    void declared_intent_typed_recovery_hits_on_a_matching_class_token() {
      AuthzContext context = freshContext().with(AuthzContext.DECLARED_INTENT_KEY, "read-only");

      assertThat(context.declaredIntent()).contains("read-only");
      assertThat(context.declaredIntent(String.class)).contains("read-only");
    }
  }

  @Nested
  class Action_sugar {

    @Test
    void action_typed_recovery_hits_on_a_matching_class_token_and_misses_on_a_mismatched_one() {
      AuthzContext context = freshContext().with(AuthzContext.ACTION_KEY, "transfer 5 dollars");

      assertThat(context.action()).contains("transfer 5 dollars");
      assertThat(context.action(String.class)).contains("transfer 5 dollars");
      assertThat(context.action(Integer.class)).isEmpty();
    }
  }
}
