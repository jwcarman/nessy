package org.jwcarman.nessy.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.Test;

class PropagatingTraceCarrierTest {

  /** Nothing in force means nothing written, so the row's column stays null. */
  @Test
  void with_no_trace_in_force_it_writes_nothing() {
    PropagatingTraceCarrier carrier = new PropagatingTraceCarrier(Tracer.NOOP, Propagator.NOOP);

    assertThat(carrier.capture()).isEmpty();
  }
}
