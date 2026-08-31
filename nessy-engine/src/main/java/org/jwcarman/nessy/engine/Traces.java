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
package org.jwcarman.nessy.engine;

import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Trace context across actor messages — the highest-risk part of this port, done by hand.
 *
 * <p><b>Micrometer, not OpenTelemetry.</b> Nessy's observability seam is {@code
 * ObservationRegistry}; OTel is the implementation underneath it via {@code
 * micrometer-tracing-bridge-otel}. An earlier version of this class named {@code
 * io.opentelemetry.api.trace.Tracer} and {@code W3CTraceContextPropagator} directly, which pinned
 * the runtime to one backend for no gain. {@link Tracer} and {@link Propagator} are the same
 * capability one layer up, and Boot supplies both as beans.
 *
 * <p><b>Why by hand at all.</b> Tracing context is a thread-local. A Spring MVC request or a cron
 * tick establishes it on one thread; an actor message is processed on a Pekko dispatcher thread
 * that has never heard of it. Nothing propagates automatically across a mailbox, so a naive port
 * produces one orphan span per actor and no tree.
 *
 * <p><b>Why not {@code ContextSnapshot}.</b> {@code io.micrometer:context-propagation} is already
 * on the classpath and is strictly less work for an in-JVM hop — one object, no carrier, and it
 * carries MDC too. It is a thread-local capture, not a serialisable carrier, so it cannot cross
 * Pekko Remoting. Correctness must not depend on the single-node deployment, so the header map wins
 * on the requirement rather than on elegance. Whoever reads this next will see {@code
 * ContextSnapshot} as the obvious simplification; this paragraph is why it is not.
 *
 * <p><b>Why not the OTel Java agent.</b> It papers over this for one JVM and then breaks silently
 * the day anyone enables clustering — the traces simply stop nesting.
 *
 * <p><b>Spans are declared, never inferred.</b> There is no interceptor opening a span per message.
 * A generic interceptor could only name a span after its message class, which would bury the
 * semconv names that actually matter ({@code chat}, {@code execute_tool}, {@code invoke_agent})
 * inside a tree of our own plumbing. Callers name and decorate their own spans.
 */
public final class Traces {

  private static final String TRACEPARENT = "traceparent";

  private final Tracer tracer;
  private final Propagator propagator;

  public Traces(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }

  /**
   * The current context, flattened to something a message can carry. Empty when untraced.
   *
   * <p>Callers do not pass a context in. Because every receive opens a real span and holds it in
   * scope for the duration of the handler, {@code currentTraceContext()} is already correct at any
   * send site inside an actor — which is what keeps parentage ambient instead of making every
   * sender thread the incoming envelope through by hand.
   */
  public Map<String, String> capture() {
    TraceContext current = tracer.currentTraceContext().context();
    if (current == null) {
      return Map.of();
    }
    Map<String, String> headers = new HashMap<>();
    propagator.inject(current, headers, Map::put);
    return Map.copyOf(headers);
  }

  /**
   * Run {@code work} in a new span whose parent is whatever {@code carried} names.
   *
   * <p>{@link Propagator#extract} returns a builder already parented to the carried context, so
   * everything else — the name, the kind, the tags — is ours to decide.
   */
  public <T> T inSpan(String name, Span.Kind kind, Map<String, String> carried, Supplier<T> work) {
    return inScope(kinded(extract(carried).name(name), kind).start(), work);
  }

  public void inSpan(String name, Span.Kind kind, Map<String, String> carried, Runnable work) {
    inSpan(name, kind, carried, toSupplier(work));
  }

  /**
   * A span with no kind — OpenTelemetry's {@code INTERNAL}, which {@link Span.Kind} cannot name.
   *
   * <p>Micrometer's kind enum follows Brave's four ({@code SERVER}, {@code CLIENT}, {@code
   * PRODUCER}, {@code CONSUMER}) and has no {@code INTERNAL} member, so the only way to ask for
   * OTel's default is to leave the kind unset. Hence the overload rather than a nullable argument.
   */
  public <T> T inSpan(String name, Map<String, String> carried, Supplier<T> work) {
    return inSpan(name, null, carried, work);
  }

  public void inSpan(String name, Map<String, String> carried, Runnable work) {
    inSpan(name, null, carried, toSupplier(work));
  }

  /**
   * A span for work that resumes a round which PARKED — an approval answered days later.
   *
   * <p>It is a new trace with a LINK to the round that asked, not a child of it, and that is honest
   * modelling rather than a limitation worked around. A span has a start and an end; a round that
   * waits three days for a human has no useful duration, and forcing it into one span would produce
   * a three-day trace that no backend will hold open and no human wants to read. The link is what
   * lets you get from the answer back to the question.
   */
  public <T> T inLinkedSpan(
      String name, Span.Kind kind, Map<String, String> linkedTo, Supplier<T> work) {
    Span.Builder builder = kinded(tracer.spanBuilder().name(name), kind);
    contextOf(linkedTo).map(Link::new).ifPresent(builder::addLink);
    return inScope(builder.start(), work);
  }

  public void inLinkedSpan(
      String name, Span.Kind kind, Map<String, String> linkedTo, Runnable work) {
    inLinkedSpan(name, kind, linkedTo, toSupplier(work));
  }

  /** A linked span with no kind — see {@link #inSpan(String, Map, Supplier)} for why. */
  public <T> T inLinkedSpan(String name, Map<String, String> linkedTo, Supplier<T> work) {
    return inLinkedSpan(name, null, linkedTo, work);
  }

  public void inLinkedSpan(String name, Map<String, String> linkedTo, Runnable work) {
    inLinkedSpan(name, null, linkedTo, toSupplier(work));
  }

  /** Adds a tag to whatever span is current, if any. */
  public void tag(String key, String value) {
    Span current = tracer.currentSpan();
    if (current != null) {
      current.tag(key, value);
    }
  }

  /**
   * Adds an integer-valued tag to whatever span is current, if any.
   *
   * <p>GenAI semantic conventions want token counts as integer attributes, not stringified ones,
   * which is what {@link Span#tag(String, long)} produces where {@link #tag(String, String)} would
   * not.
   */
  public void tag(String key, long value) {
    Span current = tracer.currentSpan();
    if (current != null) {
      current.tag(key, value);
    }
  }

  private static Span.Builder kinded(Span.Builder builder, Span.Kind kind) {
    return kind == null ? builder : builder.kind(kind);
  }

  private Span.Builder extract(Map<String, String> carried) {
    Map<String, String> headers = carried == null ? Map.<String, String>of() : carried;
    return propagator.extract(headers, Map::get);
  }

  private <T> T inScope(Span span, Supplier<T> work) {
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      return work.get();
    } catch (RuntimeException e) {
      span.error(e);
      throw e;
    } finally {
      span.end();
    }
  }

  private static Supplier<Void> toSupplier(Runnable work) {
    return () -> {
      work.run();
      return null;
    };
  }

  /**
   * The one place we parse {@code traceparent} ourselves, and it is deliberate.
   *
   * <p>{@link Propagator#extract} is span-oriented: it hands back a {@link Span.Builder} already
   * parented to the carried context, with no way to obtain the {@link TraceContext} alone. A LINK
   * needs a context rather than a parent, so there is no Micrometer-native door for this case. The
   * W3C format is fixed and versioned ({@code 00-<32 hex trace>-<16 hex span>-<2 hex flags>}),
   * which makes this the stable half of an otherwise awkward gap.
   */
  private Optional<TraceContext> contextOf(Map<String, String> headers) {
    if (headers == null) {
      return Optional.empty();
    }
    String traceparent = headers.get(TRACEPARENT);
    if (traceparent == null) {
      return Optional.empty();
    }
    String[] fields = traceparent.split("-");
    if (fields.length < 4) {
      return Optional.empty();
    }
    return Optional.of(
        tracer
            .traceContextBuilder()
            .traceId(fields[1])
            .spanId(fields[2])
            .sampled((Integer.parseInt(fields[3], 16) & 1) == 1)
            .build());
  }
}
