package org.jwcarman.nessy.engine.trace;

import java.util.Map;

/**
 * Writes the trace in force on this thread as headers, without opening a span to do it.
 *
 * <p>The engine speaks observations, not tracing, and an observation can only have headers written
 * for it by starting one -- which leaves a span behind for every effect whose only job was to be a
 * parent's name. A tracing library can write the current context directly; this is where one that
 * can is handed in. The Boot starter supplies one over Micrometer Tracing's {@code Propagator}.
 * With none supplied the engine falls back to the momentary span.
 */
@FunctionalInterface
public interface TraceCarrier {

  /** The current trace as W3C headers, or an empty map when nothing is being traced. */
  Map<String, String> capture();
}
