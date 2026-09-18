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
