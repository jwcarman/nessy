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
