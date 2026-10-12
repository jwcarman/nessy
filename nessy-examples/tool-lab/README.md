# Tool lab

A command-line lab for measuring what it costs a model to choose and call tools, and how well it
does, when it is shown less of the tool catalog.

An agent is normally offered every tool's full JSON schema on every call. The lab runs a set of tasks
with known correct tool calls against a real provider under three variants of what the model is shown,
grades every run without a model, and prints accuracy next to Usage.

Not a Spring application: it builds the requests and the provider by hand, in one process, and the
engine is not started. Every tool is synthetic and answers from a stub.

## Running it

From the repository root, with the key for the provider you choose in the environment:

```
export ANTHROPIC_API_KEY=...        # or OPENAI_API_KEY, XAI_API_KEY, GEMINI_API_KEY
./mvnw -q -pl :nessy-example-tool-lab -am compile exec:java \
  -Dexec.args="--provider anthropic --model claude-sonnet-5-5 --variants v0,v1,v2 \
               --size 500 --tasks 10 --runs 1 --out results"
```

With a model served locally (LM Studio's OpenAI-compatible server, no key needed):

```
./mvnw -q -pl :nessy-example-tool-lab -am compile exec:java -Dexec.args="--provider lmstudio --model qwen/qwen3-coder-30b --size 100 --tasks 10"
```

`-am compile` builds the engine and the providers it depends on first (the other modules skip
`exec:java`). `exec:java` runs in Maven's JVM, so a relative `--out` is relative to the directory
Maven was started from: the repository root. The key is read from the environment by the provider and
is never printed.

## Options

| Option | What it does |
|---|---|
| `--provider NAME` | `anthropic`, `openai`, `xai`, `gemini` or `lmstudio`. Required. Keys come from `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`, `GEMINI_API_KEY` (or `GOOGLE_API_KEY`). OpenAI and xAI use the chat completions wire. `lmstudio` is an OpenAI-compatible chat server at `http://localhost:1234/v1` and needs no key. Bedrock is not offered. |
| `--model NAME` | The model to call. Required. |
| `--variants LIST` | Comma-separated: `v0`, `v1`, `v2`. Default all three. |
| `--size N` | How many tools the catalog holds: 20, 100, 500 and 2000 are the sizes the lab is built for. At least 20, because the 20 hand-written tools are always included. Default 100. |
| `--tasks N` | How many tasks to run, taken from the front of `tasks.json`. The first ten are a balanced mix: four single, three multi, three ambiguous. Default 10. |
| `--runs N` | How many times each task is run under each variant. Default 1. |
| `--out DIR` | Where the result file goes. Default the working directory. |
| `--max-tokens N` | The output limit for every call. Default 2048. |
| `--base-url URL` | `lmstudio` only: the server's address, in place of `http://localhost:1234/v1`. |
| `--thinking V` | Sets the adapter's `anthropic.thinking.type` property for every call. Provider `anthropic` only. Accepted values: `adaptive`, `disabled`, `between_tools`. Unset, no thinking field is sent and the model's own default applies. |
| `--probe-unoffered` | Runs the probe below instead of the tasks. |

With `--provider anthropic` the provider's prompt cache is on, with the five-minute lifetime. The
other providers are left as they are.

## The catalog

`Catalog.of(size)` returns exactly `size` tools. The 20 hand-written tools come first and are in every
catalog: the tools the tasks need, and the near-duplicates that make a choice hard
(`jira_create_issue` and `linear_create_issue`, `salesforce_search_accounts` and
`hubspot_search_companies`, `jira_get_issue` and `linear_get_issue`). After them come generated tools,
in a fixed order: systems times kinds of thing times verbs, taken round-robin across the systems, so
a catalog of 100 touches many systems rather than every verb of one. The same size gives the same
catalog on every run, and a smaller catalog is the front of a larger one.

Every tool has a name valid on every vendor (`^[a-zA-Z0-9_-]{1,64}$`), a one-line description, an
object schema of one to four described, typed properties, and a stub that returns a deterministic JSON
string. The stubs of the hand-written tools chain: `salesforce_search_accounts` with the name Acme
returns `001ACME`, which `salesforce_list_opportunities` then needs. A generated tool returns
something small, like `{"ok":true,"system":"okta","items":[]}`.

## The tasks

`src/main/resources/tasks.json` holds 40 tasks.

- `single`: one call answers it ("Which AWS regions do we run in?").
- `multi`: two or three chained calls, each later one needing a value from an earlier result.
- `ambiguous`: the request fits two systems equally ("Create a task to rotate the TLS cert."). The right
  behaviour is to make no call that creates, updates or deletes anything, and to ask which system.

`expected` lists only the argument keys that must match. Strings are compared as text after the JSON is
parsed; numbers are compared as numbers. Extra arguments are fine.

## The variants

Each variant decides the system prompt and the tools offered on each call.

| Variant | System prompt | Tools offered |
|---|---|---|
| `v0` | The task rules only. | Every catalog tool, with its full schema, on every call. |
| `v1` | The task rules, then every catalog tool as one `name — description` line. | `load_tools`, plus the derived set. |
| `v2` | The task rules, saying tools are found by search. | `search_tools` and `load_tools`, plus the derived set. |

The lab runs `search_tools` and `load_tools` itself; they are not catalog tools.

- `search_tools(query, limit?)` ranks the catalog with BM25 over each tool's name (split on `_`) and
  description, lowercased, split on anything that is not a letter or digit, with no stemming. It
  returns `[{"name":...,"description":...}]`. The limit defaults to 10 and is at most 25.
- `load_tools(names)` answers, for each known name, `{"name","description","loaded":true}` and for an
  unknown name `{"name","error":"no such tool"}`.

### The derived rule

The tools offered on a call are a pure function of the context being sent (`Derived.offers`). Nothing
is remembered between calls. For `v1` and `v2` they are:

1. the variant's meta tools, then
2. every catalog tool named by any tool call anywhere in the context, then
3. every catalog tool named in the `names` argument of any `load_tools` call in the context whose
   outcome was `Succeeded`.

A tool is listed once, where it first appears. So `load_tools` takes effect on the next call, and a
tool the model has called stays offered for as long as the history shows the call. The history never
names a tool the request does not offer, which is what some vendors insist on (see the probe).

A call to a catalog tool that is not in the offered set is recorded as `Failed` with `<name> is not
loaded; load it with load_tools first`, and counted as `unloaded_call`. The failed call is itself in the
history, so by the rule above the tool is offered from the next call on. A call to a name that is not in
the catalog at all fails with `no such tool` and counts as `unknown_call`. In `v0` every tool is
offered, so neither can happen for a catalog tool.

## The loop

For each task, variant and run, one turn whose input is the task's prompt, and up to twelve rounds:

1. Build the request from the system prompt, the context (the turn so far) and the tools the rule
   derives from that context. A call that throws is tried again, up to four attempts in all with a
   longer pause each time; a call that comes back with a refusal or a fault is not tried again.
2. If the model asked for tools, run them (a meta tool, a stub, or a failure as above), add an exchange
   to the turn and go round again. If it answered, the run is over.

A refusal, a fault, a reply cut off at the output limit, or twelve rounds of tool calls also end a run,
and are recorded as such. A call that threw on every attempt ends that run as a fault and the lab goes
on to the next run.

## Grading

No model grades anything.

- `single` and `multi`: success when every expected call appears among the catalog calls that ran, in
  order (as a subsequence) if the task is ordered, with the listed arguments, and the run ended with an
  answer. A call that failed does not count as having been made.
- `ambiguous`: success when no call to a tool that changes something was attempted, and the run ended
  with an answer. Reads are allowed.

## What it prints

A line per run as it finishes, then a table with a row per variant:

| Column | Meaning |
|---|---|
| `success` | Runs that succeeded of runs made, and the percentage. |
| `wrong` | Mean catalog calls per run that matched no expected call; for an ambiguous task, every call to a tool that changes something. |
| `meta` | Mean calls per run to `search_tools` or `load_tools`. |
| `rounds` | Mean calls to the model per run. |
| `offered` | Mean over runs of the most tools offered on any one call. |
| `wall ms` | Mean wall time per run. |
| `unloaded`, `unknown` | Totals of `unloaded_call` and `unknown_call`. |

Then, per variant, the Usage summed over every call of every run, and the same divided by the number of
runs that succeeded (what one correct run costs, with the failed runs' spending included). Usage is
always five counts, never a single token number: input (all input, cached or not), output, cache read,
cache write and reasoning, under the model name. A count the provider did not report is printed as
`unreported`, not as zero. Where some calls reported a count and others did not, the sum covers the
ones that did.

### The result file

One JSON line per run is appended to `<out>/tool-lab-<yyyyMMdd-HHmmss>.jsonl`:

`provider`, `model`, `variant`, `size`, `task_id`, `kind`, `run`, `success`, `outcome` (`answer`,
`refusal`, `fault`, `truncated` or `exhausted`), `catalog_calls`, `wrong_calls`, `meta_calls`,
`unloaded_call`, `unknown_call`, `rounds`, `input_tokens`, `output_tokens`, `cache_read_tokens`,
`cache_write_tokens`, `reasoning_tokens` (each `null` when unreported), `usage_models`, `wall_millis`,
`max_offered`, `answer`, `note` (the reason, for a fault or a refusal) and `calls` (every call the model
made, one line each, cut to 200 characters).

## The probe

`--probe-unoffered` sends two requests and prints whether the vendor accepted each. Both have the same
history: the model called `jira_get_issue` and the call succeeded. The first offers only
`slack_post_message`; the second offers no tools. Neither is tried again. A vendor that rejects either
is one that enforces what the derived rule protects against.

## Tests

The tests use a scripted provider that returns queued results and records the requests it was sent. They
need no network and no key.
