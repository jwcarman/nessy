# MCP Clients

`nessy-tool-mcp` turns an MCP server's tools into plain Nessy `Tool<JsonNode>`
instances. `McpToolbox` opens a server; each tool it hands back is granted the
same way a hand-written `Tool` is — named individually, paired with its own
`UsagePolicy`.

## Import is not authority

Importing a whole server's toolbox does not import authority along with it.
A server offering ten tools yields ten separate grant decisions, never one
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

`toolbox.tool(name)` fails loud — `NoSuchElementException` naming every tool
the server actually advertised — rather than handing back `null` for a typo.
`toolbox.tools()` returns every tool the server advertised, in `tools/list`
order, for callers that want the whole set; there is still one `ToolGrant`
per tool, because a tool carries zero authority content on its own.

`McpToolbox` is `AutoCloseable`; closing it closes the underlying MCP
session. A `Tool` obtained before that point keeps working as a plain Java
reference, but calling it after the toolbox closes fails loud rather than
swallowing the closed session.

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

## A worked example: Scout

`nessy-examples/scout` researches public GitHub repositories through
[DeepWiki](https://deepwiki.com)'s no-auth public MCP server — a terminal
REPL agent, `chat-cli`'s exact posture, with an imported toolbox granted
tool-by-tool:

```java
try (McpToolbox toolbox =
    McpToolbox.connect(
        HttpClientStreamableHttpTransport.builder(DEEPWIKI_URL).build(), mapper)) {
  Agent<String> agent =
      harness
          .agent()
          .name("scout")
          .tools(
              ToolGrant.grant(toolbox.tool("read_wiki_structure"), UsagePolicy.allow()),
              ToolGrant.grant(toolbox.tool("read_wiki_contents"), UsagePolicy.allow()),
              ToolGrant.grant(toolbox.tool("ask_question"), UsagePolicy.requireApproval()))
          .approver(new ConsoleApprover())
          .build();
}
```

`read_wiki_structure` and `read_wiki_contents` are free — Scout can look
around and read without asking. `ask_question` is DeepWiki's own
AI-in-the-loop tool (it burns DeepWiki's own model budget to answer), so
it's the one gated behind `requireApproval()`: a human reads
`describe()`'s name-plus-JSON prompt before it goes out to a remote server.

Scout's tool names are verified against the live server, not guessed from
documentation. That's the covenant of importing someone else's toolbox: if
DeepWiki ever renames or removes one of these tools, `McpToolbox#tool(String)`
fails loud, at connect time, before the REPL ever opens — a drifted remote
toolbox breaks the demo noisily at startup rather than silently doing the
wrong thing mid-turn.

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
  Nessy's `Awaited.parked` and durable HITL, but that pairing is its own
  generation of work — banked, not forgotten.
- **The SDK's 20-second request/init timeout applies as-is.** Neither
  `connect` nor `McpToolbox` exposes a way to raise it yet. Real MCP tools
  (web search, code execution) routinely run longer than 20 seconds, so a
  slow server or a slow tool call can time out before it answers.
- **Progress notifications are not forwarded to `ToolContext.progress`.**
  The SDK's sync client exposes only a session-global progress consumer, not
  one scoped to a single `tools/call`, so v1 defers progress forwarding
  rather than leaking one call's progress into another's context.

## Where next

- [Tools and Grants](../concepts/tools-and-grants.md) — the grant principle
  `McpToolbox`-sourced tools are subject to like any other.
- [Console Apps](console-apps.md) — `ConsoleApprover`, the gate Scout's
  `ask_question` grant routes through.
- [Planning Your Agent's Work](planning-your-agents-work.md) — Scout's other
  showcase, the plan facility, wired beside these same grants.
