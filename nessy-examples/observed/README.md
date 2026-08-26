# Nessy Example: Observed

`hello`'s exact turn — one calculator tool, one question — run against a real
`ObservationRegistry` instead of `ObservationRegistry.NOOP`. Where `hello`
proves the five-minute promise, this proves the numbers: `invoke_agent`,
`chat`, and `execute_tool` spans over OTLP traces, the semconv
`gen_ai.client.token.usage` metric over OTLP metrics, and this class's own
log lines correlated to the trace that produced them.

Nothing here lives in `nessy-agent`. `HarnessConfig.observationRegistry(...)`
is the one seam (see the [observability guide](../../docs/guides/observability.md));
everything below — the OpenTelemetry SDK, the OTLP exporters, the Micrometer
tracing bridge, the token-usage handler — is application wiring, built once
in `Observed.telemetry()`/`Observed.observationRegistry(...)` and handed to
`Nessy.harness(...)` through that one method.

## Running a collector

The demo runs against Grafana's all-in-one LGTM image (Loki, Grafana, Tempo,
Mimir/Prometheus — a self-contained OTel collector plus a UI to read what it
collected):

```shell
docker run --rm -it \
  -p 3000:3000 -p 4317:4317 -p 4318:4318 \
  grafana/otel-lgtm
```

Grafana comes up at <http://localhost:3000> (no login needed for the
all-in-one image). The collector listens on `4317` (OTLP/gRPC — traces and
logs here) and `4318` (OTLP/HTTP — metrics here); `OTEL_EXPORTER_OTLP_ENDPOINT`
overrides the host if the collector isn't on `localhost` (default
`http://localhost:4318`, per the OTLP/HTTP metrics exporter's own default —
see `Observed`'s javadoc for how the trace/log path derives `4317` from it).

## Running the example

With the LGTM container up:

```shell
./mvnw -q -pl nessy-examples/observed -am compile exec:java -Dexec.args=--scripted
```

Drop `--scripted` and set `ANTHROPIC_API_KEY` to run it against a real model
instead — nothing about the export path changes.

**With no collector running at all**, the example still completes: every
exporter here is documented to log a failed export rather than throw (the
OpenTelemetry SDK's span/log processors log and move on; Micrometer's push
meter registry catches and logs its own publish failures), and
`Observed.shutdownQuietly` is one more layer of the same rule the harness
itself follows — an `ObservationHandler` must never be the reason a turn
fails. `ObservedTest` runs exactly this way, in the default offline build,
and is the proof that stays true rather than a claim made once.

## Finding the trace

In Grafana, **Explore** → **Tempo** → search by service name
(`nessy-example-observed`). One trace per segment (agentic-o11y spec §2):

```
invoke_agent agent
├── chat scripted             (gen_ai.usage.input_tokens/output_tokens, gen_ai.response.finish_reasons)
└── execute_tool calculate    (gen_ai.tool.name, gen_ai.tool.type=function)
```

A parked call (see `nessy-examples/approvals` for that arc) would show its
`nessy.approval.wait`/`nessy.tool.wait` span as a second trace's child
instead — segments never straddle a park, so the wait spans are what a
dashboard reads for "how long did the human take."

## Finding the metrics

**Explore** → **Prometheus** (Mimir, in this image) → query
`gen_ai_client_token_usage_sum` (Prometheus's own naming: dots become
underscores) filtered by `gen_ai_token_type="input"` or `"output"`; or any of
the operation timers Micrometer derives from the spans themselves —
`invoke_agent_seconds`, `chat_seconds`, `execute_tool_seconds` — each with
`_count` for a call-volume dashboard. `gen_ai_client_token_usage` is this
module's own `TokenUsageHandler`, not `nessy-agent`: the `chat` observation
carries the vendor's token counts as key-values because an
`ObservationRegistry` can time an operation but cannot record a value
histogram on its own (spec §1.2) — this ten-line handler is that missing
piece, application-side.

## Logs

`Observed` installs `OpenTelemetryAppender` (from
`io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0`) once
the SDK is built, so this class's own SLF4J log lines ship over the same
OTLP/gRPC collector as the traces, tagged with whichever trace/span was
current when the line was logged — **Explore** → **Loki** → filter by
`service_name="nessy-example-observed"`, then follow a log line's trace id
back into Tempo. That artifact is maintained (published on every
`opentelemetry-java-instrumentation` release) — its version carries a
perpetual `-alpha` suffix by that project's own versioning convention, not
because it's abandoned or unstable. Console logging (`logback.xml`'s
`CONSOLE` appender) keeps working unconditionally either way; the OTLP
appender is additive.
