# MCP Clients

`nessy-tool-mcp` turns an MCP server's tools into plain Nessy `Tool<JsonNode>`
instances. `McpToolbox` opens a server; each tool it hands back is granted the
same way a hand-written `Tool` is — named individually, paired with its own
`Approver`.

## Import is not authority

Importing a whole server's toolbox does not import authority along with it.
A server offering ten tools yields ten separate grant decisions, never one
blanket "trust this server":

```java
McpToolbox toolbox = McpToolbox.connect(transport, mapper);

Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("traveller"))
        .systemPrompt(prompt)
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .tool(toolbox.tool("search"))
        .tool(toolbox.tool("purchase"), binding -> binding.approver(desk)));

harness.observe(AgentId.of("agent-1"), "find the cheapest flight and buy it");
```

An approver that defers parks every call for someone else to answer — see
[Writing an approver](harness.md#writing-an-approver) for one that also
tells a human it's waiting.

The toolbox is deliberately not opened in a `try`-with-resources here: a
granted `Tool` keeps working only as long as the session that produced it
is open, and the harness — kept, not closed, running for as long as the
process does — may still be running turns against it at any time. The
toolbox must outlive every scope that was granted its tools, which in
practice means it lives exactly as long as the harness does, closed by the
same infrastructure hook (a container's destroy callback, alongside
`harness.shutdown()`) rather than a block exit. Closing it early fails any
in-flight or future call on those tools loud, not silently.

`toolbox.tool(name)` fails loud — `NoSuchElementException` naming every tool
the server actually advertised — rather than handing back `null` for a typo.
`toolbox.tools()` returns every tool the server advertised, in `tools/list`
order, for callers that want the whole set; there is still one binding
per tool, because a tool carries zero authority content on its own.

`McpToolbox` is `AutoCloseable`; closing it closes the underlying MCP
session. A `Tool` obtained before that point keeps working as a plain Java
reference, but calling it after the toolbox closes fails loud rather than
swallowing the closed session.

### No wrapper, no second authorization API

An MCP tool is governed exactly like a hand-written one, because nothing
about authorization lives on the `Tool` interface itself — a granted MCP
tool is granted and gated exactly the same way as
any first-party tool, `ActionContributor` included:

```java
ActionContributor<JsonNode, String> PURCHASE_ACTION =
    ActionContributor.named("purchase", in -> "purchase " + in.get("item").asText());

config.tool(toolbox.tool("purchase"), binding -> binding
        .approver(desk)
        .action(arguments -> "purchase " + arguments.path("flight").asText()));
```

`McpTool#execute` is always a single request/response round trip — never a
park — so it always declares `CompletionPolicy.IMMEDIATE`, the `Tool`
interface's own default. That means an MCP tool passes
`ToolRegistry.limited(base, policy)`'s completion filter unconditionally,
under any wiring: nothing about importing a remote toolbox can advertise a
tool a host isn't equipped to run. See
[Authorization](../concepts/authorization.md) for the whole rung ladder.

## Building the transport

`McpToolbox.connect(McpClientTransport transport, ObjectMapper mapper)` takes
an already-built transport — Nessy adds no transport of its own. The
official MCP Java SDK (`io.modelcontextprotocol.sdk`) ships what an
application needs:

- `StdioClientTransport` (`mcp-core`) — spawn a local MCP server process and
  speak newline-delimited JSON-RPC over its stdin/stdout.
- `HttpClientStreamableHttpTransport` (`mcp-core`) — the Streamable HTTP
  transport, for a server reachable over the network.

Build the transport with whatever construction idiom the SDK's own docs
describe, then hand the result to `connect(...)`. `connect` performs the MCP
`initialize`/`tools/list` handshake once, up front; from then on
`tools()`/`tool(name)` are plain in-memory lookups against that snapshot.

## A worked example: DeepWiki

`nessy-examples/mcp` is this, runnable — see
[its README](https://github.com/jwcarman/nessy/tree/main/nessy-examples/mcp).


[DeepWiki](https://deepwiki.com) publishes a no-auth public MCP server for
researching public GitHub repositories — a convenient real server to import
against, since it needs no credential of its own:

```java
McpToolbox toolbox = McpToolbox.connect(transport, mapper);

Harness<String> harness = factory.createHarness(String.class, config -> config
        .type(AgentType.of("researcher"))
        .systemPrompt(prompt)
        .model(ModelId.of("claude-opus-4"))
        .renderer(UserMessage::of)
        .tool(toolbox.tool("read_wiki_structure"))
        .tool(toolbox.tool("read_wiki_contents"))
        .tool(toolbox.tool("ask_question"), binding -> binding.approver(desk)));

harness.observe(AgentId.of("researcher"), "what does jwcarman/nessy's harness module do?");
```

`read_wiki_structure` and `read_wiki_contents` are free — the agent can look
around and read without asking. `ask_question` is DeepWiki's own
AI-in-the-loop tool (it burns DeepWiki's own model budget to answer), so
it's the one gated behind an approver that parks: the `ApprovalRequest` a
human reads off `context.request()` carries the rendered action, before
`harness.approvals().approve(id, principal, note)` lets the call out to a
remote server. See [The harness guide](harness.md) for the rest of that
flow.

Tool names granted this way are verified against the live server, not
guessed from documentation. That's the covenant of importing someone else's
toolbox: if DeepWiki ever renames or removes one of these tools,
`McpToolbox#tool(String)` fails loud, at connect time, before the host ever
takes its first observation — a drifted remote toolbox breaks the wiring
noisily at startup rather than silently doing the wrong thing mid-turn.

## v1 boundaries

- **Tools only.** Resources, prompts, and roots are not wrapped — an MCP
  server's tools are the only surface this module turns into a Nessy `Tool`.
- **Text-first, with honest degradation.** A tool's result maps text content
  blocks (joined with newlines) onto a success `ToolResult`, and an
  `isError` result onto the error-shaped `ToolResult`. Non-text content —
  images, embedded resources — has no text-shaped Nessy analog yet, so v1
  degrades honestly: the content object is JSON-encoded into the text output
  rather than silently dropped.
- **No elicitation or sampling yet.** Every `McpTool#execute` call is a
  single request/response round trip — never a park. MCP elicitation (a
  server asking the *caller* a question mid-call) would pair naturally with
  Nessy's `Awaited.deferred()` and durable HITL, but that pairing is its own
  generation of work — banked, not forgotten.
- **The SDK's 20-second request/init timeout applies as-is.** Neither
  `connect` nor `McpToolbox` exposes a way to raise it yet. Real MCP tools
  (web search, code execution) routinely run longer than 20 seconds, so a
  slow server or a slow tool call can time out before it answers.
- **Progress notifications are not forwarded anywhere.**
  The SDK's sync client exposes only a session-global progress consumer, not
  one scoped to a single `tools/call`, so v1 defers progress forwarding
  rather than leaking one call's progress into another's context.

## Where next

- [Tools](../concepts/tools.md) — the grant principle `McpToolbox`-sourced
  tools are subject to like any other, and `CompletionPolicy`'s filtering
  order.
- [Authorization](../concepts/authorization.md) — `ActionContributor`,
  enrichers, and the policy an MCP tool's grant is judged by.
- [The harness guide](harness.md) — the approval desk a
  `requireApproval()` grant on an imported tool routes through.
