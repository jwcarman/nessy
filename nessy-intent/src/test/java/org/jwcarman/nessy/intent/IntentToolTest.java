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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.substrate.InMemorySubstrate;

class IntentToolTest {

  /** A plainly-pinned mapper — tolerant reads, same as the substrate's format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /** Where an answer would go if this tool deferred. It never does, so nothing reads it. */
  private static ReplyToken freshContext() {
    return new ReplyToken("unused-by-a-tool-that-never-defers");
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
    void itAnswersImmediatelyRatherThanDeferring() {
      var tool =
          IntentTool.freeform(
              new SubstrateIntentStore<>(new InMemorySubstrate(), "agent-a", Intent.class, MAPPER));

      // What CompletionPolicy.IMMEDIATE used to declare, the return type now shows: recording a
      // claim is local work, so this tool can only ever come back Ready.
      assertThat(tool.execute(new Intent("something"), freshContext()))
          .isInstanceOf(Awaited.Ready.class);
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

      var schema = tool.inputSchema();

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
