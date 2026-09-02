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
package org.jwcarman.nessy.approval.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.CallId;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.testing.TestDatabase;

class IntentToolTest {

  /** A plainly-pinned mapper — tolerant reads, same as the stored format contract. */
  private static final ObjectMapper MAPPER =
      new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /**
   * What the engine tells a running tool. This one never defers and keeps nothing per agent, so it
   * reads none of it — but a context is no longer a single method, because a tool that DOES keep
   * something per agent has to be told which agent it is serving.
   */
  private static <T> ToolCallRequest<T> declaring(T intent) {
    return new ToolCallRequest<>(
        AgentType.of("intent-test"),
        AgentId.of("one"),
        TurnId.of("turn-1"),
        CallId.of("c1"),
        "declare_intent",
        intent,
        new ReplyToken("unused-by-a-tool-that-never-defers"));
  }

  @Nested
  class TheFreeformTier {

    @Test
    void itIsNamedDeclareIntent() {
      var tool =
          IntentTool.freeform(
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Intent.class,
                  MAPPER));

      assertThat(tool.name()).isEqualTo("declare-intent");
    }

    @Test
    void itsInputTypeIsTheFreeformIntentClass() {
      var tool =
          IntentTool.freeform(
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Intent.class,
                  MAPPER));

      assertThat(tool.inputType()).isEqualTo(Intent.class);
    }

    @Test
    void itsDescriptionTellsTheModelToDeclareBeforeActing() {
      var tool =
          IntentTool.freeform(
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Intent.class,
                  MAPPER));

      assertThat(tool.description())
          .isEqualTo("Declare what you are about to do and why, before using any other tool.");
    }

    @Test
    void itAnswersImmediatelyRatherThanDeferring() {
      var tool =
          IntentTool.freeform(
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Intent.class,
                  MAPPER));

      // What CompletionPolicy.IMMEDIATE used to declare, the return type now shows: recording a
      // claim is local work, so this tool can only ever come back Ready.
      assertThat(tool.execute(declaring(new Intent("something"))))
          .isInstanceOf(Awaited.Ready.class);
    }

    @Test
    void executingDeclaresTheDeclarationIntoTheStore() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Intent.class,
              MAPPER);
      var tool = IntentTool.freeform(store);

      tool.execute(declaring(new Intent("restart prod-eu to clear the stuck deploy")));

      assertThat(store.latest()).contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }

    @Test
    void executingReturnsAnImmediatelyReadyOkResult() {
      var tool =
          IntentTool.freeform(
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Intent.class,
                  MAPPER));

      Awaited<ToolResult> outcome = tool.execute(declaring(new Intent("restart prod-eu")));

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
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Vocabulary.class,
                  MAPPER));

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
              new JdbcIntentStore<>(
                  TestDatabase.fresh(),
                  AgentType.of("chat"),
                  AgentId.of("agent-a"),
                  Vocabulary.class,
                  MAPPER));

      var schema = tool.inputSchema();

      assertThat(schema.has("oneOf")).isTrue();
      assertThat(schema.get("oneOf")).hasSize(2);
    }

    @Test
    void executingBindsTheTypedDeclarationIntoTheStore() {
      var store =
          new JdbcIntentStore<>(
              TestDatabase.fresh(),
              AgentType.of("chat"),
              AgentId.of("agent-a"),
              Vocabulary.class,
              MAPPER);
      var tool = new IntentTool<>(Vocabulary.class, store);

      tool.execute(declaring(new Restart("prod-eu")));

      assertThat(store.latest()).contains(new Restart("prod-eu"));
    }
  }
}
