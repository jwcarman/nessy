package org.jwcarman.nessy.memory.episodic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Observation;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.embedding.Embedder;

@DisplayName("The episode store")
class JdbcEpisodesTest {

  private static final DataSource DATABASE = Calls.database();

  private static JdbcEpisodes store(Embedder embedder, int shown) {
    return JdbcEpisodes.create(
        c -> c.dataSource(DATABASE).agentType(Calls.TYPE).embedder(embedder).shown(shown));
  }

  private static Turn asking(String text) {
    return new Turn(
        new TurnId(99),
        new Observation(new Seq(99), List.of(new Block.Text(text))),
        List.of(),
        null,
        0);
  }

  private static List<Integer> numbers(List<Summary> shown) {
    return shown.stream()
        .map(s -> ((Block.Text) s.content().getFirst()).text())
        .map(text -> Integer.parseInt(text.substring("Episode ".length(), text.indexOf(','))))
        .toList();
  }

  /** Four summarised episodes on four subjects, then a fifth still open. */
  private static AgentId story(JdbcEpisodes store) {
    AgentId agent = Calls.agent();
    String[] subjects = {"cats", "dogs", "birds", "fish"};
    for (int i = 0; i < subjects.length; i++) {
      store.begin(agent, new TurnId(i * 10L + 1), subjects[i], "a new subject");
    }
    store.begin(agent, new TurnId(41), "open", "still going");
    for (int i = 0; i < subjects.length; i++) {
      store.summarize(agent, i + 1, "all about " + subjects[i] + " and more " + subjects[i]);
    }
    return agent;
  }

  @Nested
  class Beginning {

    private final JdbcEpisodes store = store(null, 5);

    @Test
    void the_first_episode_opens_at_the_turn_and_closes_when_the_next_begins() {
      AgentId agent = Calls.agent();

      Episode first = store.begin(agent, new TurnId(1), "greetings", "the start");
      assertThat(first.number()).isEqualTo(1);
      assertThat(first.open()).isTrue();
      assertThat(store.open(agent)).contains(first);
      assertThat(store.unsummarized(agent)).isEmpty();

      Episode second = store.begin(agent, new TurnId(4), "the task", "a new subject");
      assertThat(second.number()).isEqualTo(2);
      assertThat(store.all(agent))
          .extracting(Episode::through)
          .containsExactly(new TurnId(3), null);
      assertThat(store.unsummarized(agent)).extracting(Episode::number).containsExactly(1);
      assertThat(store.find(agent, 2)).contains(second);
      assertThat(store.find(agent, 3)).isEmpty();
    }

    @Test
    void begun_twice_in_one_turn_renames_rather_than_opening_an_empty_episode() {
      AgentId agent = Calls.agent();
      store.begin(agent, new TurnId(1), "first thought", "the start");

      Episode renamed = store.begin(agent, new TurnId(1), "second thought", "better");

      assertThat(renamed.number()).isEqualTo(1);
      assertThat(store.all(agent)).hasSize(1);
      assertThat(store.open(agent).map(Episode::title)).contains("second thought");
      assertThat(store.open(agent).map(Episode::reason)).contains("better");
    }

    @Test
    void a_blank_title_or_reason_is_refused() {
      AgentId agent = Calls.agent();
      TurnId at = new TurnId(1);
      assertThatThrownBy(() -> store.begin(agent, at, " ", "why"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("title");
      assertThatThrownBy(() -> store.begin(agent, at, "title", null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
      assertThat(store.all(agent)).isEmpty();
    }
  }

  @Nested
  class Summarising {

    @Test
    void a_summary_is_written_once_and_never_over() {
      JdbcEpisodes store = store(null, 5);
      AgentId agent = Calls.agent();
      store.begin(agent, new TurnId(1), "one", "start");
      store.begin(agent, new TurnId(5), "two", "next");

      assertThat(store.summarize(agent, 1, "the first")).isTrue();
      assertThat(store.summarize(agent, 1, "again")).isFalse();
      assertThat(store.summarize(agent, 2, "still open")).isFalse();
      assertThatThrownBy(() -> store.summarize(agent, 1, " "))
          .isInstanceOf(IllegalArgumentException.class);

      assertThat(store.find(agent, 1).map(Episode::summary)).contains("the first");
      assertThat(store.unsummarized(agent)).isEmpty();
    }

    @Test
    void with_an_embedder_the_summary_is_embedded_as_it_is_written() {
      KeywordEmbedder embedder = new KeywordEmbedder("kw", "cats");
      JdbcEpisodes store = store(embedder, 5);
      AgentId agent = Calls.agent();
      store.begin(agent, new TurnId(1), "one", "start");
      store.begin(agent, new TurnId(5), "two", "next");

      store.summarize(agent, 1, "cats everywhere");

      assertThat(embedder.embedded).containsExactly("cats everywhere");
      assertThat(store.embedder()).contains(embedder);
    }
  }

  @Nested
  class Showing {

    @Test
    void only_the_summarised_prefix_is_shown_and_the_tail_begins_after_it() {
      JdbcEpisodes store = store(null, 5);
      AgentId agent = Calls.agent();
      store.begin(agent, new TurnId(1), "one", "start");
      store.begin(agent, new TurnId(4), "two", "next");
      store.begin(agent, new TurnId(9), "three", "next");
      store.begin(agent, new TurnId(12), "four", "next");
      store.summarize(agent, 1, "the first");
      // Two is not summarised yet; three is, but sits behind the hole.
      store.summarize(agent, 3, "the third");

      List<Summary> shown = store.forAgent(agent);

      assertThat(shown).hasSize(1);
      assertThat(shown.getFirst().from()).isEqualTo(new TurnId(1));
      assertThat(shown.getFirst().through()).isEqualTo(new TurnId(3));
      assertThat(shown.getFirst().content())
          .containsExactly(new Block.Text("Episode 1, one: the first"));
      assertThat(store.summarizedThrough(agent)).contains(new TurnId(3));
      assertThat(store.summarizedThrough(Calls.agent())).isEmpty();
    }

    @Test
    void without_an_embedder_the_most_recent_are_shown() {
      JdbcEpisodes store = store(null, 2);
      AgentId agent = story(store);

      assertThat(numbers(store.forAgent(agent))).containsExactly(3, 4);
      assertThat(numbers(store.forAgent(agent, asking("tell me about cats"))))
          .containsExactly(3, 4);
      assertThat(store.summarizedThrough(agent)).contains(new TurnId(40));
    }

    @Test
    void with_an_embedder_the_most_relevant_join_the_most_recent_in_story_order() {
      KeywordEmbedder embedder = new KeywordEmbedder("kw", "cats", "dogs", "birds", "fish");
      JdbcEpisodes store = store(embedder, 2);
      AgentId agent = story(store);

      assertThat(numbers(store.forAgent(agent, asking("tell me about cats"))))
          .containsExactly(1, 4);
      assertThat(numbers(store.forAgent(agent, asking("dogs, please")))).containsExactly(2, 4);
      assertThat(numbers(store.forAgent(agent, asking("any birds?")))).containsExactly(3, 4);
      // The most recent is shown whatever the question, and the ranking never moves the tail.
      assertThat(numbers(store.forAgent(agent, asking("fish again")))).endsWith(4).hasSize(2);
      assertThat(store.summarizedThrough(agent)).contains(new TurnId(40));
      // Nothing to rank against: recency.
      assertThat(numbers(store.forAgent(agent))).containsExactly(3, 4);
      assertThat(numbers(store.forAgent(agent, asking(" ")))).containsExactly(3, 4);
    }

    @Test
    void everything_is_shown_when_it_fits_without_asking_the_embedder() {
      KeywordEmbedder embedder = new KeywordEmbedder("kw", "cats");
      JdbcEpisodes store = store(embedder, 10);
      AgentId agent = story(store);
      int embeddedWhileWriting = embedder.embedded.size();

      assertThat(numbers(store.forAgent(agent, asking("cats")))).containsExactly(1, 2, 3, 4);
      assertThat(embedder.embedded).hasSize(embeddedWhileWriting);
    }

    @Test
    void a_summary_embedded_by_another_model_ranks_last() {
      KeywordEmbedder old = new KeywordEmbedder("old", "cats", "dogs", "birds", "fish");
      AgentId agent = story(store(old, 2));
      // The store now embeds with a new model; the old rows cannot be compared to its queries.
      KeywordEmbedder current = new KeywordEmbedder("new", "cats", "dogs", "birds", "fish");
      JdbcEpisodes store = store(current, 3);
      store.begin(agent, new TurnId(51), "cats again", "back to it");
      store.summarize(agent, 5, "more cats");

      // Five summarised: 5 is latest; of 1..4 (old model) and none comparable, the two picked
      // are the first two by stable order, which is what "ranks last, all equal" gives.
      List<Integer> shown = numbers(store.forAgent(agent, asking("cats")));
      assertThat(shown).hasSize(3).endsWith(5);
      assertThat(Optional.of(shown.getFirst())).isPresent();
    }
  }

  @Test
  void what_is_refused_at_configuration() {
    assertThatThrownBy(() -> JdbcEpisodes.create(c -> c.agentType(Calls.TYPE)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("dataSource");
    assertThatThrownBy(() -> JdbcEpisodes.create(c -> c.dataSource(DATABASE)))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("agentType");
    assertThatThrownBy(() -> JdbcEpisodes.create(c -> c.shown(0)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Episode(0, new TurnId(1), null, "t", "r", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Episode(1, new TurnId(5), new TurnId(4), "t", "r", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Episode(1, new TurnId(1), null, "t", "r", "summary"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
