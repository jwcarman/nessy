# The Documentation Site

**Date:** 2026-08-15
**Status:** APPROVED in conversation (owner: "yes, spec and run it")
**Precedent:** mocapi's docs stack, mirrored deliberately (same tooling, personal-OSS theming).

## 1. Goal and stack

A comprehensive documentation suite rendered to GitHub Pages, mocapi-style:

- **Material for MkDocs**, pinned `mkdocs-material==9.7.7` (mocapi's exact pin).
- `mkdocs.yml` at the repo root; `docs_dir: docs`; `docs/superpowers/` excluded from the
  published site (process artifacts, exactly like mocapi excludes `plans/`/`superpowers/`).
- `.github/workflows/docs.yml` copied from mocapi's shape: path-filtered on `docs/**`,
  `mkdocs.yml`, and itself; checkout → setup-python 3.13 → pip install the pinned
  mkdocs-material → `mkdocs build` → upload-pages-artifact → deploy-pages. The repo's Pages
  setting must be flipped to "GitHub Actions" by the owner (one dashboard click, out of scope).
- `site_name: Nessy`; `site_url: https://jwcarman.github.io/nessy/`;
  `repo_url: https://github.com/jwcarman/nessy`; `edit_uri: edit/main/docs/`.
- **Stock Material theming** — light/dark palette toggle, `navigation.tabs`, `navigation.top`,
  `navigation.footer`, `search.suggest`, `search.highlight`, `content.code.copy` — but NO
  Callibrity brand assets: nessy is org.jwcarman personal OSS.
- Markdown extensions: mocapi's set (admonition, attr_list, md_in_html, toc permalink,
  pymdownx.details/highlight/inlinehilite/superfences).
- Local verification gate for every content task: `mkdocs build --strict` (fails on broken
  nav entries and bad internal links).

## 2. Content architecture

Organized around the teaching arc, not the module list. Home is `docs/index.md` (a distilled
landing page — what nessy is, the twenty-line agent, where to go next); the repo README stays
independent.

**Concepts** (`docs/concepts/`) — the vocabulary, one page each:
- `durable-loop.md` — harness/agent/conversation; at-least-once; the fold; why replay safety
  shapes every API.
- `tools-and-grants.md` — Tool, ToolGrant, UsagePolicy; the grant principle (incl. MCP import
  security framing).
- `parks-and-callbacks.md` — durable waits: park tokens, agent identity stamps, the seven
  doors, WrongAgentException; the crown-jewel story currently locked in specs.
- `memory-and-the-pipeline.md` — Memory as the content jurisdiction; hydrate → stages;
  ContextHydrator/ContextTransformer; summarizing; keepRecent; fail-closed + optional().
- `planning.md` — the plan facility: update_plan (wholesale replace, replay idempotency), the
  injected checklist, empty-clears, the console rendering. THE gap this generation exists to
  close.
- `storage.md` — the five doors (ConversationStore, Transcript, Parks, SummaryStore,
  PlanStore); in-memory defaults everywhere; JDBC across five dialects; the TCK.

**Guides** (`docs/guides/`) — task-shaped:
- `getting-started.md` — an agent in twenty lines, in-memory everything.
- `providers.md` — Anthropic/OpenAI, EnvModelProviders, switching by env var.
- `durable-persistence.md` — JdbcPersistence, dialect detection, schema bootstrap, what
  survives a restart.
- `console-apps.md` — ConsoleRepl/renderer/approver/plan checklist; SGR-only stance.
- `mcp-clients.md` — McpToolbox, granting imported tools, the DeepWiki example.
- `planning-your-agents-work.md` — wiring PlanStore + tool + transformer + .plan(); prompt
  guidance; what good plan behavior looks like (drawn from the live Scout transcripts).
- `summarizing-memory.md` — the fold, watermarks, tail threshold, SummaryStore.
- `triggers.md` — who initiates a turn: terminal, web, clock, webhook, queue; tell-while-
  parked; the inbox.
- `observability.md` — what exists today (observation registry, turn observers, listeners),
  honestly noting current limits (no per-stage spans yet).
- `spring-boot.md` — the starter, autoconfiguration, properties.

**Examples** (`docs/examples/index.md`) — one tour page: table of the examples
(hello, chat-cli, scout, chat-web, night-watchman, dispatcher, order-desk), one paragraph
each on what it teaches, links into the repo.

**Reference** (`docs/reference/`):
- `configuration.md` — starter properties, gathered from NessyProperties + module READMEs.
- `tck.md` — certifying a custom backend (public-@Test lesson included).
- Changelog and javadoc: nav links out to the repo CHANGELOG.md and (until published
  javadoc exists) the source tree.

## 3. Binding rules for every page

- **Truth over completeness:** every code snippet is checked against current main — exact
  type names, method names, package names. No invented API, no stale names (the README
  truth-pass discipline, site-wide). Where behavior has sharp edges (empty-plan-clears,
  at-least-once re-told events), the docs say so plainly.
- **Voice:** the repo's existing prose voice (module READMEs) — direct, concrete, no
  marketing. Admonitions for the sharp edges.
- **Sources:** module READMEs, the root README, CHANGELOG, and the specs' prose are raw
  material to curate and rewrite — never link the site to docs/superpowers (excluded).
- Each page ends with "Where next" links (2-3, within the site).
- Root README gains a docs-site badge/link once the site exists.

## 4. Out of scope

- Published javadoc (separate concern; nav links to source until then).
- Versioned docs (mike) — single-version site until a release wants more.
- The Pages dashboard toggle (owner's click).
- Notebook documentation — lands with the Notebook generation, which will add its concepts
  section then.
