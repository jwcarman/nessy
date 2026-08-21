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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.tool.ToolCall;

class EnrichersTest {

  private static final ToolCall CALL =
      new ToolCall("c1", "spend", JsonNodeFactory.instance.objectNode());

  private static AuthzContext freshContext() {
    return AuthzContext.of("test-agent", CALL);
  }

  @Test
  void principal_deposits_the_resolved_value_under_principal_key() {
    Enricher<Object> enricher = Enrichers.principal(() -> "ada");

    AuthzContext enriched = enricher.enrich(freshContext(), null);

    assertThat(enriched.get(AuthzContext.PRINCIPAL_KEY)).contains("ada");
  }

  @Test
  void principal_never_mutates_the_context_it_was_given() {
    Enricher<Object> enricher = Enrichers.principal(() -> "ada");
    AuthzContext context = freshContext();

    enricher.enrich(context, null);

    assertThat(context.principal()).isEmpty();
  }

  @Test
  void principal_reports_itself_as_named_principal() {
    Enricher<Object> enricher = Enrichers.principal(() -> "ada");

    assertThat(enricher.displayName()).contains("principal");
  }

  @Test
  void principal_calls_the_resolver_fresh_on_every_enrichment() {
    AtomicInteger calls = new AtomicInteger();
    Enricher<Object> enricher = Enrichers.principal(() -> "principal-" + calls.incrementAndGet());

    AuthzContext first = enricher.enrich(freshContext(), null);
    AuthzContext second = enricher.enrich(freshContext(), null);

    assertThat(first.get(AuthzContext.PRINCIPAL_KEY)).contains("principal-1");
    assertThat(second.get(AuthzContext.PRINCIPAL_KEY)).contains("principal-2");
  }

  @Test
  void principal_rejects_a_null_resolver() {
    assertThatThrownBy(() -> Enrichers.principal(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("resolver");
  }
}
