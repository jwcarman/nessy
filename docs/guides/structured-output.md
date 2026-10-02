# Structured Output

Asking a model for a typed answer, instead of prose you parse yourself.

## Naming a shape

`DirectHarnessFactory.create` has an overload that takes the answer's type
alongside the agent type:

```java
public record Weather(String summary, int highF, int lowF) {}

DirectHarness<String, Weather> forecaster = factory.create(
        new AgentType("forecaster"),
        Weather.class,
        config -> config.systemPrompt("Answer with today's weather, nothing else."));
```

This `factory` was built with a default model, so the harness need not name
one: `config.inference(ProviderId.of("anthropic"), InferenceOptions.of("claude-sonnet-5"))`
beside the backend and the provider. See
[Getting Started](getting-started.md#the-smallest-harness).

Naming `Weather.class` here does two things at once. The schema is
generated once, when the harness is built, and sent to the provider on
every call so the model is constrained to that shape on the wire — natively,
on every vendor that supports it. And what comes back is parsed into
`Weather` before the caller ever sees it: a caller never learns which
mechanism the vendor used underneath.

A harness built without naming a shape — `factory.create(agentType,
customizer)` — asks nothing of the answer's form. What comes back is the
model's prose, joined, and `O` is `String`.

A shape that itself takes type arguments, a list of records for instance,
has no `Class<O>` to name — a `TypeRef<O>` does, and `create` has an
overload that takes one instead. The schema is generated from the whole
type, so `TypeRef<List<Item>>` describes typed items.

Any answer type works: a record, a list, a map, an enum, a string, a sealed
type. Providers require an object at the root of an answer schema, so a type
whose schema is not an object travels wrapped: the model is asked for
`{"value": ...}`, with the type's schema under `value`, and the harness hands
the caller what was under it. A record answer needs no wrapper and goes as
generated. The transcript keeps the model's own text, wrapper included.

## Asking, and reading the outcome

```java
Outcome<Weather> outcome = forecaster.ask(agentId, "what's the weather in Columbus, Ohio?");

switch (outcome) {
  case Outcome.Answered<Weather>(Weather weather, _) -> System.out.println(weather);
  case Outcome.Refused<Weather>(String category, _) -> System.out.println("declined: " + category);
  case Outcome.Failed<Weather>(String reason, _) -> System.out.println("failed: " + reason);
  case Outcome.Busy<Weather> _ -> System.out.println("busy, try again");
}
```

`Outcome<O>` is the same sealed answer shape `DirectHarness.ask` always
returns, whatever `O` is. `Answered` carries the value in the shape that
was asked for — `Weather` here, plain text when no shape was named.
`Refused` is the model declining. `Busy` means another turn was already
running on this agent and nothing happened.

`Failed` is where a model's answer that would not fit the requested shape
arrives. The turn happened and the model spoke; what came back did not
parse into `Weather`, which is a failure of the asking rather than a
refusal by the model, so it lands beside every other reason a turn can end
without an answer rather than getting an arm of its own.

## How the answer is read back

The default reading is JSON, parsed into the type that was asked for —
`OutputReader.json(mapper, type)`, applied automatically once a shape is
named, because a shape was requested by naming a Java type and the model
was handed the schema generated from that same type, so JSON matching it
is exactly what was requested.

An application that wants the words as the model actually sent them,
unparsed, supplies `OutputReader.text()` instead through the `create`
overload that takes a reader. The schema still reaches the provider — a
caller supplying its own reader is saying how the answer is spelled, not
that it may be anything — so this is for a model that answers a shape
while spelling it differently: XML, a vendor's own envelope, or binding
rules the shared mapper does not carry.

## How a shape becomes a schema

`JsonSchemaGenerator` is the seam: a Java type in (a `Class`, or a
`java.lang.reflect.Type` that keeps type arguments), a `JsonSchema` out. `VictoolsJsonSchemaGenerator`, in `nessy-engine`, is the
shipped implementation, built on the [victools](https://github.com/victools/jsonschema-generator)
generator. It walks a record's components into properties and turns
`@JsonPropertyDescription` into the text a model reads, so a well-named
record is its own documentation.

The same generator produces a tool's input schema and a harness's answer
schema — one job, whichever direction it points, which is why `JsonSchema`
is one type for both rather than two.

!!! warning "A shape with no components needs an explicit empty schema"
    A record with no components — `record Ping() {}` — generates
    `{"type":"object"}` on its own, which is valid JSON Schema and is
    **rejected on the wire**: OpenAI's function-calling shape requires
    `parameters.properties` to be present. `VictoolsJsonSchemaGenerator`
    adds an empty `properties` object whenever it would otherwise be
    missing, so a no-argument shape still publishes the object the wire
    requires. A generator written by hand needs to do the same.

## Where next

- [The Harness](harness.md), the rest of a direct harness's configuration
- [Providers](providers.md), what a provider does with the schema it is handed
- [Tools](../concepts/tools.md), the other place a schema is generated from a type
