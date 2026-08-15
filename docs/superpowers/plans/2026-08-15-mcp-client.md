# The MCP Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `nessy-tool-mcp` — MCP server tools as nessy `Tool`s: `McpToolbox` over the official MCP Java SDK, per-tool grants, zero kernel change, tested against a real in-process MCP server.

**Architecture:** Two tasks — the module (toolbox + tool + the in-process-server test suite, including the end-to-end registry proof), then paperwork. The kernel is untouched by design; any discovered need to change core is a STOP-and-report finding, not a quiet edit.

**Tech Stack:** MCP Java SDK (`io.modelcontextprotocol.sdk`, version pinned in the parent — Task 1 verifies the real artifact ids), Jackson, nessy-core, nessy-testing (ScriptedModelProvider for the end-to-end proof).

**Spec:** `docs/superpowers/specs/2026-08-15-mcp-client-design.md` — binding.

## Global Constraints

- TDD with RED/GREEN evidence; offline `./mvnw -q clean verify` green after EVERY task — this module's tests are DEFAULT-BUILD (in-process server, no Docker/network/key); `javadoc:javadoc` 0 errors on the module.
- Before every commit: `./mvnw license:format -Plicense && ./mvnw spotless:apply`, re-stage. No IDE metadata. No suppressions, no star imports, no mocking libraries (the in-process MCP server is the SDK's REAL server, not a fake), prose snake_case test names, S5778/S5841, Awaitility not sleep.
- Commit trailer: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- ZERO core/SPI changes. If the SDK's shapes force one, STOP and report.

---

### Task 1: The module — `nessy-tool-mcp`

**Files:** new module (root pom aggregator line; parent pom property pinning the MCP SDK version — read the SDK's released coordinates/artifact layout first, do not guess; `nessy-bom` managed entry; module pom in the family shape: nessy-core + the SDK core artifact + Jackson via the platform, test deps nessy-core test-jar? no — nessy-testing + the SDK's server artifact if separate), `McpToolbox.java` (public front door per spec §2: connect/initialize/tools/tool/AutoCloseable/fail-noisy-lookup/closed-behavior), `McpTool.java` (package-private, spec §3 exactly: spec() override with server schema as ObjectNode, inputType JsonNode.class, describe = name + compact JSON, execute → callTool with the result mapping and honest non-text degradation, Awaited.ready always, progress forwarding IF the sync surface allows — else documented deferral in the report), package-info in the family voice.

**Interfaces:**
- Consumes: `Tool<T>`, `ToolSpec`, `ToolResult` (read its actual grammar first), `ToolContext`, `Awaited` from nessy-core; the SDK's client + server APIs.
- Produces: `McpToolbox.connect(...)` (freeze the signature against the SDK's client-construction idiom), `toolbox.tools()`, `toolbox.tool(String)`.

**Tests (all default-build, in-process SDK server):** discovery mirror + fail-noisy lookup; schema byte-fidelity through spec(); argument round-trip (server records what it received); text mapping; isError mapping; non-text degradation; closed-toolbox loudness; THE END-TO-END PROOF — a real `Nessy.harness(ScriptedModelProvider...)` agent granted an `McpTool`, the scripted model calls it, the MCP server answers, the turn completes (the zero-kernel claim proven through the real executor); progress forwarding test if implemented.

- [ ] RED: the suite against stubs; GREEN: the module. `./mvnw -q clean verify` green; `./mvnw -q -pl nessy-tool-mcp javadoc:javadoc` 0 errors.
- [ ] Commit: `feat: the world's toolboxes open — MCP tools become nessy tools`

### Task 2: Paperwork

`nessy-tool-mcp/README.md` (the grant-principle-as-import-security headline; the connect/grant snippet; transports arrive from the SDK; the v1 boundaries: tools-only, text-first, elicitation/sampling banked); root README: Install section artifact row + one sentence where tools are introduced (imported toolboxes, granted like everything else); CHANGELOG `### Added` (module, zero-kernel claim proven end to end, per-tool grants); no Breaking entries. Full offline sweep + the container sweep unchanged-but-run (`-Dnessy.excludedGroups=live`) end to end.

- [ ] Commit: `docs: the import papers — granted tool by tool, like everything else`

---

## Self-Review Notes (already applied)

- Task 1 owns freezing `connect(...)`'s signature against the SDK's real idiom and reporting the choice — the spec's snippet is intent, not law.
- The progress-forwarding deferral path is sanctioned and must be a REPORTED decision either way.
- The end-to-end registry proof is the generation's thesis (spec §5) — it must exercise the real `ToolInvoker` path via an agent turn, not call `execute` directly.
- No starter work anywhere in this plan (spec §6) — a reviewer finding autoconfigure edits should treat them as scope creep.
