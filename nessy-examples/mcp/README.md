# nessy-examples/mcp

A terminal agent whose tools belong to somebody else.

Every other example here writes its own tools. This one writes none: it
connects to [DeepWiki](https://deepwiki.com)'s public MCP server and grants
what that server advertises. An imported tool is an ordinary `Tool` — granted
the same way, gated the same way, described the same way — so nothing
downstream can tell the difference.

## Running it

DeepWiki needs no credential. You need a model:

```bash
export OPENAI_API_KEY=not-needed
export OPENAI_BASE_URL=http://localhost:1234/v1
export NESSY_MODEL=<a model your endpoint serves>

./mvnw -q -pl :nessy-example-mcp -am compile exec:java
```

Then ask it something:

```
> what does jwcarman/nessy do?
```

## What it demonstrates

**Three tools, two policies.** `read_wiki_structure` and `read_wiki_contents`
are free, so they are ungated — the agent looks around and reads without
asking. `ask_question` puts the question to DeepWiki's own model and spends
their budget, so it is the one behind an approver. That is this application's
judgement about its own bill, not Nessy's judgement about DeepWiki.

**A action renderer for a tool you did not write.** An imported tool's input is a
`JsonNode`, because the server declared the schema. The action renderer reads the
fields it knows — `repoName`, `question` — and falls back to the whole
document rather than guessing, so a person always sees what they are
approving.

**Names verified against the live server.** `McpToolbox#tool(String)` fails
at connect time if DeepWiki renames a tool, so a drifted remote toolbox
breaks the wiring loudly at startup rather than quietly mid-turn.

## Something worth knowing

`read_wiki_contents` can return a repository's entire wiki — measured at
**341,962 characters** for a medium-sized project. Nothing in Nessy bounds a
tool result before it reaches the model: `maxTokens` caps what the model
writes, and `Memory` shapes the transcript, but a tool result inside the
current exchange is neither.

A small local model may fail on that, and the failure surfaces as whatever
the provider says. That is a real property of importing somebody else's
tools, and this example is where you will meet it.
