# MCP Clients

`nessy-tool-mcp` turns an MCP server's tools into plain Nessy
`Tool<JsonNode>` instances. `McpToolbox` opens a server; each tool it hands
back is granted the same way a hand-written `Tool` is, named individually,
paired with its own `Approver`.

## Import is not authority

Importing a whole server's toolbox does not import authority along with it.
A server offering ten tools yields ten separate grant decisions, never one
blanket "trust this server":

```java
McpToolbox toolbox = McpToolbox.connect(transport, mapper);

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("traveller"))
        .systemPrompt(prompt)
        .tool(toolbox.tool("search"))
        .tool(toolbox.tool("purchase"), binding -> binding
                .approver(desk)
                .action(arguments -> "purchase " + arguments.path("flight").asText())));

harness.observe(agentId, "find the cheapest flight and buy it");
```

An MCP tool is governed exactly like a hand-written one, because nothing
about authorization lives on the `Tool` interface itself: approver, action
renderer, enrichers, timeout and retry policy all apply.

The toolbox is deliberately not opened in a `try`-with-resources. A granted
`Tool` keeps working only as long as the session that produced it is open,
and the harness, kept and not closed, may be running turns against it at any
time. The toolbox must outlive every harness that was granted its tools,
closed by the same infrastructure hook that closes the factory. Closing it
early fails any in-flight or future call on those tools loudly, not
silently.

`toolbox.tool(name)` fails loud, a `NoSuchElementException` naming every
tool the server actually advertised, rather than handing back `null` for a
typo. `toolbox.tools()` returns every tool the server advertised, in
`tools/list` order; there is still one binding per tool, because a tool
carries no authority on its own.

## Building the transport

`McpToolbox.connect(McpClientTransport transport, JsonMapper mapper)` takes
an already-built transport; Nessy adds no transport of its own. The official
MCP Java SDK ships what an application needs:

- `StdioClientTransport`: spawn a local MCP server process and speak
  newline-delimited JSON-RPC over its stdin/stdout.
- `HttpClientStreamableHttpTransport`: the Streamable HTTP transport, for a
  server reachable over the network.

`connect` performs the `initialize` and `tools/list` handshake once, up
front; from then on `tools()` and `tool(name)` are in-memory lookups against
that snapshot.

## A worked example: DeepWiki

`nessy-examples/mcp` is this, runnable.
[DeepWiki](https://deepwiki.com) publishes a no-auth public MCP server for
researching public GitHub repositories:

```java
McpToolbox toolbox = McpToolbox.connect(transport, mapper);

Harness<String> harness = factory.create(config -> config
        .agentType(new AgentType("researcher"))
        .systemPrompt(prompt)
        .tool(toolbox.tool("read_wiki_structure"))
        .tool(toolbox.tool("read_wiki_contents"))
        .tool(toolbox.tool("ask_question"), binding -> binding.approver(desk)));

harness.observe(agentId, "what does jwcarman/nessy's harness module do?");
```

`read_wiki_structure` and `read_wiki_contents` are free. `ask_question` is
DeepWiki's own AI-in-the-loop tool, spending DeepWiki's model budget, so it
is the one gated behind an approver. If DeepWiki ever renames or removes one
of these tools, `toolbox.tool(name)` fails at connect time, before the
harness takes its first observation.

## Boundaries

- **Tools only.** Resources, prompts and roots are not wrapped.
- **Text-first, with honest degradation.** Text content blocks map onto a
  success result and an `isError` result onto a failure. Non-text content is
  JSON-encoded into the text rather than silently dropped.
- **Never a park.** Every `McpTool.call` is a single request and response.
  MCP elicitation would pair naturally with `Awaited.deferred()` and is not
  built yet.
- **The SDK's request timeout applies as-is.** Real MCP tools can run
  longer than it; set the binding's timeout with that in mind.

## Where next

- [Tools](../concepts/tools.md), the grant principle every tool is subject to
- [Authorization](../concepts/authorization.md), approvers and enrichers
- [The Harness](harness.md), the desk an imported tool's approval routes through
