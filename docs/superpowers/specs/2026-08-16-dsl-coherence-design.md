# DSL coherence — one idiom, no build()

**Date:** 2026-08-16
**Status:** RATIFIED in conversation (owner rulings complete, including the
provider-builder scope ruling: statics + customizer).
**Design of record:** subordinate to `2026-08-09-nessy-agent-harness-design-v2.md`;
extends the construction idiom the subagents-v2 generation introduced with
`SubagentConfig`/`SubagentCustomizer`.

## 1. The law

One construction idiom across the public surface: **a factory method takes a named
customizer, hands it a config object with fluent setters and NO `build()` method, and
returns the finished thing.** Nothing half-built is representable; every required field
is validated by the factory the instant the lambda returns, failing with an exception
that names the field. No public `build()` survives anywhere. No release exists — the
old builder surface is removed, not deprecated.

The naming family: `XCustomizer` customizes `XConfig`. Customizers are
`@FunctionalInterface`s (javadoc anchor, future default methods — the Spring
`Customizer<T>` precedent).

**Amendment (owner ruling, 2026-08-16 evening): `XConfig` is an INTERFACE.** The
config type the customizer receives carries the configuration verbs and nothing
else — the owner's original intent for the Config/Builder split. A package-private
implementation (the builder, returning itself from every setter) implements the
interface and carries the construction machinery; the factory hands the impl out
typed as the interface. The lambda's static type thereby PROVES configuration
purity: no build(), no assembly accessors, no internals reachable through the
reference, not even from the same package. Zero call-site churn — lambda bodies
are unchanged; only type declarations move. Applies to every config in §2's
family (Harness, Agent<T>, Subagent<T>, Repl, TurnObserver, PipelineMemory,
ScriptedModelProvider, and the four provider configs).

## 2. The surfaces

- **Harness:** `Nessy.harness(HarnessCustomizer)` → `Harness`. The provider moves
  inside the lambda (`h.provider(p)`), deliberately reversing the v1 "enforced by
  signature" stance: the factory validates at the same call site with the same
  immediacy, and one required-fields rule everywhere beats two mechanisms. README's
  front-door prose updates accordingly.
- **Agents:** `harness.agent(AgentCustomizer<String>)` → `Agent<String>` (the everyday
  door) and `harness.agent(Class<T>, AgentCustomizer<T>)` → `Agent<T>` (typed inputs —
  the `Class` token up front lets the compiler unify `T` across the customizer, config,
  and renderer — type agreement is compile-time, not a runtime check (amended after
  Task 1 review: the default JSON renderer means no reachable missing-renderer path
  exists, and erasure leaves a stored token nothing to compare against)). `AgentBuilder` becomes `AgentConfig<T>`; the subagent doors it
  already carries keep their `SubagentCustomizer` shape unchanged.
- **Providers (ruled: statics + customizer):** each provider module kills its public
  builder and ships the blessed one-call statics plus a customizer factory:
  `AnthropicModelProvider.fromEnv()` / `.create(AnthropicProviderCustomizer)`, and
  likewise OpenAI (`apiKey`/`baseUrl` config), Gemini (`apiKey`/`baseUrl`/`fromEnv`),
  Bedrock (`region`/`credentialsProvider`/`client`/`fromEnv` — `fromEnv()` static
  included). The internal SDK-builder wrapping is unchanged; only nessy's surface
  converts.
- **Console:** `ConsoleRepl` converts (`ConsoleRepl.run(agent, ReplCustomizer)` or the
  closest shape that preserves its current chrome/plan/farewell options — exact form
  settles at planning against the current class).
- **Inventory rule for everything else:** planning inventories every remaining public
  `build()`/builder in the API (known candidates: `TurnObserver.builder()`,
  `PipelineMemory`'s assembly if it exposes one, `ToolGrant` if applicable) and
  converts each with the same pattern — statics for one-liner cases, customizer for
  multi-field ones. Anything intentionally exempted must be listed in the plan with a
  reason (SPI store interfaces and the sealed message grammar are out of scope by
  nature — they are not built through builders).

## 3. Migration

Everything in-repo migrates in the same generation: examples (all eight), autoconfigure
(its provider construction), docs (every code sample), tests. The changelog stays
first-release-clean — vocabulary updates in place, no "changed from builder" entries.
The Trying-a-Provider guide's env-driven commands are unaffected (EnvModelProviders'
public surface is `fromEnv()`/`select()` statics already — spot-check at planning).

## 4. Sequencing

Builds after subagents-v2 merges (that generation owns `AgentBuilder` until then). The
reflection generation follows this one and writes its `Reflection.critic(c -> ...)`
factory in this idiom from birth (its spec already assumes so).

## 5. Testing

House rules. Per surface: required-field validation names the field; customizer
receives a live config and the factory result reflects every setter; the typed-agent
`Class` token path preserves renderer-type agreement; provider statics equal their
customizer-built counterparts in behavior (offline seam tests); no public `build()`
remains (an architecture test or grep gate pins it).
