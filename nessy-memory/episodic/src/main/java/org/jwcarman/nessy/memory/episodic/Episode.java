package org.jwcarman.nessy.memory.episodic;

import java.util.Objects;
import org.jwcarman.nessy.api.TurnId;

/**
 * One stretch of an agent's story, named by the model when it began.
 *
 * <p>Open until the next one begins: {@code through} is null while turns are still being added.
 * Closed and not yet summarised: {@code through} set, {@code summary} null, its turns still shown
 * verbatim. Summarised: the summary stands in for the turns.
 *
 * @param number the episode's position in the story, from one
 * @param from the first turn in it
 * @param through the last turn in it, or null while it is still open
 * @param title what it is called: what the model named it when it began, until the summary is
 *     written by something that has read the whole of it and titles it accordingly
 * @param openedAs what the model named it when it began, kept whatever it is called later
 * @param reason why the model began it
 * @param summary what was made of it once it closed, or null until then
 */
public record Episode(
    int number,
    TurnId from,
    TurnId through,
    String title,
    String openedAs,
    String reason,
    String summary) {

  public Episode {
    if (number < 1) {
      throw new IllegalArgumentException("episodes are numbered from one, not " + number);
    }
    Objects.requireNonNull(from, "from must not be null");
    Objects.requireNonNull(title, "title must not be null");
    Objects.requireNonNull(openedAs, "openedAs must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    if (through != null && through.value() < from.value()) {
      throw new IllegalArgumentException(
          "an episode must run forwards: from %s through %s".formatted(from, through));
    }
    if (summary != null && through == null) {
      throw new IllegalArgumentException("an open episode cannot have been summarised");
    }
  }

  public boolean open() {
    return through == null;
  }

  public boolean summarized() {
    return summary != null;
  }
}
