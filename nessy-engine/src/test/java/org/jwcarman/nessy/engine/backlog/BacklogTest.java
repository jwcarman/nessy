package org.jwcarman.nessy.engine.backlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.BacklogItem;
import org.jwcarman.nessy.api.ObservationCoalescer;

class BacklogTest {

  private static final ObservationCoalescer<String> KEEP_ALL = ObservationCoalescer.keepAll();

  private static final Instant T0 = Instant.parse("2026-09-05T12:00:00Z");

  private static BacklogItem<String> item(String text) {
    return new BacklogItem<>(text, T0);
  }

  private static Backlog<String> openWith(String... texts) {
    Backlog<String> backlog = Backlog.empty();
    for (String text : texts) {
      backlog = backlog.accept(item(text), KEEP_ALL);
    }
    return backlog;
  }

  // --- open -------------------------------------------------------------------------------

  @Test
  void anEmptyOpenBacklogHasNothingToDo() {
    assertThat(Backlog.<String>empty().next()).isInstanceOf(Pull.Empty.class);
    assertThat(Backlog.empty().size()).isZero();
  }

  @Test
  void acceptingQueuesTheObservation() {
    Backlog<String> after = Backlog.<String>empty().accept(item("first"), KEEP_ALL);

    assertThat(after.size()).isEqualTo(1);
    assertThat(after.next()).isInstanceOf(Pull.Item.class);
  }

  @Test
  void takingHandsOutTheOldestAndTheBacklogThatWouldRemain() {
    Pull<String> pull = openWith("first", "second").next();

    assertThat(pull).isInstanceOf(Pull.Item.class);
    Pull.Item<String> taken = (Pull.Item<String>) pull;
    assertThat(taken.item().observation()).isEqualTo("first");
    assertThat(taken.remainder().size()).isEqualTo(1);
  }

  /**
   * The reason there is no separate consume. A pull the fold refuses is undone by never persisting
   * the remainder -- so the original must be untouched by having looked at it.
   */
  @Test
  void takingDoesNotChangeTheBacklogItWasTakenFrom() {
    Backlog<String> backlog = openWith("first", "second");

    backlog.next();

    assertThat(backlog.size())
        .as("refusing a pull must cost nothing, so taking cannot mutate")
        .isEqualTo(2);
  }

  // --- sealing ----------------------------------------------------------------------------

  @Test
  void sealingAbandonsWhatIsQueued() {
    Backlog<String> sealed = openWith("first", "second").seal();

    assertThat(sealed.size())
        .as(
            "terminating is the application saying it is done, not asking for a queue "
                + "to be worked through afterwards")
        .isZero();
    assertThat(sealed.next()).isInstanceOf(Pull.Pill.class);
  }

  @Test
  void thePillIsOfferedForever() {
    Backlog<String> sealed = Backlog.<String>empty().seal();

    assertThat(sealed.next()).isInstanceOf(Pull.Pill.class);
    assertThat(sealed.next())
        .as("terminality is enforced here, not relied upon from the fold")
        .isInstanceOf(Pull.Pill.class);
  }

  @Test
  void aSealedBacklogRefusesObservationsRatherThanSwallowingThem() {
    Backlog<String> sealed = openWith("first").seal();

    Backlog<String> after = sealed.accept(item("too late"), KEEP_ALL);

    assertThat(after)
        .as(
            "an observation queued here could never be taken, so it is not queued -- and "
                + "returning the same backlog is how the fold knows nothing happened")
        .isEqualTo(sealed);
  }

  @Test
  void sealingTwiceChangesNothing() {
    Backlog<String> sealed = openWith("first").seal();

    assertThat(sealed.seal())
        .as("idempotent, so a second termination is not a second fold")
        .isEqualTo(sealed);
  }

  /** The coalescer is never consulted once nothing more can be taken. */
  @Test
  void aSealedBacklogDoesNotConsultTheCoalescer() {
    Backlog<String> sealed = Backlog.<String>empty().seal();

    Backlog<String> after =
        sealed.accept(
            item("too late"),
            (backlog, incoming) -> {
              throw new AssertionError("the coalescer must not be asked");
            });

    assertThat(after).isEqualTo(sealed);
  }
}
