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
package org.jwcarman.nessy.engine.chapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ChapterPolicy;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessy.api.OpenTurns;
import org.jwcarman.nessy.api.Summarizer;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.turn.Chapter;
import org.jwcarman.nessy.api.turn.Summary;
import org.jwcarman.nessy.backend.chapter.Chapters;
import org.jwcarman.nessy.backend.lease.Attempt;
import org.jwcarman.nessy.backend.lease.LeaseKind;
import org.jwcarman.nessy.backend.lease.Leases;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cuts an agent's history into chapters and writes each chapter's summary, when a turn ends.
 *
 * <p><b>Cutting.</b> The turns completed since the last closed chapter are shown to the {@link
 * ChapterPolicy}, which names the turns that each end a chapter. The keeper stores those chapters.
 * An answer that is null, holds a null, names a turn that is not open or names turns out of order
 * is logged and treated as closing nothing; so is a policy that throws. When the policy closes
 * nothing, whether by answering so or by being treated so, and {@code maxChapterLength} turns are
 * open, the oldest {@code maxChapterLength} close anyway, and a chapter the policy made longer than
 * that is split into chapters of at most that many turns, so no chapter outgrows what a summariser
 * can be shown.
 *
 * <p><b>Summarising.</b> Chapters without a summary are summarised oldest first, one at a time. A
 * blank summary, a summariser that throws or a refused lease stops the pass, so a later chapter is
 * never summarised ahead of an earlier one; the next turn's end tries again.
 *
 * <p><b>Leases.</b> The cut holds the lease for the agent, and so does each summary, one at a time,
 * so a model call is never made outside one and the lease is never taken from inside itself (a
 * {@link Leases} must not be re-entered). A lease held by someone else is asked for again until the
 * keeper's wait is spent, because the holder may have read the history before the turn this pass
 * was told about; past that the keeper does nothing, and the next turn's end tries again.
 *
 * <p><b>Waiting for the turn it was told about.</b> A turn's end is announced before the store that
 * holds it has committed: both doors narrate inside the lock that writes the turn, which on a
 * database is the transaction. The keeper runs on a thread of its own and could read first and not
 * see the turn it was told ended, leaving it for the next turn's end. So the listener waits,
 * briefly and with a deadline, for that turn to be visible before it cuts, and cuts with what is
 * visible if the deadline passes.
 */
public final class ChapterKeeper {

  /** The lease kind under which the cut and each summary are done. */
  public static final LeaseKind LEASE_KIND = new LeaseKind("nessy.chapters");

  /** How long a lease is believed held when the caller has no better figure. */
  public static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(2);

  /** The longest the listener waits for the turn it was told about to become visible. */
  private static final Duration VISIBILITY_WAIT = Duration.ofSeconds(2);

  /** How often the listener looks again while it waits. */
  private static final Duration VISIBILITY_POLL = Duration.ofMillis(25);

  private static final Logger LOG = LoggerFactory.getLogger(ChapterKeeper.class);

  private final AgentType agentType;
  private final ChapterPolicy policy;
  private final Summarizer summarizer;
  private final Chapters chapters;
  private final Leases leases;
  private final TurnHistories histories;
  private final int maxChapterLength;
  private final Duration leaseTtl;
  private final Duration visibilityWait;
  private final Duration visibilityPoll;

  public ChapterKeeper(
      AgentType agentType,
      ChapterPolicy policy,
      Summarizer summarizer,
      Chapters chapters,
      Leases leases,
      TurnHistories histories,
      int maxChapterLength,
      Duration leaseTtl) {
    this(
        agentType,
        policy,
        summarizer,
        chapters,
        leases,
        histories,
        maxChapterLength,
        leaseTtl,
        VISIBILITY_WAIT,
        VISIBILITY_POLL);
  }

  /** As above, with the wait for a just-ended turn set, so a test need not wait two seconds. */
  ChapterKeeper(
      AgentType agentType,
      ChapterPolicy policy,
      Summarizer summarizer,
      Chapters chapters,
      Leases leases,
      TurnHistories histories,
      int maxChapterLength,
      Duration leaseTtl,
      Duration visibilityWait,
      Duration visibilityPoll) {
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.summarizer = Objects.requireNonNull(summarizer, "summarizer must not be null");
    this.chapters = Objects.requireNonNull(chapters, "chapters must not be null");
    this.leases = Objects.requireNonNull(leases, "leases must not be null");
    this.histories = Objects.requireNonNull(histories, "histories must not be null");
    Objects.requireNonNull(leaseTtl, "leaseTtl must not be null");
    if (maxChapterLength < 1) {
      throw new IllegalArgumentException("a chapter holds at least one turn: " + maxChapterLength);
    }
    if (leaseTtl.isZero() || leaseTtl.isNegative()) {
      throw new IllegalArgumentException("the lease time must be positive: " + leaseTtl);
    }
    this.maxChapterLength = maxChapterLength;
    this.leaseTtl = leaseTtl;
    this.visibilityWait = Objects.requireNonNull(visibilityWait, "visibilityWait must not be null");
    this.visibilityPoll = Objects.requireNonNull(visibilityPoll, "visibilityPoll must not be null");
  }

  /** The listener to attach to a harness: hears this type's turns end, on a thread of its own. */
  public NarrationListener listener() {
    return NarrationListener.of(
            c ->
                c.agentType(agentType)
                    .onTurnEnded((_, agentId, ended) -> keepAfter(agentId, ended.turn())))
        .async();
  }

  /**
   * Waits for the turn that ended to be visible in the agent's history, then keeps. A turn already
   * inside a closed chapter is visible by definition and is not waited for. If the deadline passes
   * the keeper carries on with what is visible.
   */
  void keepAfter(AgentId agentId, TurnId ended) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(ended, "ended must not be null");
    Optional<TurnId> closed = chapters.closedThrough(agentType, agentId);
    if (closed.isEmpty() || ended.value() > closed.get().value()) {
      awaitVisible(agentId, ended, closed);
    }
    keep(agentId);
  }

  private void awaitVisible(AgentId agentId, TurnId ended, Optional<TurnId> closed) {
    long deadline = System.nanoTime() + visibilityWait.toNanos();
    while (!histories.forAgent(agentType, agentId).completedAfter(closed).contains(ended)) {
      if (System.nanoTime() >= deadline) {
        LOG.debug(
            "[{}] turn {} of agent {} was not visible after {}; keeping what is",
            agentType.value(),
            ended,
            agentId,
            visibilityWait);
        return;
      }
      try {
        Thread.sleep(visibilityPoll);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /**
   * Cuts what is due, then writes what is unwritten. Public so a caller may ask outright.
   *
   * <p>A pass that finds the lease held by another does not give up at once: whoever holds it may
   * have read the turns before the one this pass was told about, so a chapter that turn made due
   * would be left open. It asks again every poll interval until the lease is free or the keeper's
   * wait (two seconds by default, the same as for a turn to become visible) is spent, which is long
   * for a cut and short for a summary. If the wait is spent the pass does nothing and says so at
   * DEBUG; the next turn's end keeps. The same wait applies before each summary. Nothing is
   * remembered between passes, so this holds when the holder is on another node.
   */
  public void keep(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Attempt<Boolean> cut =
        withLease(
            agentId,
            () -> {
              cut(agentId);
              return true;
            });
    if (cut instanceof Attempt.Ignored<Boolean>) {
      LOG.debug(
          "[{}] chapters of agent {} were still being kept elsewhere after {}; the next turn's end"
              + " will keep",
          agentType.value(),
          agentId,
          visibilityWait);
    }
    summarise(agentId);
  }

  /**
   * The work under the agent's lease, asking again while another holds it, up to the keeper's
   * patience. A holder that is mid-cut read the turns before the one this pass was told about, so
   * giving up at the first refusal would leave what that turn made due for the next turn's end.
   * Nothing is remembered between passes, for the holder may be on another node; the lease is the
   * only thing that is shared, so it is asked again. A refusal that outlasts the patience, or an
   * interrupt, is answered as a refusal.
   */
  private <T> Attempt<T> withLease(AgentId agentId, Supplier<T> work) {
    long deadline = System.nanoTime() + visibilityWait.toNanos();
    Attempt<T> attempt = leases.tryWithLease(LEASE_KIND, agentType, agentId, leaseTtl, work);
    while (attempt instanceof Attempt.Ignored<T> && System.nanoTime() < deadline) {
      try {
        Thread.sleep(visibilityPoll);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        return attempt;
      }
      attempt = leases.tryWithLease(LEASE_KIND, agentType, agentId, leaseTtl, work);
    }
    return attempt;
  }

  private void cut(AgentId agentId) {
    Optional<TurnId> after = chapters.closedThrough(agentType, agentId);
    List<TurnId> open = histories.forAgent(agentType, agentId).completedAfter(after);
    if (open.isEmpty()) {
      return;
    }
    List<TurnId> ends = asked(agentId, open);
    if (ends.isEmpty() && open.size() >= maxChapterLength) {
      ends = List.of(open.get(maxChapterLength - 1));
    }
    List<Chapter> cut = chaptersOf(agentId, open, ends);
    if (cut.isEmpty()) {
      return;
    }
    if (chapters.append(agentType, agentId, after, cut)) {
      LOG.info(
          "[{}] closed {} chapter(s) of agent {} through turn {}",
          agentType.value(),
          cut.size(),
          agentId,
          cut.getLast().through());
    } else {
      LOG.debug(
          "[{}] chapters of agent {} were closed by someone else first",
          agentType.value(),
          agentId);
    }
  }

  /** What the policy says, or nothing if it threw or answered with turns that are not open. */
  private List<TurnId> asked(AgentId agentId, List<TurnId> open) {
    List<TurnId> ends;
    try {
      ends = policy.ends(new OpenTurns(agentType, agentId, open));
    } catch (RuntimeException e) {
      LOG.warn(
          "[{}] the chapter policy threw for agent {}; treating it as closing nothing",
          agentType.value(),
          agentId,
          e);
      return List.of();
    }
    if (!valid(ends, open)) {
      LOG.warn(
          "[{}] the chapter policy answered with turns that are not open and in order for agent {}:"
              + " treating it as closing nothing; answered {}, open {}",
          agentType.value(),
          agentId,
          ends,
          open);
      return List.of();
    }
    return ends;
  }

  private static boolean valid(List<TurnId> ends, List<TurnId> open) {
    if (ends == null) {
      return false;
    }
    Set<TurnId> known = new HashSet<>(open);
    TurnId previous = null;
    for (TurnId end : ends) {
      if (end == null
          || !known.contains(end)
          || (previous != null && end.value() <= previous.value())) {
        return false;
      }
      previous = end;
    }
    return true;
  }

  /** Chapters over {@code open} ending at each of {@code ends}, split to the maximum length. */
  private List<Chapter> chaptersOf(AgentId agentId, List<TurnId> open, List<TurnId> ends) {
    List<Chapter> result = new ArrayList<>();
    int start = 0;
    for (TurnId end : ends) {
      int last = open.indexOf(end);
      for (int from = start; from <= last; from += maxChapterLength) {
        int through = Math.min(from + maxChapterLength - 1, last);
        result.add(new Chapter(agentType, agentId, open.get(from), open.get(through)));
      }
      start = last + 1;
    }
    return result;
  }

  private void summarise(AgentId agentId) {
    for (Chapter chapter : chapters.unsummarized(agentType, agentId)) {
      Attempt<Boolean> attempt = withLease(agentId, () -> summarise(agentId, chapter));
      boolean goOn = attempt.orElse(false);
      if (!goOn) {
        return;
      }
    }
  }

  /** Writes one chapter's summary; false when the pass should stop. */
  private boolean summarise(AgentId agentId, Chapter chapter) {
    if (!chapters.unsummarized(agentType, agentId).contains(chapter)) {
      return true;
    }
    String text;
    try {
      text = summarizer.summarize(chapter);
    } catch (RuntimeException e) {
      LOG.warn(
          "[{}] the summariser threw on turns {}..{} of agent {}; stopping",
          agentType.value(),
          chapter.from(),
          chapter.through(),
          agentId,
          e);
      return false;
    }
    if (text == null || text.isBlank()) {
      LOG.warn(
          "[{}] the summariser wrote nothing for turns {}..{} of agent {}; stopping",
          agentType.value(),
          chapter.from(),
          chapter.through(),
          agentId);
      return false;
    }
    if (chapters.summarize(new Summary(chapter, text))) {
      LOG.info(
          "[{}] wrote the summary of turns {}..{} of agent {}",
          agentType.value(),
          chapter.from(),
          chapter.through(),
          agentId);
    } else {
      LOG.debug(
          "[{}] turns {}..{} of agent {} were summarised by someone else first",
          agentType.value(),
          chapter.from(),
          chapter.through(),
          agentId);
    }
    return true;
  }
}
