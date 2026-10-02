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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jwcarman.codec.jackson.JacksonCodecFactory;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.DirectHarness;
import org.jwcarman.nessy.api.Outcome;
import org.jwcarman.nessy.api.ProviderId;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.SystemPrompt;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.api.turn.TurnResult;
import org.jwcarman.nessy.backend.inmemory.InMemoryDirectBackend;
import org.jwcarman.nessy.engine.chapter.ProseSummarizer;
import org.jwcarman.nessy.engine.chapter.Transcripts;
import org.jwcarman.nessy.engine.harness.direct.DefaultDirectHarnessFactory;
import org.jwcarman.nessy.engine.schema.VictoolsJsonSchemaGenerator;
import org.jwcarman.nessy.inference.InferenceContext;
import org.jwcarman.nessy.inference.InferenceOptions;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.Toolset;
import org.jwcarman.nessy.inference.anthropic.AnthropicInferenceProvider;
import org.jwcarman.nessy.inference.gemini.GeminiInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiChatInferenceProvider;
import org.jwcarman.nessy.inference.openai.OpenAiChatProviderConfig;
import org.jwcarman.nessy.inference.openai.OpenAiProperties;
import org.jwcarman.nessy.inference.openai.OpenAiReasoningEffort;
import tools.jackson.databind.json.JsonMapper;

/**
 * A command-line lab for trying ways of cutting and summarising a long conversation.
 *
 * <p>It replays one recorded conversation through the real engine, turn by turn, so that the engine
 * cuts it into chapters under the chosen policy and a real model writes each chapter's summary with
 * the chosen summariser. Then it asks the questions the recording answers, from the context the
 * engine built (the summaries, then the turns after the last one) and nothing else, has a model
 * grade each answer, and prints how many were right and how large that context was.
 *
 * <p>The recorded replies are played back by a scripted provider that answers instantly, so what
 * the real model is paid for is the work being compared: the summaries, the cuts, the answers and
 * the grading.
 *
 * <p>The model is reached as the engine reaches it, through an {@link InferenceProvider} built from
 * the environment: {@code ANTHROPIC_API_KEY}, {@code OPENAI_API_KEY}, {@code XAI_API_KEY} or {@code
 * GEMINI_API_KEY} (or {@code GOOGLE_API_KEY}). A key is never printed.
 */
public final class ChapterLab {

  /** The providers {@code --provider} accepts. */
  static final List<String> PROVIDERS = List.of("anthropic", "openai", "xai", "gemini", "lmstudio");

  /** Where a local OpenAI-compatible server listens unless {@code --base-url} says otherwise. */
  static final String LMSTUDIO_URL = "http://localhost:1234/v1";

  /** The providers that speak the OpenAI chat wire, and so take {@code --reasoning-effort}. */
  private static final List<String> OPENAI_WIRE = List.of("openai", "xai", "lmstudio");

  /** The policies {@code --policy} accepts, as the usage message spells them. */
  static final String POLICIES = "every:N, session, hindsight or none";

  /** The summarisers {@code --summarizer} accepts. */
  static final List<String> SUMMARIZERS = List.of("prose", "index");

  private static final AgentType TYPE = new AgentType("chapter-lab");
  private static final String RECORDED = "recorded";

  /** What a question that the model never answered is recorded as. */
  static final String NO_ANSWER = "(no answer)";

  private static final Duration TRANSPORT = Duration.ofMinutes(6);
  private static final int SUMMARY_TOKENS = 4000;
  private static final int ANSWER_TOKENS = 4000;
  private static final int GRADE_TOKENS = 2000;
  private static final String USAGE =
      """
      usage: ChapterLab --data FILE --provider (anthropic|openai|xai|gemini|lmstudio) --model NAME
                        [--conversation N] [--questions N] [--policy every:N|session|hindsight|none]
                        [--summarizer prose|index] [--summary-model NAME]
                        [--max-chapter-length N] [--max-tail N]
                        [--base-url URL (lmstudio)] [--reasoning-effort VALUE]""";

  private ChapterLab() {}

  /**
   * What one run was asked to do.
   *
   * @param policy {@code every:N}, {@code session}, {@code hindsight} or {@code none}
   * @param summaryModel the model that writes summaries and cuts; the answering model by default
   * @param baseUrl the address of a local server, or null for its default
   * @param reasoningEffort an {@link OpenAiReasoningEffort} name, or null to send none
   */
  record Settings(
      File data,
      int conversation,
      int questions,
      String provider,
      String model,
      String policy,
      String summarizer,
      String summaryModel,
      int maxChapterLength,
      int maxTail,
      String baseUrl,
      String reasoningEffort) {

    Settings(
        File data,
        int conversation,
        int questions,
        String provider,
        String model,
        String policy,
        String summarizer,
        String summaryModel,
        int maxChapterLength,
        int maxTail) {
      this(
          data,
          conversation,
          questions,
          provider,
          model,
          policy,
          summarizer,
          summaryModel,
          maxChapterLength,
          maxTail,
          null,
          null);
    }

    boolean chapters() {
      return !policy.equals("none");
    }

    /** The settings as one line. */
    String describe() {
      return ("provider %s, model %s, summary model %s, conversation %d, %d questions, policy %s,"
              + " summarizer %s, max chapter length %d, max tail %d")
          .formatted(
              provider,
              model,
              summaryModel,
              conversation,
              questions,
              policy,
              summarizer,
              maxChapterLength,
              maxTail);
    }

    /** Where the questions, answers and verdicts are appended. */
    String resultsFile() {
      return "chapter-lab-%s-%s-%s-%s.jsonl"
          .formatted(provider, safe(model), safe(policy), summarizer);
    }

    private static String safe(String name) {
      return name.replaceAll("[^A-Za-z0-9._-]", "-");
    }
  }

  /**
   * What a run found out.
   *
   * @param turns how many turns were replayed
   * @param chapters how many chapters were closed and summarised
   * @param summaryWords the words in all the summaries
   * @param contextWords the words in the context every question was asked with
   * @param askedByCategory how many questions of each category were asked
   * @param correctByCategory how many of each category were answered correctly
   * @param unanswered how many questions got no answer from the model, graded as wrong
   * @param notUnderstood how many verdicts could not be read, graded as wrong
   */
  record Result(
      int turns,
      int chapters,
      int summaryWords,
      int contextWords,
      Map<Integer, Integer> askedByCategory,
      Map<Integer, Integer> correctByCategory,
      long summaryInputTokens,
      long summaryOutputTokens,
      long answerInputTokens,
      long answerOutputTokens,
      int unanswered,
      int notUnderstood) {

    int asked() {
      return askedByCategory.values().stream().mapToInt(Integer::intValue).sum();
    }

    int correct() {
      return correctByCategory.values().stream().mapToInt(Integer::intValue).sum();
    }
  }

  public static void main(String[] args) throws IOException {
    Settings settings;
    try {
      settings = parse(args);
    } catch (IllegalArgumentException refused) {
      System.err.println(refused.getMessage());
      System.err.println(USAGE);
      System.exit(2);
      return;
    }
    LocomoConversation conversation;
    InferenceProvider provider;
    try {
      conversation = LocomoConversation.read(settings.data(), settings.conversation());
      provider = provider(settings);
    } catch (IllegalArgumentException | IllegalStateException refused) {
      System.err.println(refused.getMessage());
      System.exit(2);
      return;
    }
    try {
      Result result = run(settings, conversation, provider, System.out, Path.of("."));
      System.out.println("done: " + result.correct() + " of " + result.asked());
    } catch (RuntimeException failed) {
      System.err.println("the run failed: " + failed.getMessage());
      System.exit(1);
      return;
    }
    System.exit(0);
  }

  /**
   * Reads the command line.
   *
   * @throws IllegalArgumentException saying what is wrong and, for a choice, the choices
   */
  static Settings parse(String[] args) {
    Map<String, String> given = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      String name = args[i];
      if (!name.startsWith("--")) {
        throw new IllegalArgumentException("expected an option, found: " + name);
      }
      if (i + 1 >= args.length) {
        throw new IllegalArgumentException(name + " needs a value");
      }
      given.put(name.substring(2), args[i + 1]);
    }
    List<String> known =
        List.of(
            "data",
            "conversation",
            "questions",
            "provider",
            "model",
            "policy",
            "summarizer",
            "summary-model",
            "max-chapter-length",
            "max-tail",
            "base-url",
            "reasoning-effort");
    for (String name : given.keySet()) {
      if (!known.contains(name)) {
        throw new IllegalArgumentException("unknown option --" + name + "; options are " + known);
      }
    }
    String provider = required(given, "provider");
    if (!PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException(
          "unknown provider '%s'; choose one of %s".formatted(provider, PROVIDERS));
    }
    String policy = given.getOrDefault("policy", "every:20");
    checkPolicy(policy);
    String summarizer = given.getOrDefault("summarizer", "prose");
    if (!SUMMARIZERS.contains(summarizer)) {
      throw new IllegalArgumentException(
          "unknown summarizer '%s'; choose one of %s".formatted(summarizer, SUMMARIZERS));
    }
    String model = required(given, "model");
    String baseUrl = given.get("base-url");
    if (baseUrl != null && !provider.equals("lmstudio")) {
      throw new IllegalArgumentException("--base-url applies only to --provider lmstudio");
    }
    String effort = given.get("reasoning-effort");
    if (effort != null) {
      if (!OPENAI_WIRE.contains(provider)) {
        throw new IllegalArgumentException(
            "--reasoning-effort applies only to the providers " + OPENAI_WIRE);
      }
      effort(effort);
    }
    Settings settings =
        new Settings(
            new File(required(given, "data")),
            number(given, "conversation", 0),
            number(given, "questions", 40),
            provider,
            model,
            policy,
            summarizer,
            given.getOrDefault("summary-model", model),
            number(given, "max-chapter-length", 200),
            number(given, "max-tail", 400),
            baseUrl,
            effort);
    if (policy.startsWith("every:")
        && Integer.parseInt(policy.substring("every:".length())) > settings.maxChapterLength()) {
      throw new IllegalArgumentException(
          ("--policy %s is longer than --max-chapter-length (%d), so the engine would cut first;"
                  + " raise --max-chapter-length (and --max-tail above it)")
              .formatted(policy, settings.maxChapterLength()));
    }
    if (settings.chapters() && settings.maxTail() <= settings.maxChapterLength()) {
      throw new IllegalArgumentException(
          "--max-tail (%d) must be greater than --max-chapter-length (%d)"
              .formatted(settings.maxTail(), settings.maxChapterLength()));
    }
    return settings;
  }

  private static void checkPolicy(String policy) {
    boolean fine = policy.equals("session") || policy.equals("hindsight") || policy.equals("none");
    if (policy.startsWith("every:")) {
      try {
        fine = Integer.parseInt(policy.substring("every:".length())) >= 1;
      } catch (NumberFormatException notANumber) {
        fine = false;
      }
    }
    if (!fine) {
      throw new IllegalArgumentException(
          "unknown policy '%s'; choose %s".formatted(policy, POLICIES));
    }
  }

  private static String required(Map<String, String> given, String name) {
    String value = given.get(name);
    if (value == null) {
      throw new IllegalArgumentException("--" + name + " is required");
    }
    return value;
  }

  private static int number(Map<String, String> given, String name, int fallback) {
    String value = given.get(name);
    if (value == null) {
      return fallback;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0) {
        throw new IllegalArgumentException("--%s must not be negative: %s".formatted(name, value));
      }
      return parsed;
    } catch (NumberFormatException notANumber) {
      throw new IllegalArgumentException("--%s must be a whole number: %s".formatted(name, value));
    }
  }

  /**
   * The provider named, built from the environment. The message of a missing key names the variable
   * to set and never prints one.
   */
  static InferenceProvider provider(String name) {
    return provider(name, null, null);
  }

  /** The provider {@code settings} names, with its address and reasoning effort if given. */
  static InferenceProvider provider(Settings settings) {
    return provider(settings.provider(), settings.baseUrl(), settings.reasoningEffort());
  }

  private static OpenAiReasoningEffort effort(String name) {
    try {
      return OpenAiReasoningEffort.valueOf(name.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "unknown reasoning effort '%s'; choose one of %s"
              .formatted(name, List.of(OpenAiReasoningEffort.values())));
    }
  }

  private static InferenceProvider provider(String name, String baseUrl, String reasoningEffort) {
    return switch (name) {
      case "anthropic" -> AnthropicInferenceProvider.of(c -> c.fromEnv().timeout(TRANSPORT));
      case "openai" ->
          OpenAiChatInferenceProvider.of(
              c -> {
                c.fromEnv().timeout(TRANSPORT);
                withEffort(c, reasoningEffort);
              });
      case "lmstudio" ->
          OpenAiChatInferenceProvider.of(
              c -> {
                // A local server ignores the key, but the client insists on one.
                c.apiKey("lm-studio")
                    .baseUrl(baseUrl == null ? LMSTUDIO_URL : baseUrl)
                    .vendor("lmstudio")
                    .timeout(TRANSPORT);
                withEffort(c, reasoningEffort);
              });
      case "xai" ->
          OpenAiChatInferenceProvider.of(
              c -> {
                c.apiKey(environment("XAI_API_KEY"))
                    .baseUrl("https://api.x.ai/v1")
                    .vendor("x_ai")
                    .timeout(TRANSPORT);
                withEffort(c, reasoningEffort);
              });
      case "gemini" -> GeminiInferenceProvider.of(c -> c.fromEnv().timeout(TRANSPORT));
      default ->
          throw new IllegalArgumentException(
              "unknown provider '%s'; choose one of %s".formatted(name, PROVIDERS));
    };
  }

  private static void withEffort(OpenAiChatProviderConfig config, String reasoningEffort) {
    if (reasoningEffort != null) {
      config.property(OpenAiProperties.REASONING_EFFORT, effort(reasoningEffort));
    }
  }

  private static String environment(String variable) {
    String value = System.getenv(variable);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(variable + " is not set");
    }
    return value;
  }

  /**
   * Replays, cuts, asks and grades.
   *
   * @param real the model that writes summaries, answers and grades
   * @param out where progress and the result are printed
   * @param resultsDirectory where the JSON lines of questions and verdicts are appended
   */
  static Result run(
      Settings settings,
      LocomoConversation conversation,
      InferenceProvider real,
      PrintStream out,
      Path resultsDirectory) {
    return run(settings, conversation, real, out, resultsDirectory, Models.DEFAULT_PAUSE);
  }

  /** As above, pausing {@code retryPause} before a failed summary is written again. */
  static Result run(
      Settings settings,
      LocomoConversation conversation,
      InferenceProvider real,
      PrintStream out,
      Path resultsDirectory,
      Duration retryPause) {
    Counting writing = new Counting(real);
    Counting answering = new Counting(real);
    Replay replay = new Replay();
    JsonMapper mapper = JsonMapper.builder().build();
    InMemoryDirectBackend backend = new InMemoryDirectBackend(new JacksonCodecFactory(mapper));
    try (DefaultDirectHarnessFactory factory =
        DefaultDirectHarnessFactory.of(
            f ->
                f.backend(backend)
                    .provider(ProviderId.of(RECORDED), replay)
                    .schemas(new VictoolsJsonSchemaGenerator())
                    .mapper(mapper))) {
      Watch watch = new Watch(out, retryPause);
      InferenceOptions summaryOptions =
          new InferenceOptions(settings.summaryModel(), SUMMARY_TOKENS);
      InferenceOptions answerOptions = new InferenceOptions(settings.model(), ANSWER_TOKENS);
      out.println(settings.describe());
      out.println(
          "replaying %d turns of conversation %d"
              .formatted(conversation.turns().size(), settings.conversation()));

      AgentId agent = AgentId.random();
      DirectHarness<String, String> harness =
          factory.create(
              TYPE,
              c -> {
                c.systemPrompt("You are one half of a long conversation between two people.")
                    .inputRenderer(said -> List.of(new Block.Text(said)))
                    .inference(
                        in ->
                            in.provider(ProviderId.of(RECORDED))
                                .model(RECORDED)
                                .context(
                                    ctx -> {
                                      if (settings.chapters()) {
                                        ctx.maxChapterLength(settings.maxChapterLength())
                                            .maxTail(settings.maxTail());
                                      } else {
                                        ctx.withoutChapters().maxTail(settings.maxTail());
                                      }
                                    }));
                if (settings.chapters()) {
                  c.chapterPolicy(
                          watch.watching(
                              policy(settings, factory, replay, writing, summaryOptions)))
                      .summarizer(
                          watch.reporting(summarizer(settings, factory, writing, summaryOptions)));
                }
              });

      replay(conversation, harness, agent, replay, settings, watch, backend, out);

      List<Summary> summaries = backend.chapters().summaries(TYPE, agent);
      Optional<TurnId> through =
          summaries.isEmpty()
              ? Optional.empty()
              : Optional.of(summaries.getLast().chapter().through());
      List<Turn> tail = tail(factory, agent, through);
      int summaryWords = summaries.stream().mapToInt(s -> words(s.text())).sum();
      int contextWords = summaryWords + tail.stream().mapToInt(ChapterLab::contentWords).sum();
      out.println(
          "asking %d questions with %d chapter summaries and %d turns in view"
              .formatted(
                  Math.min(settings.questions(), conversation.questions().size()),
                  summaries.size(),
                  tail.size()));

      Map<Integer, Integer> asked = new TreeMap<>();
      Map<Integer, Integer> correct = new TreeMap<>();
      Grader grader = new Grader(answering, answerOptions, retryPause);
      int unanswered = 0;
      int notUnderstood = 0;
      Path results = resultsDirectory.resolve(settings.resultsFile());
      List<LocomoConversation.Question> questions =
          conversation.sample(settings.questions(), settings.conversation());
      long nextId = nextId(summaries, tail);
      for (int i = 0; i < questions.size(); i++) {
        LocomoConversation.Question question = questions.get(i);
        Optional<String> said =
            Models.tryText(
                answering,
                new InferenceRequest(
                    new SystemPrompt(LabPrompts.ANSWER_SYSTEM),
                    new InferenceContext(
                        summaries,
                        tail,
                        List.of(),
                        List.of(),
                        Models.asking(nextId, LabPrompts.question(question.text())),
                        List.of()),
                    Toolset.none(),
                    answerOptions),
                retryPause);
        String answer = said.orElse(NO_ANSWER);
        Grader.Verdict verdict =
            said.isPresent()
                ? grader.grade(question.text(), question.answer(), answer)
                : Grader.Verdict.NO;
        boolean right = verdict == Grader.Verdict.YES;
        if (said.isEmpty()) {
          unanswered++;
        }
        if (verdict == Grader.Verdict.NOT_UNDERSTOOD) {
          notUnderstood++;
        }
        asked.merge(question.category(), 1, Integer::sum);
        correct.merge(question.category(), right ? 1 : 0, Integer::sum);
        out.println(
            "question %d of %d: %s"
                .formatted(
                    i + 1,
                    questions.size(),
                    verdict == Grader.Verdict.NOT_UNDERSTOOD
                        ? "wrong (verdict not understood)"
                        : right ? "correct" : "wrong"));
        append(mapper, results, question, answer, right, verdict);
      }

      Result result =
          new Result(
              conversation.turns().size(),
              summaries.size(),
              summaryWords,
              contextWords,
              asked,
              correct,
              writing.inputTokens(),
              writing.outputTokens(),
              answering.inputTokens(),
              answering.outputTokens(),
              unanswered,
              notUnderstood);
      print(result, settings, results, out);
      return result;
    }
  }

  private static ChapterPolicy policy(
      Settings settings,
      DefaultDirectHarnessFactory factory,
      Replay replay,
      InferenceProvider writing,
      InferenceOptions summaryOptions) {
    if (settings.policy().equals("session")) {
      return LabPolicies.session(replay.sessionEnds());
    }
    if (settings.policy().equals("hindsight")) {
      return LabPolicies.hindsight(factory.histories(), writing, summaryOptions);
    }
    return ChapterPolicy.every(Integer.parseInt(settings.policy().substring("every:".length())));
  }

  private static Summarizer summarizer(
      Settings settings,
      DefaultDirectHarnessFactory factory,
      InferenceProvider writing,
      InferenceOptions summaryOptions) {
    return settings.summarizer().equals("index")
        ? new ProseSummarizer(
            factory.histories(), writing, summaryOptions, LabPrompts.INDEX_SUMMARY)
        : new ProseSummarizer(factory.histories(), writing, summaryOptions);
  }

  private static void replay(
      LocomoConversation conversation,
      DirectHarness<String, String> harness,
      AgentId agent,
      Replay replay,
      Settings settings,
      Watch watch,
      InMemoryDirectBackend backend,
      PrintStream out) {
    List<LocomoConversation.Recorded> turns = conversation.turns();
    for (int i = 0; i < turns.size(); i++) {
      LocomoConversation.Recorded turn = turns.get(i);
      replay.next(turn.reply(), conversation.endsSession(i));
      Outcome<String> outcome = harness.ask(agent, turn.input());
      if (!(outcome instanceof Outcome.Answered<String>)) {
        throw new IllegalStateException("turn %d did not complete: %s".formatted(i + 1, outcome));
      }
      if (settings.chapters()) {
        watch.settle(backend.chapters(), TYPE, agent, replay.lastTurn());
      }
      if ((i + 1) % 50 == 0) {
        out.println("replayed %d of %d turns".formatted(i + 1, turns.size()));
      }
    }
  }

  private static List<Turn> tail(
      DefaultDirectHarnessFactory factory, AgentId agent, Optional<TurnId> through) {
    List<TurnId> open = factory.histories().forAgent(TYPE, agent).completedAfter(through);
    if (open.isEmpty()) {
      return List.of();
    }
    return factory.histories().forAgent(TYPE, agent).turnsBetween(open.getFirst(), open.getLast());
  }

  private static long nextId(List<Summary> summaries, List<Turn> tail) {
    long last = summaries.isEmpty() ? 0 : summaries.getLast().chapter().through().value();
    return Math.max(last, tail.isEmpty() ? 0 : tail.getLast().id().value()) + 1;
  }

  /** The words a turn says, its input and its reply and nothing the lab adds around them. */
  private static int contentWords(Turn turn) {
    int reply =
        turn.result() instanceof TurnResult.Answered(var blocks)
            ? words(Transcripts.text(blocks))
            : 0;
    return words(Transcripts.text(turn.input().blocks())) + reply;
  }

  private static int words(String text) {
    String stripped = text.strip();
    return stripped.isEmpty() ? 0 : stripped.split("\\s+").length;
  }

  private static void append(
      JsonMapper mapper,
      Path file,
      LocomoConversation.Question question,
      String answer,
      boolean right,
      Grader.Verdict verdict) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("question", question.text());
    line.put("category", question.category());
    line.put("answer", answer);
    line.put("correct_answer", question.answer());
    line.put("correct", right);
    line.put("verdict", verdict.name().toLowerCase(Locale.ROOT));
    try {
      Files.writeString(
          file,
          mapper.writeValueAsString(line) + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private static void print(Result result, Settings settings, Path results, PrintStream out) {
    out.println();
    out.println("settings: " + settings.describe());
    out.println("chapters: " + result.chapters());
    out.println("summary words: " + result.summaryWords());
    out.println("context words per question: " + result.contextWords());
    out.println("correct: %d of %d".formatted(result.correct(), result.asked()));
    out.println("questions with no answer: " + result.unanswered());
    out.println("verdicts not understood: " + result.notUnderstood());
    result
        .askedByCategory()
        .forEach(
            (category, asked) ->
                out.println(
                    "  category %d: %d of %d"
                        .formatted(category, result.correctByCategory().get(category), asked)));
    out.println(
        "tokens, summaries and cuts: %d in, %d out"
            .formatted(result.summaryInputTokens(), result.summaryOutputTokens()));
    out.println(
        "tokens, answers and grading: %d in, %d out"
            .formatted(result.answerInputTokens(), result.answerOutputTokens()));
    out.println("questions and verdicts appended to " + results);
  }
}
