package org.jwcarman.nessy.approval.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jwcarman.nessy.approval.intent.Fixtures.AGENT;
import static org.jwcarman.nessy.approval.intent.Fixtures.MAPPER;
import static org.jwcarman.nessy.approval.intent.Fixtures.TYPE;
import static org.jwcarman.nessy.approval.intent.Fixtures.declaring;
import static org.jwcarman.nessy.approval.intent.Fixtures.freshDatabase;
import static org.jwcarman.nessy.approval.intent.Fixtures.freshStore;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

class IntentToolTest {

  @Nested
  class TheFreeformTier {

    @Test
    void itIsNamedDeclareIntent() {
      assertThat(IntentTool.freeform(freshStore()).name())
          .isEqualTo(new ToolName("declare-intent"));
    }

    @Test
    void itsInputTypeIsTheFreeformIntentClass() {
      assertThat(IntentTool.freeform(freshStore()).inputType()).isEqualTo(Intent.class);
    }

    @Test
    void itsDescriptionTellsTheModelToDeclareBeforeActing() {
      assertThat(IntentTool.freeform(freshStore()).description())
          .isEqualTo("Declare what you are about to do and why, before using any other tool.");
    }

    /** Recording a claim is local work, so this tool can only ever come back ready. */
    @Test
    void itAnswersImmediatelyRatherThanDeferring() {
      assertThat(IntentTool.freeform(freshStore()).call(declaring(new Intent("something"))))
          .isInstanceOf(Awaited.Ready.class);
    }

    @Test
    void callingDeclaresTheDeclarationIntoTheStoreForTheCallingAgent() {
      var store = freshStore();

      IntentTool.freeform(store)
          .call(declaring(new Intent("restart prod-eu to clear the stuck deploy")));

      assertThat(store.latest(AGENT))
          .contains(new Intent("restart prod-eu to clear the stuck deploy"));
    }

    @Test
    void callingReturnsAnImmediatelyReadyOkResult() {
      Awaited<ToolResult> outcome =
          IntentTool.freeform(freshStore()).call(declaring(new Intent("restart prod-eu")));

      assertThat(outcome)
          .isEqualTo(Awaited.ready(ToolResult.ok(new Block.Text("intent recorded"))));
    }
  }

  /**
   * The schema for a sealed vocabulary is the engine's generator's business now -- {@code
   * Tool.inputSchema(generator)} defaults to asking it -- and the generator's own tests cover the
   * {@code oneOf} shape. What is this module's is that the typed declaration binds and lands.
   */
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

    private JdbcIntentStore<Vocabulary> store() {
      return new JdbcIntentStore<>(freshDatabase(), TYPE, Vocabulary.class, MAPPER);
    }

    @Test
    void itsDescriptionPointsAtTheDefinedIntentShapes() {
      assertThat(new IntentTool<>(Vocabulary.class, store()).description())
          .isEqualTo(
              "Declare what you are about to do, using one of the defined intent shapes, before"
                  + " using any other tool.");
    }

    @Test
    void callingBindsTheTypedDeclarationIntoTheStore() {
      var store = store();

      new IntentTool<>(Vocabulary.class, store).call(declaring(new Restart("prod-eu")));

      assertThat(store.latest(AGENT)).contains(new Restart("prod-eu"));
    }
  }
}
