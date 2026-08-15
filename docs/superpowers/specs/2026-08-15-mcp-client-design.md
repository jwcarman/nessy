# The MCP Client — the world's toolboxes open

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner: "let's do the mcp client";
rulings surfaced in session, not gated; origin: "consuming MCP servers
feels like a big deal. We have no way to turn an MCP tool into a tool
that one of our agents can consume")

---

## 1. Purpose and the zero-kernel claim

A new module, **`nessy-tool-mcp`**, that turns any MCP server's tools
into nessy `Tool`s — wrapping the official MCP Java SDK
(`io.modelcontextprotocol.sdk`), Spring-free, following the
`nessy-model-*`/`nessy-store-jdbc` module pattern.

**The kernel changes not at all**, verified against the shipped
contracts before this spec was written:

- `ToolSpec` already carries a raw Jackson `ObjectNode` schema
  ("wire-neutral on purpose"), and `Tool.spec()` is a *default* method —
  an MCP-backed tool simply overrides it with the schema the server
  advertised instead of deriving one from a record.
- `ToolInvoker` deserializes arguments via
  `mapper.convertValue(call.arguments(), tool.inputType())` — with
  `inputType() = JsonNode.class` that is an identity hop.
- `execute(T, ToolContext)` already exists; MCP results map onto
  `ToolResult`; MCP progress notifications map onto
  `ToolContext.progress`.

## 2. The front door: `McpToolbox`

```java
try (McpToolbox toolbox = McpToolbox.connect(transport, mapper)) {
  Agent<String> agent = harness.agent()
      .name("researcher")
      .tools(
          ToolGrant.grant(toolbox.tool("search"), UsagePolicy.allow()),
          ToolGrant.grant(toolbox.tool("purchase"), UsagePolicy.requireApproval()))
      .build();
}
```

- `McpToolbox.connect(...)` initializes an `McpSyncClient` over a
  caller-supplied transport (stdio and Streamable HTTP both arrive from
  the SDK; nessy adds no transport of its own) and performs the MCP
  `initialize`/`tools/list` handshake. The exact factory signatures
  follow the SDK's own client-construction idiom — the plan's Task 1
  reads the SDK before freezing them; the shape above is the intent.
- `tools()` — every server tool as an immutable `List<Tool<JsonNode>>`;
  `tool(name)` — one by name, throwing with the available names listed
  when absent (fail-noisy discovery).
- `AutoCloseable`: the toolbox owns the client session; closing it
  closes the connection. Tools obtained from a closed toolbox fail loud
  on execute.
- Two servers = two toolboxes = two namespaces. Name collisions between
  servers are the application's business, made visible by the grant
  list itself — you grant what you name.

**The grant principle is the import-security story** and the README's
headline: every imported capability is granted individually, with its
own `UsagePolicy`, exactly like a hand-written tool. Nothing arrives
pre-authorized; `GatedToolCallExecutor`'s wiring-time
every-tool-has-a-grant belt applies to imported tools unchanged.

## 3. `McpTool` (package-private)

- `name()`/`description()` — the server's, verbatim (blank description →
  empty string, `ToolSpec` requires non-null).
- `spec()` — overridden: the server's `inputSchema` converted to
  `ObjectNode` via the module's mapper (`valueToTree` of the SDK's
  schema type; Task 1 reads the SDK's schema representation).
- `inputType()` — `JsonNode.class`; the model's arguments pass through
  untyped, exactly as the server's schema described them.
- `describe(input)` — the tool name plus compact single-line JSON of the
  arguments: the approval prompt must be skimmable, and
  `JsonNode.toString` is already compact; prefix it with the name.
- `execute(input, context)` — `client.callTool(name, arguments)`;
  result mapping:
  - text content blocks joined with newlines → success `ToolResult`;
  - `isError` → the error-shaped `ToolResult` (read `ToolResult`'s
    actual grammar and use its error form);
  - non-text content (images, resources) — v1 degrades honestly:
    JSON-encode the content object into the text output, documented in
    the javadoc as a v1 limitation (tools-only, text-first).
  - Always `Awaited.ready(...)` — MCP tool calls are request/response;
    the durable pairings (elicitation → parks) are deferred (§6).
- **Progress**: if the SDK's sync call surface accepts a progress
  consumer without contortions, forward MCP progress notifications to
  `context.progress`. If the seam proves awkward (async-client-only,
  session-global listeners), DEFER with a report finding rather than
  forcing it — the feature is a bonus, not the thesis.
- Transport/protocol failures surface as the error-shaped `ToolResult`
  where the MCP result itself says error, and as thrown
  `RuntimeException` where the call could not complete at all — the
  executor's existing fail-closed handling does the rest (read how
  `ToolInvoker` treats a throwing tool and align).

## 4. Versioning and dependencies

The MCP Java SDK is not managed by Boot's BOM: the parent pom pins its
version as a property, `nessy-bom` manages the module like its siblings,
and the module depends on the SDK's core artifact only (no Spring, no
transport extras beyond what `tools/list`+`tools/call` need). Task 1
reads the SDK's current artifact layout rather than trusting this
paragraph.

## 5. Testing — the SDK is its own dogfood

The MCP Java SDK ships the **server** side too: tests build a real
in-process MCP server (the SDK's server API over an in-memory/paired
transport if one exists, else stdio to a subprocess-free harness — Task
1 reads what the SDK offers and picks the lightest REAL pairing) and run
the whole handshake offline — no Docker, no key, no network, default
build. That makes this module's tests the first true end-to-end MCP
exchange in the repo.

- Discovery: `tools()` mirrors the server's list; `tool(name)` fails
  noisy with available names.
- Spec fidelity: the served schema comes back byte-equal through
  `McpTool.spec()`.
- Execution: arguments round-trip (server asserts what it received);
  text result maps; `isError` maps to the error shape; non-text content
  degrades as documented.
- The registry seam: an `McpTool` granted through a real
  `AgentBuilder`+`ScriptedModelProvider` turn executes end to end (the
  zero-kernel claim, proven not asserted).
- Closed-toolbox behavior; progress forwarding if implemented.
- A `mocapi`-served demo is banked as a future example, not this wave.

## 6. Deliberately not in this wave

Elicitation → `Awaited.parked` + the HITL flow (the marquee pairing —
its own generation, it touches approval UX); sampling; resources,
prompts, roots (tools only); starter auto-configuration of connections
(tool import is identity work, app-declared — the razor's agent side;
revisit if three examples pay the same wiring tax); any example
adoption; retry/reconnect policy beyond what the SDK does natively.

## 7. Breaking (pre-1.0)

None. Purely additive: new module; no core, SPI, or starter change.
