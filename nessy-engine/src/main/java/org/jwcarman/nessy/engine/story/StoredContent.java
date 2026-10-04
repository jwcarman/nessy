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
package org.jwcarman.nessy.engine.story;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.CallResult;
import org.jwcarman.nessy.api.PayloadRef;
import org.jwcarman.nessy.api.RequestContent;
import org.jwcarman.nessy.api.Seq;
import org.jwcarman.nessy.api.StoryContent;
import org.jwcarman.nessy.api.TurnContent;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.backend.event.ActionRequest;
import org.jwcarman.nessy.backend.event.AgentEvent;
import org.jwcarman.nessy.backend.event.AgentEvents;
import org.jwcarman.nessy.backend.payload.Payloads;

/**
 * The content one agent's story refers to, read from its stored events and payloads.
 *
 * <p>Every read of events is made with a limit, a page at a time, and stops as soon as it has what
 * it was asked for. A call's key and id come from the request that made it, and its result from the
 * success with that id in the same request: a model may repeat a call id in a later request of one
 * turn, so an id is only meaningful inside the request that carries it.
 */
final class StoredContent implements StoryContent {

  /** The most events read at once, and the most results one read returns. */
  static final int PAGE = 1_000;

  private final AgentEvents events;
  private final Payloads payloads;
  private final AgentType type;
  private final AgentId id;

  StoredContent(AgentEvents events, Payloads payloads, AgentType type, AgentId id) {
    this.events = events;
    this.payloads = payloads.forAgent(id);
    this.type = type;
    this.id = id;
  }

  @Override
  public TurnContent turn(TurnId turn) {
    Objects.requireNonNull(turn, "turn must not be null");
    long value = turn.value();
    if (value < 1) {
      throw noTurn(turn);
    }
    Seq before = new Seq(value - 1);
    List<AgentEvents.Written> head = events.readWrittenFrom(type, id, before, 1);
    if (head.isEmpty()
        || !(head.getFirst().event() instanceof AgentEvent.TurnStarted started)
        || started.seq().value() != value) {
      throw noTurn(turn);
    }
    List<AgentEvent.ActionsRequested> requests = new ArrayList<>();
    List<AgentEvent.InferenceAnswered> answers = new ArrayList<>();
    scan(
        before,
        event ->
            switch (event) {
              case AgentEvent.TurnStarted other -> other.seq().value() == value;
              case AgentEvent.Terminated _ -> false;
              case AgentEvent.ActionsRequested requested -> requests.add(requested);
              case AgentEvent.InferenceAnswered answered -> answers.add(answered);
              default -> true;
            });
    List<RequestContent> written = new ArrayList<>();
    for (AgentEvent.ActionsRequested requested : requests) {
      written.add(
          new RequestContent(
              requested.seq(),
              narrow(blocks(requested.request()), Block.ActionRequestContent.class)));
    }
    Optional<List<Block.AnswerContent>> answer =
        answers.isEmpty()
            ? Optional.empty()
            : Optional.of(narrow(blocks(answers.getFirst().answer()), Block.AnswerContent.class));
    return new TurnContent(
        narrow(blocks(started.input()), Block.InputContent.class), written, answer);
  }

  @Override
  public Optional<List<Block.ToolResultContent>> result(IdempotencyKey key) {
    Objects.requireNonNull(key, "key must not be null");
    CallId[] wanted = new CallId[1];
    PayloadRef[] found = new PayloadRef[1];
    scan(
        Seq.NONE,
        event ->
            switch (event) {
              case AgentEvent.ActionsRequested requested -> {
                if (wanted[0] != null) {
                  yield false;
                }
                wanted[0] = callOf(requested, key);
                yield true;
              }
              case AgentEvent.ToolSucceeded done
                  when wanted[0] != null && wanted[0].equals(done.callId()) -> {
                found[0] = done.result();
                yield false;
              }
              case AgentEvent.TurnStarted _, AgentEvent.Terminated _ -> wanted[0] == null;
              default -> true;
            });
    if (found[0] == null) {
      return Optional.empty();
    }
    return Optional.of(narrow(blocks(found[0]), Block.ToolResultContent.class));
  }

  @Override
  public List<CallResult> results(Seq after, int limit) {
    Objects.requireNonNull(after, "after must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    int capped = Math.min(limit, PAGE);
    Optional<Seq> start = startOfTurnHolding(after);
    if (start.isEmpty()) {
      return List.of();
    }
    List<Pending> pending = new ArrayList<>();
    Map<CallId, IdempotencyKey> keys = new HashMap<>();
    scan(
        start.get(),
        event ->
            switch (event) {
              case AgentEvent.TurnStarted _ -> {
                keys.clear();
                yield true;
              }
              case AgentEvent.ActionsRequested requested -> {
                keys.clear();
                for (ActionRequest action : requested.actions()) {
                  if (action instanceof ActionRequest.ToolCall call) {
                    keys.put(call.id(), call.idempotencyKey());
                  }
                }
                yield true;
              }
              case AgentEvent.ToolSucceeded done -> {
                IdempotencyKey key = keys.get(done.callId());
                if (done.seq().compareTo(after) > 0 && key != null) {
                  pending.add(new Pending(done.seq(), key, done.result()));
                }
                yield pending.size() < capped;
              }
              default -> true;
            });
    Map<PayloadRef, Payloads.Resolved> resolved =
        payloads.get(pending.stream().map(Pending::result).distinct().toList());
    List<CallResult> results = new ArrayList<>();
    for (Pending one : pending) {
      results.add(
          new CallResult(
              one.seq(),
              one.key(),
              narrow(
                  found(one.result(), resolved.get(one.result())), Block.ToolResultContent.class)));
    }
    return List.copyOf(results);
  }

  @Override
  public Stream<CallResult> allResults(Seq after) {
    Objects.requireNonNull(after, "after must not be null");
    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize(new Paging(after), Spliterator.ORDERED), false);
  }

  /**
   * Walks the results a page at a time. A page is fetched when the one before it is used up and was
   * full, never earlier, so a consumer that stops early stops the reading; nothing is held open
   * between pages.
   */
  private final class Paging implements Iterator<CallResult> {

    private Seq after;
    private Iterator<CallResult> page = Collections.emptyIterator();
    private boolean more = true;

    Paging(Seq after) {
      this.after = after;
    }

    @Override
    public boolean hasNext() {
      if (!page.hasNext() && more) {
        List<CallResult> next = results(after, PAGE);
        more = next.size() == PAGE;
        if (!next.isEmpty()) {
          after = next.getLast().seq();
        }
        page = next.iterator();
      }
      return page.hasNext();
    }

    @Override
    public CallResult next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      return page.next();
    }
  }

  /**
   * The position just before the start of the turn holding the first event at or after {@code
   * after}, so that a read from there sees the request that gave every later result its key. Empty
   * when the story has nothing at or after that position.
   */
  private Optional<Seq> startOfTurnHolding(Seq after) {
    if (after.value() == 0) {
      return Optional.of(Seq.NONE);
    }
    List<AgentEvents.Written> next =
        events.readWrittenFrom(type, id, new Seq(after.value() - 1), 1);
    if (next.isEmpty()) {
      return Optional.empty();
    }
    AgentEvent event = next.getFirst().event();
    return Optional.of(
        switch (event) {
          case AgentEvent.TurnStarted e -> before(e.turn());
          case AgentEvent.InferenceAnswered e -> before(e.turn());
          case AgentEvent.InferenceRefused e -> before(e.turn());
          case AgentEvent.InferenceFailed e -> before(e.turn());
          case AgentEvent.InferenceAttempted e -> before(e.turn());
          case AgentEvent.TurnStopped e -> before(e.turn());
          case AgentEvent.ActionsRequested e -> before(e.turn());
          case AgentEvent.ToolApproved e -> before(e.turn());
          case AgentEvent.ToolDenied e -> before(e.turn());
          case AgentEvent.ToolSucceeded e -> before(e.turn());
          case AgentEvent.ToolFailed e -> before(e.turn());
          case AgentEvent.Terminated e -> new Seq(e.seq().value() - 1);
        });
  }

  private static Seq before(TurnId turn) {
    return new Seq(turn.value() - 1);
  }

  /** The id of the call in {@code requested} that has {@code key}, or null when none does. */
  private static CallId callOf(AgentEvent.ActionsRequested requested, IdempotencyKey key) {
    for (ActionRequest action : requested.actions()) {
      if (action instanceof ActionRequest.ToolCall call && call.idempotencyKey().equals(key)) {
        return call.id();
      }
    }
    return null;
  }

  /**
   * Reads the story after {@code after}, a page at a time, until {@code visitor} says to stop or
   * the story ends.
   */
  private void scan(Seq after, Predicate<AgentEvent> visitor) {
    Seq from = after;
    while (true) {
      List<AgentEvents.Written> page = events.readWrittenFrom(type, id, from, PAGE);
      for (AgentEvents.Written written : page) {
        if (!visitor.test(written.event())) {
          return;
        }
      }
      if (page.size() < PAGE) {
        return;
      }
      from = page.getLast().event().seq();
    }
  }

  private List<Block> blocks(PayloadRef ref) {
    return found(ref, payloads.get(ref));
  }

  private static List<Block> found(PayloadRef ref, Payloads.Resolved resolved) {
    return switch (resolved) {
      case Payloads.Resolved.Found(List<Block> content) -> content;
      // A reference with nothing behind it is a fault of the store, never a missing answer.
      case Payloads.Resolved.Missing _ ->
          throw new IllegalStateException("no payload behind " + ref);
    };
  }

  private static <T extends Block> List<T> narrow(List<Block> blocks, Class<T> position) {
    return blocks.stream().filter(position::isInstance).map(position::cast).toList();
  }

  private static IllegalArgumentException noTurn(TurnId turn) {
    return new IllegalArgumentException("no turn " + turn.value() + " in this agent's story");
  }

  private record Pending(Seq seq, IdempotencyKey key, PayloadRef result) {}
}
