# Nessy Example: Scout

The tool-import showcase: one idea, per the family convention — an imported
toolbox, granted tool-by-tool, over `nessy-tool-mcp`'s security story made
runnable. Scout is a terminal REPL agent, chat-cli's exact posture (plain
`main`, `java.lang.IO`, no Docker, no database, no Spring), that researches
public GitHub repositories through [DeepWiki](https://deepwiki.com)'s no-auth
public MCP server. The deliberate beat: one of the three imported tools routes
through the console approval gate, so a human approves a *remote* server's
tool call, reading `describe()`'s name-plus-JSON prompt before it runs. The
example matrix does not grow beyond this — Scout is the tool-import showcase
on the existing terminal front door, not a second interactive example.

## The grants

```java
.tools(
    ToolGrant.grant(toolbox.tool("read_wiki_structure"), UsagePolicy.allow()),
    ToolGrant.grant(toolbox.tool("read_wiki_contents"), UsagePolicy.allow()),
    ToolGrant.grant(toolbox.tool("ask_question"), UsagePolicy.requireApproval()))
.approver(new ConsoleApprover())
```

`read_wiki_structure` and `read_wiki_contents` are free — Scout can look
around and read without asking. `ask_question` is DeepWiki's own
AI-in-the-loop tool (it burns DeepWiki's own model budget to answer), so it's
the one gated behind `requireApproval()`: a human sees the question before it
goes out.

## Run it

```bash
ANTHROPIC_API_KEY=… ./mvnw -q -pl nessy-examples/scout -am compile exec:java -Dexec.mainClass=org.jwcarman.nessy.examples.scout.Scout
```

The `-am` flag also builds this module's reactor dependencies (`nessy-core`,
`nessy-model-anthropic`, `nessy-tool-mcp`) — the first run compiles that whole
upstream chain and takes noticeably longer; every run after is fast, since
Maven only recompiles what changed.

Needs `ANTHROPIC_API_KEY` **and** network: unlike every other example in this
family, Scout's tools live on someone else's server. There is no offline mode
for the demo itself — `mcp.deepwiki.com` is a live dependency of the running
app, by design (see "The DeepWiki covenant" below).

## The approval prompt

Ask something that needs `ask_question` and the turn parks on
`ConsoleApprover`, which prints the request and blocks on an answer:

```
you> why does the reducer live in one method?
⚙ tool: ask_question
approve: ask_question {"repoName":"jwcarman/nessy","question":"why does the reducer live in one method?"}
y/n>
```

Answer `y` and the call goes out to DeepWiki; anything else denies it, and the
model gets `Denied: declined at the console` back as an ordinary tool result —
it can apologize, rephrase, or route around the question, same as any other
declined tool call in this framework.

## The DeepWiki covenant

DeepWiki is a public, no-auth MCP server Scout doesn't control. Its tool names
are verified against the live server, not guessed from documentation:
`initialize` against `https://mcp.deepwiki.com/mcp` on 2026-08-15 returned
server `DeepWiki 2.14.3` advertising, among others, exactly the three tools
Scout grants — `read_wiki_structure`, `read_wiki_contents`, `ask_question` —
each described in the server's own `instructions` string.

That's the covenant of importing someone else's toolbox: nothing here pins a
version or vendors a schema. If DeepWiki ever renames or removes one of these
tools, `McpToolbox#tool(String)` fails loud, at connect time, before the REPL
ever opens — a `NoSuchElementException` naming every tool actually on offer.
That's the intended failure mode: a drifted remote toolbox breaks the demo
noisily, at startup, rather than silently doing the wrong thing mid-turn.

## Testing

`ScoutTest` is fully offline — no key, no network, no Docker — even though the
demo itself needs both. It reproduces `nessy-tool-mcp`'s own in-process
MCP-server test pattern (`InMemoryMcpTransport` /
`PipedClientTransport`, copied locally with attribution — this module can't
depend on another module's `src/test`) to stand up a real MCP server, shaped
like DeepWiki's own three tools, entirely inside the JVM. A package-private
seam, `Scout#scout(Harness, McpToolbox, String, Approver)`, is the one thing
both `Scout#main` and `ScoutTest` call to build the agent, so the test
exercises the exact grant table the demo runs, not a parallel copy of it. One
case drives an `allow()`-granted tool through the real gated executor and
asserts the answer lands; a second proves the `requireApproval()` grant
actually gates, by watching the in-process server's `ask_question` handler
never get called while a declining approver is in the seat.
