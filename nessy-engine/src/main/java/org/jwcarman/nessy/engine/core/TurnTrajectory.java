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
package org.jwcarman.nessy.engine.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jwcarman.nessy.api.CallFailure;
import org.jwcarman.nessy.api.Trajectory;
import org.jwcarman.nessy.api.TurnOutcome;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.backend.event.AgentEvent;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The shape of a turn's behaviour, worked out from what it did: which tools ran in which rounds,
 * what each came to, and how the turn ended, with every execution-specific value left out.
 *
 * <p>The fold keeps a {@link State} in the busy states beside the tally and moves it on as calls
 * settle; a harness hashes it once, at the event that ends the turn, into a {@link Trajectory}.
 * Nothing here is incremental: the state holds every completed round, and a turn has few enough for
 * that to be nothing.
 *
 * <p><b>Version 1 canonical bytes</b>, hashed with SHA-256. Big-endian, unsigned, strings UTF-8
 * with a 32-bit length prefix, so that no two distinct trajectories serialise the same:
 *
 * <pre>
 * "NESSY_TRAJECTORY"  u16 version  u32 rounds
 *   per round: u32 entries, per entry (sorted by name bytes then outcome): u32 len, name, u8 outcome
 * 0xFF  u8 terminal outcome
 * </pre>
 *
 * The bytes up to the marker are a complete encoding of the path alone, so a rounds-only
 * fingerprint, if ever wanted, is a hash of that prefix under the same version.
 *
 * <p>Not public API. {@link Trajectory} is what anyone outside reads; this is how it is made.
 */
public final class TurnTrajectory {

  public static final short VERSION = 1;

  private static final byte[] DOMAIN = "NESSY_TRAJECTORY".getBytes(StandardCharsets.US_ASCII);
  private static final byte TERMINAL_MARKER = (byte) 0xFF;

  private TurnTrajectory() {}

  /**
   * What a settled call came to, as far as the model can tell. Five ways to settle collapse to
   * three because the rule is "keep a distinction only if it changes what the model can reasonably
   * do next": a call that timed out and one that threw both read as "try again or not"; a call a
   * person refused and one whose approval never came both read as "you may not".
   */
  public enum CallOutcome {
    SUCCESS((byte) 1),
    FAILED((byte) 2),
    DENIED((byte) 3);

    private final byte tag;

    CallOutcome(byte tag) {
      this.tag = tag;
    }

    public byte tag() {
      return tag;
    }
  }

  /** One settled call: the tool and what it came to. Compared by name bytes, then outcome. */
  public record Entry(ToolName tool, CallOutcome outcome) implements Comparable<Entry> {

    public Entry {
      Objects.requireNonNull(tool, "tool must not be null");
      Objects.requireNonNull(outcome, "outcome must not be null");
    }

    @Override
    public int compareTo(Entry other) {
      int byName = Arrays.compareUnsigned(nameBytes(tool), nameBytes(other.tool));
      return byName != 0 ? byName : Byte.compare(outcome.tag, other.outcome.tag);
    }
  }

  /** One completed round: every call the model asked for at once, sorted, duplicates kept. */
  public record Round(List<Entry> entries) {
    public Round {
      entries = List.copyOf(entries);
    }
  }

  /**
   * Where a turn's trajectory stands: the rounds done, the round in progress, and how many model
   * attempts failed and were retried (counted here because the tally's failures also count the
   * failure that ends a turn, and a retry is not that).
   */
  public record State(Instant arrivedAt, List<Round> completed, List<Entry> current, int retries) {

    public State {
      Objects.requireNonNull(arrivedAt, "arrivedAt must not be null");
      completed = List.copyOf(completed);
      current = List.copyOf(current);
    }

    public static State opened(Instant arrivedAt) {
      return new State(arrivedAt, List.of(), List.of(), 0);
    }

    public State retried() {
      return new State(arrivedAt, completed, current, retries + 1);
    }

    public State settled(ToolName tool, CallOutcome outcome) {
      List<Entry> next = new ArrayList<>(current);
      next.add(new Entry(tool, outcome));
      return new State(arrivedAt, completed, next, retries);
    }

    /** The last call of the round has settled: sort what it held and keep it. */
    public State roundClosed() {
      if (current.isEmpty()) {
        throw new IllegalStateException("no round is open");
      }
      List<Entry> sorted = new ArrayList<>(current);
      sorted.sort(null);
      List<Round> next = new ArrayList<>(completed);
      next.add(new Round(sorted));
      return new State(arrivedAt, next, List.of(), retries);
    }

    public int toolCalls() {
      return completed.stream().mapToInt(round -> round.entries().size()).sum() + current.size();
    }

    public int count(CallOutcome outcome) {
      return (int)
          (completed.stream()
                  .flatMap(round -> round.entries().stream())
                  .filter(entry -> entry.outcome() == outcome)
                  .count()
              + current.stream().filter(entry -> entry.outcome() == outcome).count());
    }
  }

  /** What a settling event says the call came to. */
  public static CallOutcome outcomeOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.ToolSucceeded _ -> CallOutcome.SUCCESS;
      case AgentEvent.ToolDenied _ -> CallOutcome.DENIED;
      case AgentEvent.ToolFailed failed ->
          failed.kind() == CallFailure.NOT_AUTHORISED ? CallOutcome.DENIED : CallOutcome.FAILED;
      default ->
          throw new IllegalArgumentException(event.getClass().getSimpleName() + " settles nothing");
    };
  }

  /** How this event ends a turn, or empty because it does not. */
  public static Optional<TurnOutcome> endingOf(AgentEvent event) {
    return switch (event) {
      case AgentEvent.InferenceAnswered answered ->
          Optional.of(answered.truncated() ? TurnOutcome.TRUNCATED : TurnOutcome.ANSWERED);
      case AgentEvent.InferenceRefused _ -> Optional.of(TurnOutcome.REFUSED);
      case AgentEvent.InferenceFailed _ -> Optional.of(TurnOutcome.FAILED);
      case AgentEvent.TurnStopped _ -> Optional.of(TurnOutcome.STOPPED);
      case AgentEvent.TurnStarted _,
          AgentEvent.InferenceAttempted _,
          AgentEvent.ActionsRequested _,
          AgentEvent.ToolApproved _,
          AgentEvent.ToolDenied _,
          AgentEvent.ToolSucceeded _,
          AgentEvent.ToolFailed _,
          AgentEvent.ApprovalDeferred _,
          AgentEvent.ToolDeferred _,
          AgentEvent.Terminated _ ->
          Optional.empty();
    };
  }

  public static Trajectory fingerprint(State state, TurnOutcome outcome) {
    return new Trajectory(VERSION, HexFormat.of().formatHex(sha256(canonical(state, outcome))));
  }

  /**
   * The trajectory as JSON, for the row: the same rounds in the same order, each round's entries in
   * the order {@link #canonical} encodes them, and the turn's outcome. Compact, keys in a fixed
   * order. Not the bytes hashed -- a database may reorder keys -- but one-to-one with them: under
   * one version, two renderings are equal exactly when the two fingerprints are.
   *
   * <p>A name Postgres {@code jsonb} cannot hold (an unpaired surrogate, a NUL) would make the row
   * refuse to insert and roll back the turn's ending on every retry. Such a name is written with
   * each offending character as the six characters {@code \}{@code uXXXX}, and its entry is flagged
   * {@code "escaped": true} so it never equals a real name that happens to spell the same text. In
   * such a name each backslash is also written as the six characters {@code \}{@code u005C}, so
   * that every backslash in an escaped name begins an escape and two different escaped names never
   * render alike.
   */
  public static String json(State state, TurnOutcome outcome) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    ObjectNode root = nodes.objectNode();
    ArrayNode rounds = root.putArray("rounds");
    for (Round round : state.completed()) {
      ArrayNode entries = rounds.addArray();
      for (Entry entry : round.entries()) {
        ObjectNode written = entries.addObject();
        String name = entry.tool().value();
        String safe = jsonSafe(name);
        written.put("tool", safe);
        written.put("outcome", entry.outcome().name());
        if (!safe.equals(name)) {
          written.put("escaped", true);
        }
      }
    }
    root.put("outcome", outcome.name());
    return root.toString();
  }

  /**
   * The name with each unpaired surrogate and each NUL written as {@code \}{@code uXXXX}. In a name
   * that needs escaping, each backslash is also written as the six characters {@code \}{@code
   * u005C}.
   */
  private static String jsonSafe(String name) {
    // First pass: detect if escaping is needed (unpaired surrogates or NUL)
    boolean needsEscaping = false;
    int i = 0;
    while (i < name.length()) {
      char c = name.charAt(i);
      if (c == '\u0000' || Character.isSurrogate(c)) {
        boolean pairedHigh =
            Character.isHighSurrogate(c)
                && i + 1 < name.length()
                && Character.isLowSurrogate(name.charAt(i + 1));
        if (!pairedHigh) {
          needsEscaping = true;
          break;
        }
        i += 2;
        continue;
      }
      i++;
    }

    // If no escaping needed, return as-is
    if (!needsEscaping) {
      return name;
    }

    // Second pass: build escaped output
    StringBuilder out = new StringBuilder(name.length() * 2);
    i = 0;
    while (i < name.length()) {
      char c = name.charAt(i);
      boolean pairedHigh =
          Character.isHighSurrogate(c)
              && i + 1 < name.length()
              && Character.isLowSurrogate(name.charAt(i + 1));
      if (pairedHigh) {
        out.append(c).append(name.charAt(i + 1));
        i += 2;
      } else if (c == '\\') {
        out.append("\\u005C");
        i++;
      } else if (c == '\u0000' || Character.isSurrogate(c)) {
        out.append(String.format("\\u%04X", (int) c));
        i++;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /** The exact bytes hashed. Exposed so a test can pin the framing. */
  public static byte[] canonical(State state, TurnOutcome outcome) {
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(DOMAIN);
    u16(out, VERSION);
    u32(out, state.completed().size());
    for (Round round : state.completed()) {
      u32(out, round.entries().size());
      for (Entry entry : round.entries()) {
        byte[] name = nameBytes(entry.tool());
        u32(out, name.length);
        out.writeBytes(name);
        out.write(entry.outcome().tag());
      }
    }
    out.write(TERMINAL_MARKER);
    out.write(tagOf(outcome));
    return out.toByteArray();
  }

  /** The byte that stands for a turn's ending in the canonical encoding. Never reassigned. */
  private static byte tagOf(TurnOutcome outcome) {
    return switch (outcome) {
      case ANSWERED -> 1;
      case TRUNCATED -> 2;
      case REFUSED -> 3;
      case FAILED -> 4;
      case STOPPED -> 5;
    };
  }

  /**
   * A tool name's bytes, for both the sort and the hash. A well-formed name is its strict UTF-8. A
   * name with an unpaired surrogate has no UTF-8 form, so it is 0xFF (never seen in UTF-8, so no
   * escaped name equals a well-formed one) followed by its UTF-16BE code units. Never throws.
   */
  private static byte[] nameBytes(ToolName tool) {
    String value = tool.value();
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] bytes = new byte[encoded.remaining()];
      encoded.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      byte[] units = new byte[1 + 2 * value.length()];
      units[0] = TERMINAL_MARKER;
      for (int i = 0; i < value.length(); i++) {
        units[1 + 2 * i] = (byte) (value.charAt(i) >>> 8);
        units[2 + 2 * i] = (byte) value.charAt(i);
      }
      return units;
    }
  }

  private static void u16(ByteArrayOutputStream out, int value) {
    if (value < 0 || value > 0xFFFF) {
      throw new IllegalArgumentException("does not fit in 16 bits: " + value);
    }
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static void u32(ByteArrayOutputStream out, int value) {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static byte[] sha256(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM ships SHA-256", e);
    }
  }
}
