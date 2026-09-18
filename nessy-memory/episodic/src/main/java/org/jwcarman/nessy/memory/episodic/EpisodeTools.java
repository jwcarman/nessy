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
package org.jwcarman.nessy.memory.episodic;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AmbientSource;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The two verbs a model uses on its episodes, and the stage that shows it which there are.
 *
 * <p>{@code begin_episode} records a boundary and nothing else: the model says a distinct piece of
 * work has begun, the store closes the last episode at the turn before, and the summary is written
 * later, in the background, by the {@link EpisodeSummarizer}. Nothing waits on a model call inside
 * a tool call. {@code recall_episode} reads a summary the index names in full, for the episodes the
 * store did not choose to show.
 *
 * <p><b>The tools are useless without the stage.</b> A model that cannot see its episodes does not
 * know one exists, so it never recalls one, and a model not told it is in an episode never begins
 * the next.
 */
public final class EpisodeTools {

  private static final String KIND = "episodes";
  private static final String EPISODES_NOT_NULL = "episodes must not be null";

  private EpisodeTools() {}

  /** Opens an episode at the turn the tool is called in. */
  public record BeginEpisode(
      @JsonPropertyDescription("A short name for what this episode is about; shown in your index")
          String title,
      @JsonPropertyDescription("Why this is a new episode rather than more of the last one")
          String reason) {}

  /** Reads one episode's summary back. */
  public record RecallEpisode(
      @JsonPropertyDescription("The episode's number, exactly as it appears in your index")
          int number) {}

  /**
   * The read half: the episodes there are, in front of the model on every call, and what to do
   * about the message in hand.
   *
   * <p>Ambient rather than a message, for the reason the notebook's index is: asked afresh each
   * time, it says what is true now. It always says something, unlike the notebook's, because the
   * decision it prompts is made on every call: with no episode open, whether this message begins
   * one; with one open, whether this message is still about it. The system prompt can say "begin an
   * episode when the subject changes"; only this can say what the subject currently is.
   */
  public static AmbientSource index(JdbcEpisodes episodes) {
    Objects.requireNonNull(episodes, EPISODES_NOT_NULL);
    return AmbientSource.of(
        source -> source.kind(KIND).text(agentId -> Optional.of(render(episodes.all(agentId)))));
  }

  static final String NONE_OPEN =
      "No episode is open. If this message starts a new topic, task or request, begin one with"
          + " begin_episode before answering, however small it looks: episodes are how this"
          + " conversation is remembered once it is long, and turns outside one are forgotten.";

  /** What the model sees: numbers, titles and spans, which is under way, and the question. */
  private static String render(List<Episode> all) {
    if (all.isEmpty()) {
      return NONE_OPEN;
    }
    StringBuilder text = new StringBuilder("Episodes of this conversation so far:\n");
    Episode current = null;
    for (Episode episode : all) {
      text.append("- ").append(episode.number()).append(". ").append(episode.title());
      if (!episode.title().equals(episode.openedAs())) {
        text.append(" (begun as \"").append(episode.openedAs()).append("\")");
      }
      if (episode.open()) {
        current = episode;
        text.append(" (current, since turn ").append(episode.from().value()).append(')');
      } else {
        text.append(" (turns ")
            .append(episode.from().value())
            .append("..")
            .append(episode.through().value())
            .append(episode.summarized() ? ")" : ", not yet summarised)");
      }
      text.append('\n');
    }
    text.append(
        "Summaries of the episodes most relevant to this message are shown above the recent turns;"
            + " read any other in full with recall_episode.\n");
    if (current == null) {
      text.append(NONE_OPEN);
    } else {
      text.append("The current episode is '")
          .append(current.title())
          .append("'. If this message is not about that, call begin_episode first.");
    }
    return text.toString();
  }

  /** Records that a new episode began at this turn; the summary of the last comes later. */
  public static Tool<BeginEpisode> begin(JdbcEpisodes episodes) {
    Objects.requireNonNull(episodes, EPISODES_NOT_NULL);
    return new EpisodeTool<>(
        BeginEpisode.class,
        new ToolName("begin_episode"),
        "Mark that a distinct piece of work has begun: a new topic, task or request that is not a"
            + " continuation of the current episode. Call it before answering the message that"
            + " starts the new work, and not otherwise: a follow-up, a clarification or a return"
            + " to the current subject is the same episode. The previous episode is closed and"
            + " summarised in the background; you need not summarise it yourself.",
        (agentId, turn, input) -> {
          Episode begun = episodes.begin(agentId, turn, input.title(), input.reason());
          return said("Began episode " + begun.number() + ", '" + begun.title() + "'.");
        });
  }

  /** Reads an episode's summary, for the ones the store did not show. */
  public static Tool<RecallEpisode> recall(JdbcEpisodes episodes) {
    Objects.requireNonNull(episodes, EPISODES_NOT_NULL);
    return new EpisodeTool<>(
        RecallEpisode.class,
        new ToolName("recall_episode"),
        "Read the summary of one earlier episode in full, by the number shown in your episode"
            + " index.",
        (agentId, _, input) ->
            episodes
                .find(agentId, input.number())
                .map(EpisodeTools::recalled)
                .orElseGet(
                    () ->
                        failure(
                            "no episode numbered "
                                + input.number()
                                + " -- check the episode index in your context")));
  }

  private static final String EPISODE = "Episode ";

  private static ToolResult recalled(Episode episode) {
    if (episode.open()) {
      return said(
          EPISODE
              + episode.number()
              + ", '"
              + episode.title()
              + "', is the current one: its turns are in front of you.");
    }
    if (!episode.summarized()) {
      return said(
          EPISODE
              + episode.number()
              + ", '"
              + episode.title()
              + "', has not been summarised yet; its turns are still in front of you.");
    }
    return said(EPISODE + episode.number() + ", " + episode.title() + ": " + episode.summary());
  }

  private static ToolResult failure(String message) {
    return new ToolResult.Failure(message);
  }

  private static ToolResult said(String text) {
    return ToolResult.ok(new Block.Text(text));
  }

  /** What a verb does once it knows whose episodes, and in which turn. */
  @FunctionalInterface
  private interface Verb<I> {
    ToolResult apply(AgentId agentId, TurnId turn, I input);
  }

  /**
   * The shape both share. Bad arguments come back as a failure the model can read, never as a
   * throw: a throw is the engine's problem and is retried with the same bad arguments, a failure is
   * the model's, and the model is the one who can fix it.
   */
  private record EpisodeTool<I>(Class<I> inputType, ToolName name, String description, Verb<I> verb)
      implements Tool<I> {

    @Override
    public Awaited<ToolResult> call(ToolCallRequest<I> request) {
      try {
        return Awaited.ready(verb.apply(request.agentId(), request.turn(), request.input()));
      } catch (IllegalArgumentException | NullPointerException invalid) {
        return Awaited.ready(failure(invalid.getMessage()));
      }
    }
  }
}
