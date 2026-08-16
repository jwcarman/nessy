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
package org.jwcarman.nessy.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.RunOutcome;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.event.EventEmitter;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.api.tool.ToolCall;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.Capability;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.spi.notebook.Notebook;
import org.jwcarman.nessy.spi.notebook.NotebookTools;
import org.jwcarman.nessy.spi.reflection.Reflection;
import org.jwcarman.nessy.spi.transcript.Transcript;

/**
 * The full learn-then-benefit loop, offline (design of record 2026-08-16 §5): a conversation
 * settles {@code FAILED}, the critic writes a lesson, and a later conversation over the same
 * subject both carries that lesson — annotated as foreign-sourced — in its own notebook index, and
 * can read its body back through the recall tool. One scripted provider plays both the agent's own
 * model and the critic's side call (design §3's own wiring shape: the critic reuses the harness's
 * provider).
 */
class ReflectionEndToEndTest {

  private static final String IDENTITY = "e2e-agent";
  private static final SubjectId SUBJECT = new SubjectId("subject-1");

  /**
   * A provider that throws on its very first call — standing in for a genuine model failure (an
   * xAI-style 403, say) that settles the conversation {@code FAILED} without ever touching the
   * script — then delegates every later call to {@code delegate}, exactly like {@code
   * EndToEndTest}'s own {@code FailFirstThenDelegate}. Local to this test for the same reason that
   * one is local to its own.
   */
  private static final class FailFirstThenDelegate implements ModelProvider {

    private final ModelProvider delegate;
    private final RuntimeException firstCallFailure;
    private boolean calledOnce;

    FailFirstThenDelegate(ModelProvider delegate, RuntimeException firstCallFailure) {
      this.delegate = delegate;
      this.firstCallFailure = firstCallFailure;
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      if (!calledOnce) {
        calledOnce = true;
        throw firstCallFailure;
      }
      return delegate.stream(request);
    }

    @Override
    public Set<Capability> capabilities() {
      return delegate.capabilities();
    }
  }

  @Test
  void a_lesson_from_a_failed_conversation_is_visible_and_recallable_in_the_next_conversation() {
    Notebook notebook = Notebook.inMemory();
    Function<ConversationId, SubjectId> subjectResolver = id -> SUBJECT;
    Transcript transcript = Transcript.inMemory();
    // Turn 1 (consumed by the critic's own side call): a lesson distilled from the failure.
    // Turn 2 (consumed by the second conversation's own model call): an ordinary reply.
    ScriptedModelProvider scripted =
        ScriptedModelProvider.script(
            s ->
                s.text(
                        "[{\"hook\": \"retry with backoff\", \"body\": \"The flaky task failed"
                            + " because nothing retried the transient error; retry with backoff"
                            + " next time.\"}]")
                    .endTurn()
                    .text("Understood, I will retry with backoff this time.")
                    .endTurn());
    FailFirstThenDelegate provider =
        new FailFirstThenDelegate(scripted, new RuntimeException("boom: transient failure"));

    Agent<String> agent =
        Nessy.harness(
                h ->
                    h.provider(provider)
                        .listen(
                            ConversationSettled.class,
                            Reflection.critic(
                                c ->
                                    c.transcript(transcript)
                                        .notebook(notebook)
                                        .subject(subjectResolver)
                                        .provider(provider)
                                        .model("critic-model")
                                        .reflectOnSuccess(false))))
            .agent(
                a ->
                    a.name(IDENTITY)
                        .model("fake-model")
                        .memory(
                            Memory.pipeline(
                                transcript,
                                config ->
                                    config.transform(
                                        NotebookTools.transformer(
                                            notebook, IDENTITY, subjectResolver)))));

    Conversation<String> first = agent.converse();
    RunOutcome firstOutcome = first.tell("attempt the flaky task");
    assertThat(firstOutcome.state().status()).isEqualTo(ConversationStatus.FAILED);

    Conversation<String> second = agent.converse();
    second.tell("attempt the flaky task again");

    // The second conversation's own request carries the notebook index the critic's lesson landed
    // in, annotated as foreign-sourced: this agent's own identity is "e2e-agent", the lesson's
    // source is "reflection".
    ModelRequest secondRequest = scripted.requests().get(1);
    TextBlock index = (TextBlock) secondRequest.context().messages().getLast().content().getFirst();
    assertThat(index.text()).contains("retry with backoff").contains("(from reflection)");

    String lessonName = "lesson:" + first.conversationId().value();
    ToolContext recallContext =
        new ToolContext(
            second.conversationId(),
            new ToolCall("recall-1", "recall", JsonNodeFactory.instance.objectNode()),
            EventEmitter.noop());
    Awaited<ToolResult> awaited =
        NotebookTools.recall(notebook, IDENTITY, subjectResolver)
            .execute(new NotebookTools.RecallNote(lessonName), recallContext);
    ToolResult recalled = ((Awaited.Ready<ToolResult>) awaited).value();

    assertThat(recalled.isError()).isFalse();
    assertThat(recalled.content())
        .isEqualTo(
            "The flaky task failed because nothing retried the transient error; retry with"
                + " backoff next time.");
  }
}
