package org.jwcarman.nessy.engine.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.ReceiverContext;
import io.micrometer.observation.transport.SenderContext;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;

/**
 * Carrying a trace across the gap between emitting an effect and performing it.
 *
 * <p><b>No tracer here, deliberately.</b> Whether OpenTelemetry writes a correct {@code
 * traceparent} is OpenTelemetry's business and already its own tests. What is mine is the plumbing
 * either side of it: that a context in force at emit becomes something storable, that it survives
 * being written to a column and read back, and that its absence costs a parent rather than a turn.
 * A handler standing in for the tracer tests exactly that and nothing else.
 */
class TracesTest {

  private static final String HEADER = "traceparent";
  private static final String VALUE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /**
   * What a real tracing bridge does, reduced to the two moments this code depends on: write the
   * current identity into a carrier on the way out, read one back on the way in.
   */
  private static final class FakePropagation implements ObservationHandler<Observation.Context> {

    private final ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();

    @Override
    public void onStart(Observation.Context context) {
      if (context instanceof SenderContext<?> sending) {
        inject(sending);
      }
      if (context instanceof ReceiverContext<?> receiving) {
        String parent = parentOf(receiving);
        // A carrier with no parent reads back null, which is the ordinary case for a row written
        // before tracing was switched on. Recorded as absent rather than offered to a queue that
        // rejects nulls.
        received.add(parent == null ? "" : parent);
      }
    }

    /**
     * Generic so the wildcard is captured rather than cast away. {@code SenderContext<?>} cannot
     * have its own setter applied to its own carrier without naming the carrier type, and naming it
     * {@code Object} is a lie the compiler is right to warn about -- a method parameter lets javac
     * work out for itself that the two halves match.
     */
    private static <C> void inject(SenderContext<C> sending) {
      sending.getSetter().set(sending.getCarrier(), HEADER, VALUE);
    }

    private static <C> String parentOf(ReceiverContext<C> receiving) {
      return receiving.getGetter().get(receiving.getCarrier(), HEADER);
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }
  }

  private static ObservationRegistry registryWith(ObservationHandler<Observation.Context> handler) {
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(handler);
    return registry;
  }

  @Test
  void a_context_in_force_becomes_something_that_can_be_stored() {
    Traces traces = new Traces(registryWith(new FakePropagation()));

    String carrier = traces.capture();

    assertThat(carrier)
        .as("a column's worth of text, in the one format every tracer already reads")
        .isEqualTo(HEADER + ": " + VALUE);
  }

  @Test
  void and_is_handed_back_to_whoever_performs_the_work() {
    FakePropagation propagation = new FakePropagation();
    Traces traces = new Traces(registryWith(propagation));

    String carrier = traces.capture();
    String answer = traces.restore("nessy.effect", carrier, Map.of(), () -> "done");

    assertThat(answer).isEqualTo("done");
    assertThat(propagation.received)
        .as("the parent reaches the far side exactly as it left")
        .containsExactly(VALUE);
  }

  /**
   * A tracing library that can write the current context directly is used instead of a span: the
   * same headers reach the column, and nothing is opened to put them there.
   */
  @Test
  void a_supplied_carrier_writes_the_context_without_opening_a_span() {
    ConcurrentLinkedQueue<String> started = new ConcurrentLinkedQueue<>();
    ObservationRegistry registry =
        registryWith(
            new ObservationHandler<>() {
              @Override
              public void onStart(Observation.Context context) {
                started.add(context.getName());
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    Traces traces = new Traces(registry, () -> Map.of(HEADER, VALUE));

    String carrier = traces.capture();

    assertThat(carrier).isEqualTo(HEADER + ": " + VALUE);
    assertThat(started).isEmpty();
  }

  /** A span learns its name once its work knows what it is, and keeps it when it ends. */
  @Test
  void the_span_in_force_can_be_named_after_it_has_started() {
    ConcurrentLinkedQueue<String> named = new ConcurrentLinkedQueue<>();
    ObservationRegistry registry =
        registryWith(
            new ObservationHandler<>() {
              @Override
              public void onStop(Observation.Context context) {
                named.add(context.getContextualName());
              }

              @Override
              public boolean supportsContext(Observation.Context context) {
                return true;
              }
            });
    Traces traces = new Traces(registry);

    traces.restore(
        "nessy.effect",
        null,
        Map.of(),
        () -> {
          traces.nameCurrent("nessy.effect infer");
          return null;
        });

    assertThat(named).containsExactly("nessy.effect infer");
  }

  // ---- what absence costs ------------------------------------------------------------------

  /**
   * Switching tracing off is passing {@link ObservationRegistry#NOOP}, and it has to cost nothing:
   * no carrier captured, so no column written, so nothing stored for rows nobody will ever trace.
   */
  @Test
  void an_application_that_is_not_tracing_captures_nothing() {
    assertThat(Traces.noop().capture()).isNull();
  }

  @Test
  void and_still_performs_its_work() {
    assertThat(Traces.noop().restore("nessy.effect", null, Map.of(), () -> "done"))
        .isEqualTo("done");
    assertThat(Traces.noop().in("nessy.turn", Map.of(), () -> "done")).isEqualTo("done");
  }

  /**
   * A row written before the column existed, or by a process that was not tracing. It starts a
   * trace of its own rather than failing: losing a trace must never lose an agent's work.
   */
  @Test
  void a_row_with_no_parent_is_performed_anyway() {
    Traces traces = new Traces(registryWith(new FakePropagation()));

    assertThat(traces.restore("nessy.effect", null, Map.of(), () -> "done")).isEqualTo("done");
  }

  /**
   * The carrier is text in a column, and a column can hold anything a bad deploy put there. Every
   * shape of nonsense costs a parent and nothing else.
   */
  @Test
  void and_so_is_one_whose_parent_cannot_be_read() {
    Traces traces = new Traces(registryWith(new FakePropagation()));

    assertThat(traces.restore("nessy.effect", "", Map.of(), () -> "done")).isEqualTo("done");
    assertThat(traces.restore("nessy.effect", "not a header at all", Map.of(), () -> "done"))
        .isEqualTo("done");
    assertThat(traces.restore("nessy.effect", ": no name", Map.of(), () -> "done"))
        .isEqualTo("done");
  }

  private static <C> void injectBoth(SenderContext<C> sending) {
    sending.getSetter().set(sending.getCarrier(), HEADER, VALUE);
    sending.getSetter().set(sending.getCarrier(), "tracestate", "vendor=opaque");
  }

  /** Two headers survive as two, because a tracer may send tracestate beside traceparent. */
  @Test
  void a_carrier_of_several_headers_round_trips_whole() {
    Traces traces =
        new Traces(
            registryWith(
                new ObservationHandler<>() {
                  @Override
                  public void onStart(Observation.Context context) {
                    if (context instanceof SenderContext<?> sending) {
                      injectBoth(sending);
                    }
                  }

                  @Override
                  public boolean supportsContext(Observation.Context context) {
                    return true;
                  }
                }));

    assertThat(traces.capture())
        .isEqualTo(HEADER + ": " + VALUE + "\n" + "tracestate: vendor=opaque");
  }
}
