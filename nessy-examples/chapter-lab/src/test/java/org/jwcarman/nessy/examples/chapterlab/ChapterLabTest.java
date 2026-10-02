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
package org.jwcarman.nessy.examples.chapterlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.Usage;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Input;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessy.engine.store.TurnHistory;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;
import tools.jackson.databind.json.JsonMapper;

@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ChapterLabTest {

  private static final AgentType TYPE = new AgentType("chapter-lab");
  private static final AgentId AGENT = AgentId.random();

  private static File fixture() {
    URL found = ChapterLabTest.class.getResource("/locomo-fixture.json");
    assertThat(found).isNotNull();
    try {
      return new File(found.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String textOf(InferenceRequest request) {
    return Transcripts.text(request.context().activeTurn().input().blocks());
  }

  private static InferenceResult answer(String text, int in, int out) {
    return new InferenceResult.Answer(
        List.of(new Block.Text(text)), Usage.of("the-model", in, out));
  }

  /**
   * Stands in for the real model. It tells the kinds of request apart by their system prompt, and
   * answers each with fixed text and a fixed token count.
   */
  private static final class ScriptedModel implements InferenceProvider {

    final List<InferenceRequest> summaries = new CopyOnWriteArrayList<>();
    final List<InferenceRequest> questions = new CopyOnWriteArrayList<>();
    final List<InferenceRequest> hindsights = new CopyOnWriteArrayList<>();
    private final String hindsightReply;

    ScriptedModel() {
      this("[2]");
    }

    ScriptedModel(String hindsightReply) {
      this.hindsightReply = hindsightReply;
    }

    @Override
    public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
      String system = request.systemPrompt().value();
      if (system.equals(ProseSummarizer.PROMPT) || system.equals(LabPrompts.INDEX_SUMMARY)) {
        summaries.add(request);
        return answer("they planted tomatoes and talked about Rex", 10, 2);
      }
      if (system.equals(LabPrompts.HINDSIGHT_SYSTEM)) {
        hindsights.add(request);
        return answer(hindsightReply, 7, 1);
      }
      if (system.equals(LabPrompts.ANSWER_SYSTEM)) {
        questions.add(request);
        String asked = textOf(request);
        if (asked.contains("What did Ann plant")) {
          return answer("tomatoes", 20, 3);
        }
        if (asked.contains("dog")) {
          return answer("Rex", 20, 3);
        }
        if (asked.contains("photos")) {
          return answer("three hundred", 20, 3);
        }
        return answer("unknown", 20, 3);
      }
      if (system.equals(LabPrompts.GRADE_SYSTEM)) {
        return answer(textOf(request).contains("Given answer: unknown") ? "no" : "Yes", 5, 1);
      }
      throw new IllegalStateException("a request nobody scripted: " + system);
    }
  }

  private static ChapterLab.Settings settings(String policy, String summarizer) {
    return new ChapterLab.Settings(
        fixture(),
        0,
        40,
        "anthropic",
        "the-model",
        policy,
        summarizer,
        "the-summary-model",
        200,
        400);
  }

  private static final class Run {
    final ChapterLab.Result result;
    final String printed;
    final List<String> recorded;

    Run(ChapterLab.Result result, String printed, List<String> recorded) {
      this.result = result;
      this.printed = printed;
      this.recorded = recorded;
    }
  }

  private static Run run(ChapterLab.Settings settings, ScriptedModel model, Path directory)
      throws Exception {
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    ChapterLab.Result result =
        ChapterLab.run(
            settings,
            LocomoConversation.read(settings.data(), 0),
            model,
            new PrintStream(captured, true, StandardCharsets.UTF_8),
            directory);
    Path written = directory.resolve(settings.resultsFile());
    return new Run(
        result,
        captured.toString(StandardCharsets.UTF_8),
        Files.exists(written) ? Files.readAllLines(written) : List.of());
  }

  @Nested
  @DisplayName("Reading a conversation")
  class Reading_a_conversation {

    private final LocomoConversation conversation = LocomoConversation.read(fixture(), 0);

    @Test
    void messages_are_paired_into_turns_across_sessions() {
      assertThat(conversation.turns()).hasSize(5);
      assertThat(conversation.turns())
          .extracting(LocomoConversation.Recorded::session)
          .containsExactly(1, 1, 2, 2, 3);
    }

    @Test
    void the_first_message_is_the_input_and_carries_the_sessions_date() {
      LocomoConversation.Recorded first = conversation.turns().getFirst();

      assertThat(first.input())
          .isEqualTo("[1:00 pm on 1 May, 2023] Ann: I planted tomatoes today.");
      assertThat(first.reply()).isEqualTo("Ben: Nice, I have a dog called Rex.");
    }

    @Test
    void a_message_nobody_answered_is_a_turn_with_no_reply() {
      LocomoConversation.Recorded unanswered = conversation.turns().get(3);

      assertThat(unanswered.input())
          .isEqualTo("[9:30 am on 8 May, 2023] Ben: Three hundred of them.");
      assertThat(unanswered.reply()).isEqualTo("(no reply)");
    }

    @Test
    void the_last_turn_of_each_session_is_known() {
      List<Integer> ends =
          IntStream.range(0, conversation.turns().size())
              .filter(conversation::endsSession)
              .boxed()
              .toList();

      assertThat(ends).containsExactly(1, 3, 4);
    }

    @Test
    void only_questions_of_categories_one_to_four_with_evidence_are_kept() {
      assertThat(conversation.questions())
          .extracting(LocomoConversation.Question::category)
          .containsExactly(1, 2, 4, 1);
      assertThat(conversation.questions().getLast().answer()).isEqualTo("300");
    }

    @Test
    void a_sample_is_fixed_by_the_conversations_number() {
      List<LocomoConversation.Question> first = conversation.sample(2, 0);
      List<LocomoConversation.Question> again = conversation.sample(2, 0);

      assertThat(first).hasSize(2).isEqualTo(again).isSubsetOf(conversation.questions());
      assertThat(conversation.sample(40, 0)).hasSameSizeAs(conversation.questions());
    }

    @Test
    void a_conversation_the_file_does_not_hold_is_refused() {
      File data = fixture();

      assertThatThrownBy(() -> LocomoConversation.read(data, 3))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("conversation 3")
          .hasMessageContaining("holds 1");
    }
  }

  private static TurnId turn(long id) {
    return new TurnId(id);
  }

  private static Turn recorded(long id) {
    return new Turn(
        turn(id),
        new Input(new Seq(id), List.of(new Block.Text("question " + id))),
        List.of(),
        new TurnResult.Answered(List.of(new Block.Text("reply " + id))),
        5);
  }

  @Nested
  @DisplayName("The session policy")
  class The_session_policy {

    @Test
    void a_chapter_ends_at_each_sessions_last_turn() {
      OpenTurns open =
          new OpenTurns(TYPE, AGENT, List.of(turn(1), turn(2), turn(3), turn(4), turn(5)));

      List<TurnId> ends = LabPolicies.session(Set.of(turn(2), turn(4))).ends(open);

      assertThat(ends).containsExactly(turn(2), turn(4));
    }

    @Test
    void nothing_closes_while_no_session_has_ended() {
      OpenTurns open = new OpenTurns(TYPE, AGENT, List.of(turn(1), turn(2)));

      assertThat(LabPolicies.session(Set.of(turn(7))).ends(open)).isEmpty();
    }
  }

  /** A story of {@code count} numbered turns. */
  private static final class Story implements TurnHistories, TurnHistory {

    private final int count;

    Story(int count) {
      this.count = count;
    }

    @Override
    public TurnHistory forAgent(AgentType agentType, AgentId agentId) {
      return this;
    }

    @Override
    public List<Turn> turnsBetween(TurnId from, TurnId through) {
      List<Turn> turns = new ArrayList<>();
      for (long id = from.value(); id <= through.value() && id <= count; id++) {
        turns.add(recorded(id));
      }
      return turns;
    }

    @Override
    public List<Turn> lastTurns(int turns) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Turn> turnsFrom(long fromTurn) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<Turn> lastTurnsAfter(TurnId through, int turns) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long turnsAfter(long through) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<TurnId> completedAfter(Optional<TurnId> through) {
      throw new UnsupportedOperationException();
    }
  }

  private static OpenTurns openTurns(int count) {
    return new OpenTurns(
        TYPE, AGENT, IntStream.rangeClosed(1, count).mapToObj(ChapterLabTest::turn).toList());
  }

  @Nested
  @DisplayName("The hindsight policy")
  class The_hindsight_policy {

    private final InferenceOptions options = InferenceOptions.of("the-summary-model");

    @Test
    void a_model_reads_forty_open_turns_and_names_where_chapters_end() {
      ScriptedModel model = new ScriptedModel("The breaks are [5, 12, 40].");

      List<TurnId> ends = LabPolicies.hindsight(new Story(60), model, options).ends(openTurns(60));

      assertThat(ends).containsExactly(turn(5), turn(12), turn(40));
      assertThat(model.hindsights).hasSize(1);
      String shown = textOf(model.hindsights.getFirst());
      assertThat(shown)
          .contains("[1]", "[40]", "question 40", "These are exchanges 1 to 40.")
          .doesNotContain("[41]");
    }

    @Test
    void nothing_closes_and_no_model_is_asked_until_forty_turns_are_open() {
      ScriptedModel model = new ScriptedModel();

      List<TurnId> ends = LabPolicies.hindsight(new Story(39), model, options).ends(openTurns(39));

      assertThat(ends).isEmpty();
      assertThat(model.hindsights).isEmpty();
    }

    @Test
    void a_reply_with_no_usable_number_closes_the_first_twenty() {
      ScriptedModel model = new ScriptedModel("no breaks at all, and 99 is out of range");

      List<TurnId> ends = LabPolicies.hindsight(new Story(40), model, options).ends(openTurns(40));

      assertThat(ends).containsExactly(turn(20));
    }

    @Test
    void only_the_last_bracketed_group_of_a_reply_counts() {
      assertThat(LabPolicies.numbers("exchanges 1 to 40: [12, 25]")).containsExactly(12, 25);
      assertThat(LabPolicies.numbers("first [3] then, on reflection, [7, 9]"))
          .containsExactly(7, 9);
    }

    @Test
    void a_reply_with_no_bracketed_group_names_nothing() {
      assertThat(LabPolicies.numbers("breaks at 12 and 25")).isEmpty();
    }

    @Test
    void numbers_are_sorted_and_repeats_dropped() {
      List<TurnId> window = openTurns(10).turns();

      assertThat(LabPolicies.ends(List.of(7, 3, 7, 0, 11), window))
          .containsExactly(turn(3), turn(7));
    }
  }

  @Nested
  @DisplayName("The command line")
  class The_command_line {

    private static final String[] REQUIRED = {
      "--data", "locomo10.json", "--provider", "anthropic", "--model", "a-model"
    };

    private String[] with(String... more) {
      String[] all = new String[REQUIRED.length + more.length];
      System.arraycopy(REQUIRED, 0, all, 0, REQUIRED.length);
      System.arraycopy(more, 0, all, REQUIRED.length, more.length);
      return all;
    }

    @Test
    void the_defaults_leave_the_policy_to_decide() {
      ChapterLab.Settings settings = ChapterLab.parse(with());

      assertThat(settings.policy()).isEqualTo("every:20");
      assertThat(settings.summarizer()).isEqualTo("prose");
      assertThat(settings.summaryModel()).isEqualTo("a-model");
      assertThat(settings.conversation()).isZero();
      assertThat(settings.questions()).isEqualTo(40);
      assertThat(settings.maxChapterLength()).isEqualTo(200);
      assertThat(settings.maxTail()).isEqualTo(400);
    }

    @Test
    void every_option_is_read() {
      ChapterLab.Settings settings =
          ChapterLab.parse(
              with(
                  "--conversation",
                  "3",
                  "--questions",
                  "12",
                  "--policy",
                  "session",
                  "--summarizer",
                  "index",
                  "--summary-model",
                  "cheap",
                  "--max-chapter-length",
                  "50",
                  "--max-tail",
                  "90"));

      assertThat(settings.conversation()).isEqualTo(3);
      assertThat(settings.questions()).isEqualTo(12);
      assertThat(settings.policy()).isEqualTo("session");
      assertThat(settings.summarizer()).isEqualTo("index");
      assertThat(settings.summaryModel()).isEqualTo("cheap");
      assertThat(settings.maxChapterLength()).isEqualTo(50);
      assertThat(settings.maxTail()).isEqualTo(90);
    }

    @Test
    void an_unknown_policy_is_refused_with_the_choices() {
      String[] args = with("--policy", "sometimes");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown policy 'sometimes'")
          .hasMessageContaining("every:N")
          .hasMessageContaining("session")
          .hasMessageContaining("hindsight")
          .hasMessageContaining("none");
    }

    @Test
    void an_every_policy_needs_a_positive_number() {
      String[] args = with("--policy", "every:zero");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown policy 'every:zero'");
    }

    @Test
    void an_unknown_summarizer_is_refused_with_the_choices() {
      String[] args = with("--summarizer", "poem");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown summarizer 'poem'")
          .hasMessageContaining("prose")
          .hasMessageContaining("index");
    }

    @Test
    void an_unknown_provider_is_refused_with_the_choices() {
      String[] args = {"--data", "x.json", "--provider", "ollama", "--model", "m"};

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown provider 'ollama'")
          .hasMessageContaining("anthropic")
          .hasMessageContaining("gemini");
    }

    @Test
    void a_local_server_is_accepted_at_its_default_address() {
      ChapterLab.Settings settings =
          ChapterLab.parse(
              new String[] {"--data", "x.json", "--provider", "lmstudio", "--model", "qwen/q"});

      assertThat(settings.provider()).isEqualTo("lmstudio");
      assertThat(settings.baseUrl()).isNull();
      assertThat(settings.model()).isEqualTo("qwen/q");
    }

    @Test
    void a_local_server_may_be_given_another_address_and_a_reasoning_effort() {
      ChapterLab.Settings settings =
          ChapterLab.parse(
              new String[] {
                "--data",
                "x.json",
                "--provider",
                "lmstudio",
                "--model",
                "m",
                "--base-url",
                "http://box:9000/v1",
                "--reasoning-effort",
                "low"
              });

      assertThat(settings.baseUrl()).isEqualTo("http://box:9000/v1");
      assertThat(settings.reasoningEffort()).isEqualTo("low");
    }

    @Test
    void building_a_local_provider_needs_no_key_and_does_not_call_the_server() {
      ChapterLab.Settings settings =
          ChapterLab.parse(
              new String[] {
                "--data",
                "x.json",
                "--provider",
                "lmstudio",
                "--model",
                "m",
                "--reasoning-effort",
                "none"
              });

      assertThat(ChapterLab.provider(settings)).isNotNull();
    }

    @Test
    void anthropic_may_be_told_how_to_think_and_the_results_are_kept_apart() {
      ChapterLab.Settings settings = ChapterLab.parse(with("--thinking", "between_tools"));

      assertThat(settings.thinking()).isEqualTo("between_tools");
      assertThat(settings.describe()).endsWith(", thinking between_tools");
      assertThat(settings.resultsFile()).endsWith("-thinking-between_tools.jsonl");
    }

    @Test
    void with_no_thinking_named_the_settings_and_the_results_file_say_nothing_of_it() {
      ChapterLab.Settings settings = ChapterLab.parse(with("--policy", "every:20"));

      assertThat(settings.thinking()).isNull();
      assertThat(settings.describe()).doesNotContain("thinking");
      assertThat(settings.resultsFile()).doesNotContain("thinking");
    }

    @Test
    void an_unknown_thinking_is_refused_with_the_choices() {
      String[] args = with("--thinking", "enabled");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown thinking 'enabled'")
          .hasMessageContaining("BETWEEN_TOOLS");
    }

    @Test
    void thinking_for_another_provider_is_refused() {
      String[] args = {
        "--data", "x.json", "--provider", "openai", "--model", "m", "--thinking", "adaptive"
      };

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--thinking applies only to --provider anthropic");
    }

    @Test
    void a_base_url_for_a_hosted_provider_is_refused() {
      String[] args = with("--base-url", "http://box:9000/v1");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--base-url applies only to --provider lmstudio");
    }

    @Test
    void an_unknown_reasoning_effort_is_refused_with_the_choices() {
      String[] args = {
        "--data", "x.json", "--provider", "lmstudio", "--model", "m", "--reasoning-effort", "loud"
      };

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown reasoning effort 'loud'")
          .hasMessageContaining("MINIMAL");
    }

    @Test
    void a_fixed_length_longer_than_the_maximum_chapter_is_refused() {
      String[] args = with("--policy", "every:300");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("every:300")
          .hasMessageContaining("--max-chapter-length (200)");
    }

    @Test
    void a_fixed_length_up_to_the_maximum_chapter_is_accepted() {
      assertThat(ChapterLab.parse(with("--policy", "every:200")).policy()).isEqualTo("every:200");
    }

    @Test
    void a_missing_required_option_is_named() {
      String[] args = {"--data", "x.json", "--provider", "openai"};

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--model is required");
    }

    @Test
    void a_tail_no_longer_than_a_chapter_is_refused() {
      String[] args = with("--max-chapter-length", "100", "--max-tail", "100");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("--max-tail (100)")
          .hasMessageContaining("--max-chapter-length (100)");
    }

    @Test
    void an_option_nobody_knows_is_refused() {
      String[] args = with("--colour", "blue");

      assertThatThrownBy(() -> ChapterLab.parse(args))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("unknown option --colour");
    }
  }

  @Nested
  @DisplayName("Reading a verdict")
  class Reading_a_verdict {

    @Test
    void a_plain_yes_or_no_is_understood() {
      assertThat(Grader.parse("yes")).isEqualTo(Grader.Verdict.YES);
      assertThat(Grader.parse("No")).isEqualTo(Grader.Verdict.NO);
    }

    @Test
    void a_decorated_yes_is_understood() {
      assertThat(Grader.parse("**Yes**")).isEqualTo(Grader.Verdict.YES);
      assertThat(Grader.parse("\"yes.\"")).isEqualTo(Grader.Verdict.YES);
      assertThat(Grader.parse("  - Yes, it matches")).isEqualTo(Grader.Verdict.YES);
    }

    @Test
    void a_decorated_no_is_understood() {
      assertThat(Grader.parse("**No.**")).isEqualTo(Grader.Verdict.NO);
    }

    @Test
    void a_reply_that_reasons_first_is_read_by_its_last_word() {
      assertThat(Grader.parse("The given answer names the same month.\n\nyes"))
          .isEqualTo(Grader.Verdict.YES);
      assertThat(Grader.parse("It is vaguer than the correct answer, so: **No.**"))
          .isEqualTo(Grader.Verdict.NO);
    }

    @Test
    void a_reply_that_opens_with_one_word_and_closes_with_the_other_is_not_understood() {
      assertThat(Grader.parse("No contradiction with the correct answer.\n\nyes"))
          .isEqualTo(Grader.Verdict.NOT_UNDERSTOOD);
    }

    @Test
    void a_word_that_only_ends_in_no_is_not_a_verdict() {
      assertThat(Grader.parse("The answer names a piano")).isEqualTo(Grader.Verdict.NOT_UNDERSTOOD);
    }

    @Test
    void a_reply_that_is_neither_is_not_understood() {
      assertThat(Grader.parse("Perhaps")).isEqualTo(Grader.Verdict.NOT_UNDERSTOOD);
      assertThat(Grader.parse("Yesterday")).isEqualTo(Grader.Verdict.NOT_UNDERSTOOD);
      assertThat(Grader.parse("")).isEqualTo(Grader.Verdict.NOT_UNDERSTOOD);
    }
  }

  private static String longConversation(int turns) {
    StringBuilder json = new StringBuilder();
    json.append("{\"conversation\": {\"session_1_date_time\": \"1:00 pm on 1 May, 2023\", ");
    json.append("\"session_1\": [");
    for (int i = 1; i <= turns * 2; i++) {
      if (i > 1) {
        json.append(", ");
      }
      json.append("{\"speaker\": \"")
          .append(i % 2 == 1 ? "Ann" : "Ben")
          .append("\", \"dia_id\": \"D1:")
          .append(i)
          .append("\", \"text\": \"line ")
          .append(i)
          .append("\"}");
    }
    json.append("]}, \"qa\": [{\"question\": \"What did Ann plant?\", \"answer\": \"tomatoes\",");
    json.append(" \"evidence\": [\"D1:1\"], \"category\": 1}]}");
    return json.toString();
  }

  private static InferenceProvider except(
      String system, InferenceResult instead, InferenceProvider otherwise) {
    return (request, narrator) ->
        request.systemPrompt().value().equals(system)
            ? instead
            : otherwise.infer(request, narrator);
  }

  @Nested
  @DisplayName("A run that meets a refusal")
  class A_run_that_meets_a_refusal {

    private final PrintStream quiet =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

    @Test
    void a_refused_answer_is_graded_wrong_without_asking_the_grader(@TempDir Path directory) {
      ScriptedModel model = new ScriptedModel();
      InferenceProvider refusing =
          except(
              LabPrompts.ANSWER_SYSTEM,
              new InferenceResult.Refusal("policy", Usage.unreported("the-model")),
              model);
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              refusing,
              quiet,
              directory,
              Duration.ZERO);

      assertThat(result.asked()).isEqualTo(4);
      assertThat(result.correct()).isZero();
      assertThat(result.unanswered()).isEqualTo(4);
      assertThat(result.notUnderstood()).isZero();
    }

    @Test
    void a_refused_answer_is_recorded_as_no_answer(@TempDir Path directory) throws Exception {
      ScriptedModel model = new ScriptedModel();
      InferenceProvider refusing =
          except(
              LabPrompts.ANSWER_SYSTEM,
              new InferenceResult.Refusal("policy", Usage.unreported("the-model")),
              model);
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.run(
          settings,
          LocomoConversation.read(settings.data(), 0),
          refusing,
          quiet,
          directory,
          Duration.ZERO);

      List<String> lines = Files.readAllLines(directory.resolve(settings.resultsFile()));
      assertThat(lines)
          .hasSize(4)
          .allSatisfy(line -> assertThat(line).contains("\"answer\":\"(no answer)\""));
    }

    @Test
    void a_verdict_nobody_can_read_is_counted_and_graded_wrong(@TempDir Path directory) {
      InferenceProvider hedging =
          except(LabPrompts.GRADE_SYSTEM, answer("Perhaps", 5, 1), new ScriptedModel());
      ChapterLab.Settings settings = settings("session", "prose");
      ByteArrayOutputStream captured = new ByteArrayOutputStream();

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              hedging,
              new PrintStream(captured, true, StandardCharsets.UTF_8),
              directory,
              Duration.ZERO);

      assertThat(result.notUnderstood()).isEqualTo(4);
      assertThat(result.correct()).isZero();
      assertThat(captured.toString(StandardCharsets.UTF_8))
          .contains("verdicts not understood: 4")
          .contains("questions with no answer: 0");
    }

    @Test
    void a_verdict_nobody_can_read_is_printed_and_recorded_with_the_graders_reply(
        @TempDir Path directory) throws Exception {
      InferenceProvider hedging =
          except(
              LabPrompts.GRADE_SYSTEM,
              answer("Partly.\nIt names the month.", 5, 1),
              new ScriptedModel());
      ChapterLab.Settings settings = settings("session", "prose");
      ByteArrayOutputStream captured = new ByteArrayOutputStream();

      ChapterLab.run(
          settings,
          LocomoConversation.read(settings.data(), 0),
          hedging,
          new PrintStream(captured, true, StandardCharsets.UTF_8),
          directory,
          Duration.ZERO);

      assertThat(captured.toString(StandardCharsets.UTF_8))
          .contains("wrong (verdict not understood: Partly. It names the month.)");
      List<String> lines = Files.readAllLines(directory.resolve(settings.resultsFile()));
      assertThat(lines)
          .hasSize(4)
          .allSatisfy(
              line ->
                  assertThat(line)
                      .contains("\"verdict\":\"not_understood\"")
                      .contains("\"grader_reply\":\"Partly.\\nIt names the month.\""));
    }

    @Test
    void a_failed_grading_call_is_recorded_with_why_it_failed(@TempDir Path directory)
        throws Exception {
      InferenceProvider failing =
          except(
              LabPrompts.GRADE_SYSTEM,
              new InferenceResult.Refusal("policy", Usage.unreported("the-model")),
              new ScriptedModel());
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.run(
          settings,
          LocomoConversation.read(settings.data(), 0),
          failing,
          quiet,
          directory,
          Duration.ZERO);

      List<String> lines = Files.readAllLines(directory.resolve(settings.resultsFile()));
      assertThat(lines)
          .hasSize(4)
          .allSatisfy(
              line ->
                  assertThat(line)
                      .contains("\"grader_reply\":\"(the grading call failed: gave up after 4")
                      .contains("Refusal"));
    }

    @Test
    void a_failed_grading_call_is_a_verdict_not_understood(@TempDir Path directory) {
      InferenceProvider failing =
          except(
              LabPrompts.GRADE_SYSTEM,
              new InferenceResult.Refusal("policy", Usage.unreported("the-model")),
              new ScriptedModel());
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              failing,
              quiet,
              directory,
              Duration.ZERO);

      assertThat(result.notUnderstood()).isEqualTo(4);
      assertThat(result.unanswered()).isZero();
    }
  }

  @Nested
  @DisplayName("A run that meets a reply cut off at the output limit")
  class A_run_that_meets_a_reply_cut_off_at_the_output_limit {

    private final PrintStream quiet =
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    private final InferenceResult cutOff =
        new InferenceResult.Truncated(
            List.of(new Block.Text("It began to say")), Usage.unreported("the-model"));

    private InferenceProvider counting(String system, AtomicInteger calls) {
      InferenceProvider cutting = except(system, cutOff, new ScriptedModel());
      return (request, narrator) -> {
        if (request.systemPrompt().value().equals(system)) {
          calls.incrementAndGet();
        }
        return cutting.infer(request, narrator);
      };
    }

    @Test
    void a_cut_off_answer_is_counted_as_no_answer_and_asked_once(@TempDir Path directory) {
      AtomicInteger calls = new AtomicInteger();
      InferenceProvider provider = counting(LabPrompts.ANSWER_SYSTEM, calls);
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              provider,
              quiet,
              directory,
              Duration.ZERO);

      assertThat(result.asked()).isEqualTo(4);
      assertThat(result.unanswered()).isEqualTo(4);
      assertThat(calls).hasValue(4);
    }

    @Test
    void a_cut_off_grade_is_a_verdict_not_understood_graded_once_and_says_why(
        @TempDir Path directory) throws Exception {
      AtomicInteger calls = new AtomicInteger();
      InferenceProvider provider = counting(LabPrompts.GRADE_SYSTEM, calls);
      ChapterLab.Settings settings = settings("session", "prose");

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              provider,
              quiet,
              directory,
              Duration.ZERO);

      assertThat(result.notUnderstood()).isEqualTo(4);
      assertThat(calls).hasValue(4);
      List<String> lines = Files.readAllLines(directory.resolve(settings.resultsFile()));
      assertThat(lines)
          .hasSize(4)
          .allSatisfy(
              line ->
                  assertThat(line)
                      .contains("\"grader_reply\":\"(the grading call failed: ")
                      .contains("cut off at the output limit"));
    }
  }

  @Nested
  @DisplayName("A run under the hindsight policy")
  class A_run_under_the_hindsight_policy {

    @Test
    void the_model_names_the_breaks_and_the_store_ends_up_with_those_chapters(
        @TempDir Path directory) {
      ScriptedModel model =
          new ScriptedModel("The breaks, in exchanges 1 to 40, are [10, 25, 40].");
      LocomoConversation conversation =
          LocomoConversation.of(JsonMapper.builder().build().readTree(longConversation(45)));
      PrintStream quiet =
          new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

      ChapterLab.Result result =
          ChapterLab.run(
              settings("hindsight", "prose"), conversation, model, quiet, directory, Duration.ZERO);

      assertThat(result.turns()).isEqualTo(45);
      assertThat(result.chapters()).isEqualTo(3);
      assertThat(model.hindsights).hasSize(1);
      assertThat(model.summaries).hasSize(3);
      assertThat(
              model.summaries.stream()
                  .map(r -> textOf(r).lines().filter(line -> line.startsWith("user: ")).count())
                  .toList())
          .containsExactly(10L, 15L, 15L);
      assertThat(model.questions)
          .isNotEmpty()
          .allSatisfy(
              request -> {
                assertThat(request.context().summaries()).hasSize(3);
                assertThat(request.context().tail()).hasSize(5);
              });
    }
  }

  @Nested
  @DisplayName("A run")
  class A_run {

    @Test
    void one_chapter_per_session_with_every_question_asked_from_the_summaries(
        @TempDir Path directory) throws Exception {
      ScriptedModel model = new ScriptedModel();

      Run run = run(settings("session", "prose"), model, directory);

      ChapterLab.Result result = run.result;
      assertThat(result.turns()).isEqualTo(5);
      assertThat(result.chapters()).isEqualTo(3);
      assertThat(result.summaryWords()).isEqualTo(21);
      assertThat(result.contextWords()).isEqualTo(21);
      assertThat(result.asked()).isEqualTo(4);
      assertThat(result.correct()).isEqualTo(3);
      assertThat(result.askedByCategory())
          .containsEntry(1, 2)
          .containsEntry(2, 1)
          .containsEntry(4, 1);
      assertThat(result.correctByCategory())
          .containsEntry(1, 2)
          .containsEntry(2, 0)
          .containsEntry(4, 1);
      assertThat(model.summaries).hasSize(3);
      assertThat(model.questions)
          .hasSize(4)
          .allSatisfy(
              request -> {
                assertThat(request.context().summaries()).hasSize(3);
                assertThat(request.context().tail()).isEmpty();
              });
    }

    @Test
    void the_summaries_cover_the_recorded_sessions_in_order(@TempDir Path directory)
        throws Exception {
      ScriptedModel model = new ScriptedModel();

      run(settings("session", "prose"), model, directory);

      List<String> shown = model.summaries.stream().map(ChapterLabTest::textOf).toList();
      assertThat(shown).hasSize(3);
      assertThat(shown.get(0)).contains("planted tomatoes").contains("Lisbon tomorrow");
      assertThat(shown.get(1)).contains("Lisbon was warm").contains("Three hundred");
      assertThat(shown.get(2)).contains("tomatoes are ripe");
    }

    @Test
    void the_report_names_each_figure(@TempDir Path directory) throws Exception {
      Run run = run(settings("session", "prose"), new ScriptedModel(), directory);

      assertThat(run.printed)
          .contains("chapters: 3")
          .contains("summary words: 21")
          .contains("context words per question: 21")
          .contains("correct: 3 of 4")
          .contains("category 1: 2 of 2")
          .contains("category 2: 0 of 1")
          .contains("category 4: 1 of 1")
          .contains("tokens, summaries and cuts: 30 in, 6 out")
          .contains("tokens, answers and grading: 100 in, 16 out")
          .contains("chapter 1 summarised")
          .contains("chapter 3 summarised")
          .contains("policy session");
    }

    @Test
    void a_provider_that_reports_no_cache_counts_is_reported_as_such(@TempDir Path directory)
        throws Exception {
      Run run = run(settings("session", "prose"), new ScriptedModel(), directory);

      assertThat(run.printed).contains("cache: not reported").doesNotContain("read (");
    }

    @Test
    void the_report_says_how_much_of_the_input_was_read_from_and_written_to_the_cache(
        @TempDir Path directory) {
      InferenceResult cached =
          new InferenceResult.Answer(
              List.of(new Block.Text("tomatoes")),
              new Usage(
                  "the-model",
                  Tokens.of(100),
                  Tokens.of(2),
                  Tokens.of(80),
                  Tokens.of(15),
                  Tokens.none()));
      InferenceProvider caching = except(LabPrompts.ANSWER_SYSTEM, cached, new ScriptedModel());
      ChapterLab.Settings settings = settings("session", "prose");
      ByteArrayOutputStream captured = new ByteArrayOutputStream();

      ChapterLab.Result result =
          ChapterLab.run(
              settings,
              LocomoConversation.read(settings.data(), 0),
              caching,
              new PrintStream(captured, true, StandardCharsets.UTF_8),
              directory,
              Duration.ZERO);

      assertThat(result.answerTokens().cacheRead()).isEqualTo(Tokens.of(320));
      assertThat(result.answerTokens().cacheWrite()).isEqualTo(Tokens.of(60));
      assertThat(captured.toString(StandardCharsets.UTF_8))
          .contains("cache: 320 read (")
          .contains("60 written");
    }

    @Test
    void each_question_is_appended_with_its_answer_and_verdict(@TempDir Path directory)
        throws Exception {
      Run run = run(settings("session", "prose"), new ScriptedModel(), directory);

      assertThat(run.recorded)
          .hasSize(4)
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains("\"question\":\"What did Ann plant?\"")
                      .contains("\"answer\":\"tomatoes\"")
                      .contains("\"correct_answer\":\"tomatoes\"")
                      .contains("\"correct\":true"))
          .anySatisfy(
              line ->
                  assertThat(line)
                      .contains("\"question\":\"When did Ben travel to Lisbon?\"")
                      .contains("\"answer\":\"unknown\"")
                      .contains("\"correct\":false"));
    }

    @Test
    void a_fixed_length_leaves_the_open_turns_as_the_tail(@TempDir Path directory)
        throws Exception {
      ScriptedModel model = new ScriptedModel();

      Run run = run(settings("every:2", "prose"), model, directory);

      assertThat(run.result.chapters()).isEqualTo(2);
      assertThat(run.result.contextWords()).isEqualTo(14 + 15);
      assertThat(model.questions)
          .isNotEmpty()
          .allSatisfy(
              request -> {
                assertThat(request.context().summaries()).hasSize(2);
                assertThat(request.context().tail()).hasSize(1);
              });
    }

    @Test
    void the_index_summariser_asks_for_an_index_entry(@TempDir Path directory) throws Exception {
      ScriptedModel model = new ScriptedModel();

      run(settings("every:2", "index"), model, directory);

      assertThat(model.summaries)
          .hasSize(2)
          .allSatisfy(
              request ->
                  assertThat(request.systemPrompt().value()).isEqualTo(LabPrompts.INDEX_SUMMARY));
    }

    @Test
    void summaries_are_written_by_the_summary_model_and_answers_by_the_answering_model(
        @TempDir Path directory) throws Exception {
      ScriptedModel model = new ScriptedModel();

      run(settings("every:2", "prose"), model, directory);

      assertThat(model.summaries)
          .isNotEmpty()
          .allSatisfy(
              request -> assertThat(request.options().modelName()).isEqualTo("the-summary-model"));
      assertThat(model.questions)
          .isNotEmpty()
          .allSatisfy(request -> assertThat(request.options().modelName()).isEqualTo("the-model"));
    }

    @Test
    void with_no_chapters_the_whole_conversation_is_sent(@TempDir Path directory) throws Exception {
      ScriptedModel model = new ScriptedModel();

      Run run = run(settings("none", "prose"), model, directory);

      assertThat(run.result.chapters()).isZero();
      assertThat(run.result.summaryWords()).isZero();
      assertThat(model.summaries).isEmpty();
      assertThat(model.questions)
          .isNotEmpty()
          .allSatisfy(
              request -> {
                assertThat(request.context().summaries()).isEmpty();
                assertThat(request.context().tail()).hasSize(5);
              });
      assertThat(run.result.correct()).isEqualTo(3);
    }

    @Test
    void a_summary_that_cannot_be_written_stops_the_run_with_the_reason(@TempDir Path directory) {
      InferenceProvider failing =
          (request, narrator) ->
              request.systemPrompt().value().equals(ProseSummarizer.PROMPT)
                  ? new InferenceResult.Refusal("policy", Usage.unreported("the-model"))
                  : answer("unused", 1, 1);
      ChapterLab.Settings settings = settings("every:2", "prose");
      LocomoConversation conversation = LocomoConversation.read(settings.data(), 0);
      PrintStream quiet =
          new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);

      assertThatThrownBy(
              () ->
                  ChapterLab.run(settings, conversation, failing, quiet, directory, Duration.ZERO))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("the summary of turns");
    }
  }
}
