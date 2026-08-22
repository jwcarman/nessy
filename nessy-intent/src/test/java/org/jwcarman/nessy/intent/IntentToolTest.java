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
package org.jwcarman.nessy.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CompletionPolicy;
import org.jwcarman.nessy.api.tool.CallAddress;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolEventListener;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.durable.ToolInvocationId;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentToolTest {

  /** A plainly-pinned mapper — tolerant reads, same as the substrate's format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private static ToolContext freshContext() {
    var call =
        new ToolCall(
            "c0", "declare-intent", JsonNodeFactory.instance.objectNode().put("declaration", "x"));
    return new ToolContext(
        call,
        ToolEventListener.noop(),
        new CallAddress("ops", "prod-eu", "r0", "c0"),
        new ToolInvocationId("r0", "c0"));
  }

  @Nested
  class TheFreeformTier {

    @Test
    void itIsNamedDeclareIntent() {
      var tool =
          IntentTool.freeform(
              new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER));

      assertThat(tool.name()).isEqualTo("declare-intent");
    }

    @Test
    void itsDescriptionTellsTheModelToDeclareBeforeActing() {
      var tool =
          IntentTool.freeform(
              new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER));

      assertThat(tool.description())
          .isEqualTo("Declare what you are about to do and why, before using any other tool.");
    }

    @Test
    void itRequiresOnlyImmediateCompletion() {
      var tool =
          IntentTool.freeform(
              new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER));

      assertThat(tool.requiredCompletion()).isEqualTo(CompletionPolicy.IMMEDIATE);
    }

    @Test
    void executingDeclaresTheDeclarationIntoTheStore() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER);
      var tool = IntentTool.freeform(store);

      tool.execute(new Intent("restart prod-eu to clear the stuck deploy"), freshContext());

      assertThat(store.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }

    @Test
    void executingReturnsAnImmediatelyReadyOkResult() {
      var tool =
          IntentTool.freeform(
              new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER));

      Awaited<ToolResult> outcome = tool.execute(new Intent("restart prod-eu"), freshContext());

      assertThat(outcome).isEqualTo(Awaited.ready(ToolResult.ok("intent recorded")));
    }
  }

  @Nested
  class ASealedVocabulary {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
      @JsonSubTypes.Type(value = Restart.class, name = "Restart"),
      @JsonSubTypes.Type(value = Shutdown.class, name = "Shutdown")
    })
    sealed interface Vocabulary permits Restart, Shutdown {}

    record Restart(String host) implements Vocabulary {}

    record Shutdown(String reason) implements Vocabulary {}

    @Test
    void itsDescriptionPointsAtTheDefinedIntentShapes() {
      var tool =
          new IntentTool<>(
              Vocabulary.class,
              new SubstrateIntentStore<>(
                  new InMemorySubstrate(), "agent-a", Vocabulary.class, MAPPER));

      assertThat(tool.description())
          .isEqualTo(
              "Declare what you are about to do, using one of the defined intent shapes, before"
                  + " using any other tool.");
    }

    @Test
    void itsSpecCarriesAOneOfSchemaOverThePermittedShapes() {
      var tool =
          new IntentTool<>(
              Vocabulary.class,
              new SubstrateIntentStore<>(
                  new InMemorySubstrate(), "agent-a", Vocabulary.class, MAPPER));

      var schema = tool.spec().inputSchema();

      assertThat(schema.has("oneOf")).isTrue();
      assertThat(schema.get("oneOf")).hasSize(2);
    }

    @Test
    void executingBindsTheTypedDeclarationIntoTheStore() {
      var store =
          new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Vocabulary.class, MAPPER);
      var tool = new IntentTool<>(Vocabulary.class, store);

      tool.execute(new Restart("prod-eu"), freshContext());

      assertThat(store.latest()).contains(new Restart("prod-eu"));
    }
  }
}
