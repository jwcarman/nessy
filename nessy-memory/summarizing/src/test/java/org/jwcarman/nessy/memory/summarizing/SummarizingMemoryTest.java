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
package org.jwcarman.nessy.memory.summarizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.block.TextBlock;
import org.jwcarman.nessy.api.memory.Memory;
import org.jwcarman.nessy.api.message.AmbientMessage;
import org.jwcarman.nessy.api.message.AnswerMessage;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.ContextMessage;
import org.jwcarman.nessy.api.message.UserMessage;
import org.jwcarman.nessy.api.model.ModelId;
import org.jwcarman.nessy.api.model.StopReason;
import org.jwcarman.nessy.api.model.Usage;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Model;
import org.jwcarman.nessy.spi.model.ModelEvent;
import org.jwcarman.nessy.spi.model.ModelRequest;
import org.jwcarman.nessy.spi.model.ModelStream;
import org.jwcarman.nessy.testing.TestDatabase;

/**
 * A memory that compresses instead of dropping.
 *
 * <p>The transcript is never touched: {@code nessy_summary} is a sidecar saying "I summarize
 * through sequence N", and recall stitches that onto whatever came after. So every test here can
 * check both halves — what the model would see, and that nothing was destroyed to produce it.
 */
@DisplayName("A memory that summarizes")
class SummarizingMemoryTest {

  private static final AgentType TYPE = AgentType.of("chat");
  private static final AgentId AGENT = AgentId.of("agent-one");
  private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");

  private DataSource database;
  private TranscriptMemory transcript;

  /** Records what it was asked to summarize, so a test can assert on the request itself. */
  private static final class Summarizer implements Model {

    private final String answer;
    private final RuntimeException failure;
    ModelRequest sawRequest;
    int calls;

    Summarizer(String answer, RuntimeException failure) {
      this.answer = answer;
      this.failure = failure;
    }

    static Summarizer saying(String answer) {
      return new Summarizer(answer, null);
    }

    static Summarizer failing() {
      return new Summarizer(null, new IllegalStateException("the vendor is down"));
    }

    static Summarizer refusingToBeCalled() {
      return new Summarizer(null, new IllegalStateException("no model call was expected here"));
    }

    @Override
    public ModelId id() {
      return ModelId.of("summarizer");
    }

    @Override
    public ModelStream stream(ModelRequest request) {
      sawRequest = request;
      calls++;
      if (failure != null) {
        throw failure;
      }
      List<ModelEvent> events =
          List.of(
              new ModelEvent.TextChunk(answer),
              new ModelEvent.Stopped(StopReason.END_TURN, Usage.unreported()));
      return new ModelStream() {
        @Override
        public java.util.Iterator<ModelEvent> iterator() {
          return events.iterator();
        }

        @Override
        public void close() {
          // nothing held
        }
      };
    }
  }

  @BeforeEach
  void freshDatabase() {
    database = TestDatabase.fresh();
    transcript = TranscriptMemory.eternal(database, TYPE);
  }

  /**
   * A memory whose compression runs on the calling thread.
   *
   * <p>{@code Runnable::run} is what makes these tests deterministic: the handoff still happens
   * through the executor, so the production path is the tested path, but the work is finished
   * before the write returns.
   */
  private Memory summarizing(Model model, long after, int keepVerbatim) {
    return SummarizingMemory.create(
        config ->
            config
                .transcript(transcript)
                .dataSource(database)
                .agentType(TYPE)
                .model(model)
                .executor(Runnable::run)
                .summarizeAfter(after)
                .keepVerbatim(keepVerbatim)
                .clock(Clock.fixed(NOW, ZoneOffset.UTC)));
  }

  /** Writes {@code exchanges} user/answer pairs, so the transcript grows by two each time. */
  private static void say(Memory into, int exchanges) {
    for (int i = 1; i <= exchanges; i++) {
      into.remember(AGENT, UserMessage.of("message " + i));
      into.remember(AGENT, new AnswerMessage(List.of(new TextBlock("reply " + i))));
    }
  }

  private static List<String> textOf(Context context) {
    return context.messages().stream().map(Object::toString).toList();
  }

  @Nested
  @DisplayName("before anything is compressed")
  class Uncompressed {

    @Test
    void recall_is_just_the_transcript() {
      Memory memory = summarizing(Summarizer.refusingToBeCalled(), 100, 10);

      say(memory, 2);

      assertThat(memory.recall(AGENT).messages())
          .hasSize(4)
          .noneMatch(AmbientMessage.class::isInstance);
    }

    @Test
    @DisplayName("a transcript that has not outrun its summary is left alone")
    void below_the_threshold_no_model_is_called() {
      Summarizer never = Summarizer.refusingToBeCalled();
      Memory memory = summarizing(never, 100, 4);

      say(memory, 10); // 20 messages, threshold is 100

      assertThat(never.calls).isZero();
    }
  }

  @Nested
  @DisplayName("once it has compressed")
  class Compressed {

    @Test
    @DisplayName("recall is the summary, then only what it does not cover")
    void the_summary_replaces_the_front() {
      Summarizer summarizer = Summarizer.saying("Dale wants a refund on order 88.");
      Memory memory = summarizing(summarizer, 5, 4);

      say(memory, 10); // 20 messages

      List<ContextMessage> recalled = memory.recall(AGENT).messages();
      assertThat(recalled.getFirst()).isInstanceOf(AmbientMessage.class);
      assertThat(((AmbientMessage) recalled.getFirst()).kind()).isEqualTo("summary");
      assertThat(textOf(memory.recall(AGENT))).anyMatch(m -> m.contains("Dale wants a refund"));
      // Everything but the last few is behind the summary now.
      assertThat(recalled).hasSizeLessThan(20);
    }

    @Test
    @DisplayName("the transcript is untouched — nothing is destroyed to make a summary")
    void the_sidecar_takes_nothing_with_it() {
      Memory memory = summarizing(Summarizer.saying("a summary"), 5, 4);

      say(memory, 10);

      assertThat(transcript.recall(AGENT).messages())
          .as("every message the agent ever said")
          .hasSize(20);
    }

    @Test
    @DisplayName("there is only ever one summary, replaced rather than accumulated")
    void the_summary_is_replaced() {
      Memory memory = summarizing(Summarizer.saying("the latest"), 5, 4);

      say(memory, 20);

      List<ContextMessage> recalled = memory.recall(AGENT).messages();
      assertThat(recalled.stream().filter(AmbientMessage.class::isInstance)).hasSize(1);
    }

    @Test
    @DisplayName("compressing again is handed the PREVIOUS summary, not the whole transcript")
    void compaction_is_incremental() {
      Summarizer summarizer = Summarizer.saying("a summary");
      Memory memory = summarizing(summarizer, 5, 4);

      say(memory, 20); // enough writes to compress more than once

      assertThat(summarizer.calls).isGreaterThan(1);
      assertThat(summarizer.sawRequest.context().messages().getFirst())
          .as("its own previous output leads the input, which is what bounds the cost")
          .isInstanceOf(AmbientMessage.class);
      assertThat(summarizer.sawRequest.context().messages())
          .as("never the whole transcript")
          .hasSizeLessThan(40);
    }

    @Test
    @DisplayName("what to preserve is the application's to say")
    void the_prompt_can_be_replaced() {
      Summarizer summarizer = Summarizer.saying("a summary");
      Memory memory =
          SummarizingMemory.create(
              config ->
                  config
                      .transcript(transcript)
                      .dataSource(database)
                      .agentType(TYPE)
                      .model(summarizer)
                      .executor(Runnable::run)
                      .summarizeAfter(5)
                      .keepVerbatim(4)
                      .systemPrompt("Keep every order number, in German.")
                      .clock(Clock.fixed(NOW, ZoneOffset.UTC)));

      say(memory, 10);

      assertThat(summarizer.sawRequest.systemPrompt())
          .isEqualTo("Keep every order number, in German.");
    }

    @Test
    @DisplayName("the default instruction is what a summarizer is told when nobody says otherwise")
    void the_default_prompt_is_used() {
      Summarizer summarizer = Summarizer.saying("a summary");

      say(summarizing(summarizer, 5, 4), 10);

      assertThat(summarizer.sawRequest.systemPrompt())
          .isEqualTo(SummarizingMemory.SUMMARIZE)
          .as("written for repetition, because its own output is its next input")
          .contains("fed back");
    }

    @Test
    @DisplayName("the summarizer is given no tools")
    void nothing_that_reads_untrusted_text_may_act() {
      Summarizer summarizer = Summarizer.saying("a summary");
      Memory memory = summarizing(summarizer, 5, 4);

      say(memory, 10);

      assertThat(summarizer.sawRequest.tools())
          .as("a summarizer reads whatever a user wrote; it must not be able to act on it")
          .isEmpty();
      assertThat(summarizer.sawRequest.requested()).isEqualTo(Set.of());
    }
  }

  @Nested
  @DisplayName("when the summarizer fails")
  class Failing {

    @Test
    @DisplayName("nothing is written, and no history is lost")
    void a_failure_costs_nothing() {
      Memory memory = summarizing(Summarizer.failing(), 5, 4);

      say(memory, 10);

      // Size first: "no summary in it" would pass against an empty recall, which is the very
      // failure this test is about (S5841).
      assertThat(memory.recall(AGENT).messages())
          .as("everything is still recalled, verbatim")
          .hasSize(20)
          .as("but no summary was recorded")
          .noneMatch(AmbientMessage.class::isInstance);
      assertThat(transcript.recall(AGENT).messages()).hasSize(20);
    }

    @Test
    @DisplayName("an empty answer is not a summary")
    void an_empty_answer_is_refused() {
      Memory memory = summarizing(Summarizer.saying("   "), 5, 4);

      say(memory, 10);

      assertThat(memory.recall(AGENT).messages())
          .hasSize(20)
          .noneMatch(AmbientMessage.class::isInstance);
      assertThat(transcript.recall(AGENT).messages()).hasSize(20);
    }
  }

  @Nested
  @DisplayName("putting one together")
  class Building {

    @Test
    void a_memory_without_a_transcript_is_refused() {
      assertThatThrownBy(
              () ->
                  SummarizingMemory.create(
                      config ->
                          config
                              .dataSource(database)
                              .agentType(TYPE)
                              .model(Summarizer.saying("x"))
                              .executor(Runnable::run)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("transcript");
    }

    @Test
    @DisplayName("an executor is required, because compressing must not run on the writing thread")
    void an_executor_is_not_defaulted() {
      assertThatThrownBy(
              () ->
                  SummarizingMemory.create(
                      config ->
                          config
                              .transcript(transcript)
                              .dataSource(database)
                              .agentType(TYPE)
                              .model(Summarizer.saying("x"))))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("executor");
    }

    @Test
    @DisplayName("a threshold inside the verbatim window would never compress anything")
    void an_impossible_configuration_is_refused() {
      Model model = Summarizer.saying("x");

      assertThatThrownBy(() -> summarizing(model, 5, 10))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("keepVerbatim");
    }
  }

  @Nested
  @DisplayName("forgetting")
  class Forgetting {

    @Test
    void forget_takes_the_summary_with_it() {
      Memory memory = summarizing(Summarizer.saying("a summary"), 5, 4);
      say(memory, 10);
      assertThat(memory.recall(AGENT).messages()).anyMatch(AmbientMessage.class::isInstance);

      memory.forget(AGENT);

      assertThat(memory.recall(AGENT).messages()).isEmpty();
      assertThat(transcript.recall(AGENT).messages()).isEmpty();
    }
  }
}
