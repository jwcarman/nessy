# Nessy Tool MCP

## The grant principle as import security

Every tool an MCP server advertises still needs its own grant before an
agent may call it — importing a whole server's toolbox does not import
authority along with it. `McpToolbox` opens a server's tools as plain
nessy `Tool<JsonNode>` instances; nothing about that act pre-authorizes
anything. Each one is wired into an agent the same way a hand-written
`Tool` is — named individually, paired with its own `UsagePolicy` — so a
server offering ten tools yields ten separate grant decisions, not one
blanket "trust this server":

```java
try (McpToolbox toolbox = McpToolbox.connect(transport, mapper)) {
  Agent<String> agent =
      harness
          .agent()
          .name("researcher")
          .tools(
              ToolGrant.grant(toolbox.tool("search"), UsagePolicy.allow()),
              ToolGrant.grant(toolbox.tool("purchase"), UsagePolicy.requireApproval()))
          .build();
}
```

`toolbox.tool(name)` fails noisy — `NoSuchElementException` naming every
tool the server actually advertised — rather than handing back `null` for
a typo. `toolbox.tools()` returns every tool the server advertised, in
`tools/list` order, for callers that want to grant the whole set (still
one `ToolGrant` per tool — `AgentBuilder#tools` has no bulk-grant form,
because a tool carries zero authority content on its own; every
attachment states its policy or does not compile). Two servers make two
toolboxes and two namespaces: a name collision between them is the
application's business, made visible in the grant list itself — you
still grant what you name.

`McpToolbox` is `AutoCloseable`; closing it closes the underlying MCP
session. A `Tool` obtained before that point keeps working as a plain
Java reference, but calling it afterward fails loud rather than
swallowing the closed session.

## Transports arrive from the SDK

`McpToolbox.connect(McpClientTransport transport, ObjectMapper mapper)`
takes an already-built transport — nessy adds no transport of its own.
The official MCP Java SDK (`io.modelcontextprotocol.sdk`) ships the
transports an application actually needs:

- `StdioClientTransport` (`mcp-core`) — spawn a local MCP server process
  and speak newline-delimited JSON-RPC over its stdin/stdout.
- `HttpClientStreamableHttpTransport` (`mcp-core`) — the Streamable HTTP
  transport, for a server reachable over the network.

An application builds the transport with whatever construction idiom the
SDK's own docs describe for that transport, then hands the result to
`connect(...)`. `connect` performs the MCP `initialize`/`tools/list`
handshake once, up front; from then on `tools()`/`tool(name)` are plain
in-memory lookups against that snapshot — no request goes out just to
look a tool up.

## v1 boundaries

- **Tools only.** Resources, prompts, and roots are not wrapped — an MCP
  server's tools are the only surface this module turns into a nessy
  `Tool`.
- **Text-first, with honest degradation.** A tool's result maps text
  content blocks (joined with newlines) onto a success `ToolResult`, and
  an `isError` result onto the error-shaped `ToolResult`. Non-text
  content — images, embedded resources — has no text-shaped nessy analog
  yet, so v1 degrades honestly: the content object is JSON-encoded into
  the text output rather than silently dropped.
- **No elicitation or sampling yet.** Every `McpTool#execute` call is a
  single request/response round trip — `Awaited.ready(...)`, never a
  park. MCP elicitation (a server asking the *caller* a question
  mid-call) would pair naturally with nessy's `Awaited.parked` and the
  durable HITL flow, but that pairing touches approval UX and is its own
  generation of work — banked, not forgotten. Sampling (a server asking
  the caller's *model* to complete something) is banked alongside it.
- **The SDK's 20-second request/init timeout applies as-is.** `McpToolbox.connect` builds the
  client with `McpClient.sync(transport).build()`'s own defaults; neither `connect` nor
  `McpToolbox` exposes a way to raise them yet. Real MCP tools (web search, code execution)
  routinely run longer than 20 seconds, so a slow server or a slow tool call can time out
  before it answers. Configurability arrives with the starter wiring, a later generation —
  not this one.
- **Progress notifications are not forwarded to `ToolContext.progress`.**
  The SDK's sync client (`McpSyncClient`) exposes only a session-global
  progress consumer, registered once at client build time and applied to
  every call the session ever makes — not one scoped to a single
  `tools/call`. Wiring one here would leak another call's progress
  notifications into this tool's context, so v1 defers progress
  forwarding rather than forcing a seam the SDK's sync surface doesn't
  offer cleanly. A future async-client-based path could revisit this.

## The Jackson note

This module depends on `mcp-json-jackson2`, not the `mcp` facade
artifact. The facade defaults to `mcp-json-jackson3` (Jackson 3), while
the rest of this repo — including `ToolSpec`'s `ObjectNode` — is built on
Jackson 2 (`com.fasterxml.jackson.core`). Depending on `mcp-core` plus
`mcp-json-jackson2` explicitly, rather than the facade, keeps Jackson 3
off this module's classpath entirely and lets `McpToolbox.connect` be
handed nessy's own `ObjectMapper` directly (via `JacksonMcpJsonMapper`
at the transport's own construction time) instead of a
ServiceLoader-discovered default.

## Testing

The MCP Java SDK ships the **server** side too, so this module's tests
run the whole `initialize`/`tools/list`/`tools/call` handshake against a
real, in-process MCP server — no Docker, no key, no network, default
build. Discovery, schema fidelity, execution (including the `isError`
and non-text-degradation paths), and closed-toolbox behavior are all
proven against that real server. An end-to-end test grants an `McpTool`
through a real `AgentBuilder` and drives it via a scripted model
provider and the actual `ToolInvoker`/`GatedToolCallExecutor` path — the
zero-kernel claim (the kernel needed no changes at all to run an
MCP-backed tool) proven, not merely asserted.
