# Scout — the agent that reads other people's code

**Date:** 2026-08-15
**Status:** APPROVED — 2026-08-15 (owner: "I love scout!"; design ratified
in chat verbatim)

## 1. The lesson

One idea, per the family convention: **an imported toolbox, granted
tool-by-tool** — `nessy-tool-mcp`'s security story made runnable. A
terminal REPL agent researches public GitHub repositories through
DeepWiki's no-auth public MCP server, and the deliberate beat is that one
imported tool routes through the console approval gate: a human approves
a REMOTE server's tool call, reading `describe()`'s name-plus-JSON
prompt. The example matrix does not grow — Scout is the tool-import
showcase on the existing terminal front door.

## 2. Shape

New module `nessy-examples/scout`, chat-cli's exact posture: plain main
with `java.lang.IO`, no Docker, no database, no Spring; needs
`ANTHROPIC_API_KEY` and network at run time (DeepWiki is remote). Wiring
as ratified:

- `McpToolbox.connect(HttpClientStreamableHttpTransport.builder("https://mcp.deepwiki.com/mcp").build(), mapper)`
  (exact builder idiom verified against the SDK at build time),
  try-with-resources around the REPL loop.
- Agent `.name("scout")`, a researcher system prompt, grants:
  `read_wiki_structure` → `allow()`, `read_wiki_contents` → `allow()`,
  `ask_question` → `requireApproval()`, with chat-cli's `ConsoleApprover`
  (reuse by copy in the example's own package if chat-cli's is
  module-private — examples don't depend on each other; note which).
- `TurnObserver.logging` or chat-cli's delta rendering — match chat-cli
  (streaming REPL = deltas; read its mains and mirror).
- Tool names are DeepWiki's real ones — verify against the live server
  once during development (`tools/list`) and record them in the README;
  fail-noisy lookup means a drifted name breaks loud at startup, which
  the README notes as the covenant of importing someone else's toolbox.

## 3. Testing

One offline scripted test, default build, no key/network/Docker: the
in-process real-SDK-server pattern from `nessy-tool-mcp`'s own suite
serves scout-shaped tools; `ScriptedModelProvider` drives a turn that
calls an imported tool through the real executor; assert the tool's
answer lands. DeepWiki itself is touched only by humans running the
demo. The main's wiring and the test share construction via a small
package-private seam so the test genuinely exercises the demo's own
grant table (not a parallel copy).

## 4. Paperwork

Module README in the family voice: the lesson, the grants block front
and center, run command with first-run reactor note, needs-key-and-
network, DeepWiki no-auth covenant (public server, names can drift,
fail-noisy at connect), the approval-prompt transcript a user will see.
Root README: examples family gains the row/sentence (count updated —
the consistency sweep's arithmetic stays honest). CHANGELOG `### Added`.
No Breaking.

## 5. Not in this wave

Starter wiring (Scout constructs its toolbox in plain code — the
`nessy.mcp.clients.*` properties arrive with the queued starter-tidy
generation, which may then slim Scout's wiring section); MS Learn or
other servers; any second example adopting MCP.
