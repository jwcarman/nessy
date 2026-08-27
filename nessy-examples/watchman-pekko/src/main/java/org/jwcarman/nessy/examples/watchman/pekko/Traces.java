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
package org.jwcarman.nessy.examples.watchman.pekko;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Trace context across actor messages — the highest-risk part of this port, done by hand.
 *
 * <p><b>Why by hand.</b> OpenTelemetry's context is a thread-local. A Spring MVC request or a cron
 * tick establishes it on one thread; an actor message is processed on a Pekko dispatcher thread
 * that has never heard of it. Nothing propagates automatically across a mailbox, so a naive port
 * produces one orphan span per actor and no tree at all.
 *
 * <p><b>Why not the OTel Java agent.</b> It would paper over this for a single JVM and then break
 * the day anyone enables clustering, because its propagation does not cross Pekko Remoting. Worse,
 * it would break silently — the traces would simply stop nesting. Carrying the context IN THE
 * MESSAGE is the only approach that survives the actor becoming remote, because a message that
 * crosses a network already has to serialise everything it carries.
 *
 * <p>So the wire format is W3C {@code traceparent}: a short, serialisable string that Pekko can
 * carry anywhere, rather than an in-process {@link Context} object that could never leave the JVM.
 * Every command that begins work carries one; the receiving actor re-opens it as the parent of
 * whatever span it creates.
 */
public final class Traces {

  private static final TextMapSetter<Map<String, String>> SETTER =
      (carrier, key, value) -> {
        if (carrier != null) {
          carrier.put(key, value);
        }
      };

  private static final TextMapGetter<Map<String, String>> GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
          return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier == null ? null : carrier.get(key);
        }
      };

  private final OpenTelemetry otel;
  private final Tracer tracer;

  public Traces(OpenTelemetry otel) {
    this.otel = otel;
    this.tracer = otel.getTracer("watchman-pekko");
  }

  /** The current context, flattened to something a message can carry. Empty when untraced. */
  public Map<String, String> capture() {
    Map<String, String> carrier = new HashMap<>();
    otel.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, SETTER);
    return Map.copyOf(carrier);
  }

  /**
   * Run {@code work} inside a new span whose parent is whatever {@code carried} names.
   *
   * <p>This is the join. The actor is on a dispatcher thread with no ambient context; we make the
   * carried context current for exactly the duration of the fold, and put it back afterwards.
   */
  public <T> T inSpan(String name, Map<String, String> carried, Supplier<T> work) {
    Context parent =
        otel.getPropagators()
            .getTextMapPropagator()
            .extract(Context.root(), carried == null ? Map.of() : carried, GETTER);
    Span span =
        tracer.spanBuilder(name).setSpanKind(SpanKind.INTERNAL).setParent(parent).startSpan();
    try (Scope ignored = span.makeCurrent()) {
      return work.get();
    } catch (RuntimeException e) {
      span.recordException(e);
      throw e;
    } finally {
      span.end();
    }
  }

  public void inSpan(String name, Map<String, String> carried, Runnable work) {
    inSpan(
        name,
        carried,
        () -> {
          work.run();
          return null;
        });
  }

  /**
   * A span for work that resumes a round which PARKED — an approval answered days later.
   *
   * <p>It is a new trace with a LINK to the round that asked, not a child of it, and that is the
   * honest modelling rather than a limitation worked around. A span has a start and an end; a round
   * that waits three days for a human has no useful duration, and forcing it into one span would
   * produce a three-day trace that no backend will hold open and no human wants to read. The link
   * is what lets you get from the answer back to the question.
   */
  public <T> T inLinkedSpan(String name, Map<String, String> linkedTo, Supplier<T> work) {
    Context linked =
        otel.getPropagators()
            .getTextMapPropagator()
            .extract(Context.root(), linkedTo == null ? Map.of() : linkedTo, GETTER);
    Span span =
        tracer
            .spanBuilder(name)
            .setSpanKind(SpanKind.INTERNAL)
            .setNoParent()
            .addLink(Span.fromContext(linked).getSpanContext())
            .startSpan();
    try (Scope ignored = span.makeCurrent()) {
      return work.get();
    } catch (RuntimeException e) {
      span.recordException(e);
      throw e;
    } finally {
      span.end();
    }
  }

  /** Adds an attribute to whatever span is current, if any. */
  public static void attribute(String key, String value) {
    Span.current().setAttribute(key, value);
  }
}
