package org.jwcarman.nessy.memory.episodic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Ambient;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolResult;

@DisplayName("The episode tools")
class EpisodeToolsTest {

  private final JdbcEpisodes episodes =
      JdbcEpisodes.create(c -> c.dataSource(Calls.database()).agentType(Calls.TYPE));
  private final Tool<EpisodeTools.BeginEpisode> begin = EpisodeTools.begin(episodes);
  private final Tool<EpisodeTools.RecallEpisode> recall = EpisodeTools.recall(episodes);
  private final AmbientSource index = EpisodeTools.index(episodes);

  private static <I> ToolResult run(Tool<I> tool, AgentId agent, long turn, I input) {
    return ((Awaited.Ready<ToolResult>) tool.call(Calls.by(agent, turn, input))).value();
  }

  private static String text(ToolResult result) {
    return ((Block.Text) ((ToolResult.Success) result).blocks().getFirst()).text();
  }

  private static String text(Optional<Ambient> ambient) {
    return ((Block.Text) ambient.orElseThrow().content().getFirst()).text();
  }

  @Test
  void beginning_records_the_boundary_at_the_turn_of_the_call() {
    AgentId agent = Calls.agent();

    ToolResult first =
        run(begin, agent, 1, new EpisodeTools.BeginEpisode("greetings", "the start"));
    ToolResult second =
        run(begin, agent, 6, new EpisodeTools.BeginEpisode("the task", "new subject"));

    assertThat(text(first)).isEqualTo("Began episode 1, 'greetings'.");
    assertThat(text(second)).isEqualTo("Began episode 2, 'the task'.");
    assertThat(episodes.all(agent))
        .extracting(Episode::from, Episode::through)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(new TurnId(1), new TurnId(5)),
            org.assertj.core.groups.Tuple.tuple(new TurnId(6), null));
    assertThat(begin.name().value()).isEqualTo("begin_episode");
    assertThat(begin.description()).contains("subject changes");
  }

  @Test
  void bad_arguments_come_back_as_a_failure_the_model_can_read() {
    AgentId agent = Calls.agent();

    ToolResult result = run(begin, agent, 1, new EpisodeTools.BeginEpisode(" ", "why"));

    assertThat(result).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) result).message()).contains("title");
    assertThat(episodes.all(agent)).isEmpty();
  }

  @Test
  void recalling_reads_a_summary_or_says_why_there_is_none() {
    AgentId agent = Calls.agent();
    episodes.begin(agent, new TurnId(1), "greetings", "start");
    episodes.begin(agent, new TurnId(4), "the task", "next");
    episodes.begin(agent, new TurnId(9), "the wrap-up", "next");
    episodes.summarize(agent, 1, "hello was said");

    assertThat(text(run(recall, agent, 10, new EpisodeTools.RecallEpisode(1))))
        .isEqualTo("Episode 1, greetings: hello was said");
    assertThat(text(run(recall, agent, 10, new EpisodeTools.RecallEpisode(2))))
        .contains("not been summarised yet");
    assertThat(text(run(recall, agent, 10, new EpisodeTools.RecallEpisode(3))))
        .contains("is the current one");
    ToolResult missing = run(recall, agent, 10, new EpisodeTools.RecallEpisode(7));
    assertThat(missing).isInstanceOf(ToolResult.Failure.class);
    assertThat(((ToolResult.Failure) missing).message()).contains("no episode numbered 7");
    assertThat(recall.name().value()).isEqualTo("recall_episode");
  }

  @Test
  void the_index_lists_every_episode_and_says_nothing_when_there_are_none() {
    AgentId agent = Calls.agent();
    assertThat(index.forAgent(agent)).isEmpty();

    episodes.begin(agent, new TurnId(1), "greetings", "start");
    episodes.begin(agent, new TurnId(4), "the task", "next");
    episodes.begin(agent, new TurnId(9), "the wrap-up", "next");
    episodes.summarize(agent, 1, "Saying hello", "hello was said");

    Optional<Ambient> ambient = index.forAgent(agent);
    assertThat(ambient.map(Ambient::kind)).contains("episodes");
    assertThat(text(ambient).lines().toList())
        .containsSubsequence(
            List.of(
                "- 1. Saying hello (begun as \"greetings\") (turns 1..3)",
                "- 2. the task (turns 4..8, not yet summarised)",
                "- 3. the wrap-up (current, since turn 9)"));
    assertThat(text(ambient)).contains("recall_episode").contains("begin_episode");
  }
}
