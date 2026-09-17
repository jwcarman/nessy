package org.jwcarman.nessy.engine.trace;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.jwcarman.nessy.engine.observability.Identity;

/**
 * Carries a trace across the gap between emitting an effect and performing it.
 *
 * <p><b>Why anything is needed at all.</b> An effect is written down in one transaction and
 * performed by a later poll -- minutes later, on another thread, possibly in another process after
 * a restart. The thread that picks up the row has no ambient context to inherit, so the only way a
 * turn comes back as one trace is if the parent was written down beside the work.
 *
 * <p><b>What is stored is a carrier, not a span.</b> Two short strings in W3C's own format, which
 * is the one thing every tracer already knows how to read. Nothing here depends on how the
 * application's tracing is set up, or on it being set up at all: with no tracer configured this
 * captures nothing, stores nothing, and restores nothing.
 *
 * <p><b>{@code SenderContext} and {@code ReceiverContext} are the mechanism, not a claim that an
 * effect is a message.</b> They are how Micrometer is asked to inject and read the headers; an
 * earlier design dressed effects up as a message broker and tagged them with messaging semconv,
 * which said something untrue about what this is. An effect is work an agent owes, and its span is
 * named for the work.
 */
public final class Traces {

  /**
   * Header separator. A carrier is written as {@code name: value} lines because that is what these
   * values already are -- HTTP headers -- and a header value may not contain a newline, so nothing
   * can be smuggled across the boundary between two of them.
   */
  private static final String LINE = "\n";

  private static final String SEPARATOR = ": ";

  private final ObservationRegistry registry;
  private final TraceCarrier carrier;

  /** Captures by opening a momentary span, for an application that supplied no carrier. */
  public Traces(ObservationRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.carrier = this::emit;
  }

  /** Captures through {@code carrier}, which writes the current context without a span. */
  public Traces(ObservationRegistry registry, TraceCarrier carrier) {
    this.registry = Objects.requireNonNull(registry, "registry must not be null");
    this.carrier = Objects.requireNonNull(carrier, "carrier must not be null");
  }

  /** For an application that is not tracing, and for every test that does not care. */
  public static Traces noop() {
    return new Traces(ObservationRegistry.NOOP);
  }

  /**
   * The context in force right now, in a form that can be stored in a column.
   *
   * <p>Returns {@code null} when nothing is tracing, which is what a row's nullable column means:
   * an effect emitted before tracing was switched on, or by an application that never will, simply
   * has no parent and starts a trace of its own when it runs.
   */
  public String capture() {
    if (registry.isNoop()) {
      return null;
    }
    Map<String, String> headers = carrier.capture();
    return headers.isEmpty() ? null : encode(headers);
  }

  /**
   * The fallback: a momentary span whose only purpose is to make the tracer write its own identity
   * into the carrier. An observation has no other way to have headers written for it, so without a
   * {@link TraceCarrier} this span is the price of the parent.
   */
  private Map<String, String> emit() {
    Map<String, String> headers = new LinkedHashMap<>();
    SenderContext<Map<String, String>> sending = new SenderContext<>(Map::put, Kind.PRODUCER);
    sending.setCarrier(headers);
    Observation.createNotStarted("nessy.effect.emit", () -> sending, registry).observe(() -> {});
    return headers;
  }

  /**
   * Names the span in force after it has started, for work that only learns what it is once it is
   * underway -- an effect row has to be decoded, inside its span, before anyone knows which kind it
   * is. A span's name is read when it ends, so this lands. A contextual name only, never a tag:
   * tags become metric dimensions, and those are fixed when the observation starts.
   */
  public void nameCurrent(String contextualName) {
    Observation current = registry.getCurrentObservation();
    if (current != null) {
      current.contextualName(contextualName);
    }
  }

  /**
   * Runs {@code work} inside a span parented to whatever {@code carrier} was captured from.
   *
   * <p>A carrier that is {@code null}, empty or unreadable yields a span with no parent rather than
   * a failure. Losing a trace must never lose an agent's work: this is the one subsystem in the
   * engine whose complete absence changes nothing an application can observe except its dashboards.
   */
  public <T> T restore(String name, String carrier, Map<String, String> tags, Supplier<T> work) {
    return restore(name, carrier, tags, null, work);
  }

  /** The same, said to be this agent's. */
  public <T> T restore(String name, String carrier, Identity whose, Supplier<T> work) {
    return restore(name, carrier, Map.of(), whose, work);
  }

  private <T> T restore(
      String name, String carrier, Map<String, String> tags, Identity whose, Supplier<T> work) {
    if (registry.isNoop()) {
      return work.get();
    }
    ReceiverContext<Map<String, String>> received = new ReceiverContext<>(Map::get, Kind.CONSUMER);
    received.setCarrier(decode(carrier));
    Observation observation = Observation.createNotStarted(name, () -> received, registry);
    tags.forEach(observation::lowCardinalityKeyValue);
    if (whose != null) {
      whose.on(observation, null);
    }
    return observation.observe(work);
  }

  /** Opens a span that later effects will be parented to, and times the work inside it. */
  public <T> T in(String name, Map<String, String> tags, Supplier<T> work) {
    return in(name, tags, null, work);
  }

  /** The same, said to be this agent's. */
  public <T> T in(String name, Identity whose, Supplier<T> work) {
    return in(name, Map.of(), whose, work);
  }

  private <T> T in(String name, Map<String, String> tags, Identity whose, Supplier<T> work) {
    if (registry.isNoop()) {
      return work.get();
    }
    Observation observation = Observation.createNotStarted(name, registry);
    tags.forEach(observation::lowCardinalityKeyValue);
    if (whose != null) {
      whose.on(observation, null);
    }
    return observation.observe(work);
  }

  private static String encode(Map<String, String> headers) {
    StringBuilder text = new StringBuilder();
    headers.forEach(
        (name, value) -> {
          if (!text.isEmpty()) {
            text.append(LINE);
          }
          text.append(name).append(SEPARATOR).append(value);
        });
    return text.toString();
  }

  private static Map<String, String> decode(String carrier) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (carrier == null || carrier.isBlank()) {
      return headers;
    }
    for (String line : carrier.split(LINE)) {
      int at = line.indexOf(SEPARATOR);
      // A line without a separator is not half a header, it is not a header -- skipped rather
      // than guessed at, because a malformed carrier must cost a parent and never a turn.
      if (at > 0) {
        headers.put(line.substring(0, at), line.substring(at + SEPARATOR.length()));
      }
    }
    return headers;
  }
}
