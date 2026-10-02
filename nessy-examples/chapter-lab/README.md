# Chapter lab

A command-line lab for comparing ways of cutting and summarising a long conversation.

It replays one long recorded conversation (the [LoCoMo](https://github.com/snap-research/locomo)
data) through the real engine, turn by turn. The engine cuts the conversation into chapters under
the policy you choose, and a real model writes each chapter's summary with the summariser you
choose. Then the lab asks questions whose answers are known, from the context the engine built (the
summaries, then the turns after the last one) and nothing else, has a model grade every answer, and
prints how many were right and how big that context was.

Not a Spring application: it builds the engine and a provider by hand, in one process, with
everything in memory.

## Running it

From the repository root, with the key for the provider you choose in the environment:

```
export ANTHROPIC_API_KEY=...        # or OPENAI_API_KEY, XAI_API_KEY, GEMINI_API_KEY
./mvnw -q -pl :nessy-example-chapter-lab -am compile exec:java \
  -Dexec.args="--data /path/to/locomo10.json --conversation 0 --questions 40 \
               --provider anthropic --model claude-sonnet-5-5 \
               --policy every:20 --summarizer prose"
```

With a model served locally (LM Studio's OpenAI-compatible server, no key needed):

```
./mvnw -q -pl :nessy-example-chapter-lab -am compile exec:java -Dexec.args="--data /path/to/locomo10.json --conversation 0 --questions 5 --provider lmstudio --model qwen/qwen3.6-35b-a3b --policy every:20 --summarizer prose"
```

A local model is slow, and one that reasons before it answers is slower: use a small `--questions`
to try it.

`-am compile` builds the engine and the providers it depends on first (the other modules skip
`exec:java`). `exec:java` runs in Maven's JVM, so the result files below land in the directory Maven was started from: the repository
root. The key is read from the environment by the provider and is never printed.

## Options

| Option | What it does |
|---|---|
| `--data FILE` | The LoCoMo file (`locomo10.json`). Required. |
| `--conversation N` | Which conversation in the file to replay, counting from 0. Default 0. |
| `--questions N` | How many questions to ask. Only questions of categories 1 to 4 that cite evidence are eligible; a fixed pseudo-random sample is taken with seed `7 + conversation`, so the same options always ask the same questions. Java's generator does not reproduce the Python probe's choice from that seed. Default 40. |
| `--provider NAME` | `anthropic`, `openai`, `xai` or `gemini`. Required. Keys come from `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `XAI_API_KEY`, `GEMINI_API_KEY` (or `GOOGLE_API_KEY`). OpenAI and xAI use the chat completions wire. `lmstudio` is an OpenAI-compatible chat server at `http://localhost:1234/v1` and needs no key; models are named as the server names them. Bedrock is not offered. |
| `--base-url URL` | `lmstudio` only: the server's address, in place of `http://localhost:1234/v1`. |
| `--reasoning-effort V` | Sets the adapter's `openai.reasoning.effort` property for every call (summaries, cuts, answers, grading). Providers `openai`, `xai` and `lmstudio` only. Accepted values: `none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`. Whether a server honours it is up to the server. Unset, nothing is sent. |
| `--thinking V` | Sets the adapter's `anthropic.thinking.type` property for every call. Provider `anthropic` only. Accepted values: `adaptive`, `disabled`, `between_tools`. Unset, no thinking field is sent and the model's own default applies; Sonnet 5.5 then thinks before every summary, answer and grade, and `between_tools` is what turns that off. A run with it set appends to a file of its own, named with `-thinking-<value>`. |
| `--model NAME` | The model that answers the questions and grades the answers. Required. |
| `--summary-model NAME` | The model that writes summaries (and, for `hindsight`, names the cuts). Defaults to `--model`. Always the same provider. |
| `--policy P` | Where chapters end. See below. Default `every:20`. |
| `--summarizer S` | `prose` or `index`. See below. Default `prose`. |
| `--max-chapter-length N` | The engine's ceiling on a chapter's length in turns. Default 200, so the policy alone decides. |
| `--max-tail N` | The engine's ceiling on the turns kept verbatim after the last summary. Default 400. Must exceed `--max-chapter-length` unless the policy is `none`. |

### Policies

- `every:N`: a chapter every N turns. N may not exceed `--max-chapter-length`, or the engine's own
  cut would fire first. Whatever is left over when the conversation stops stays open as
  the tail.
- `session`: a chapter for each recorded session. The lab knows which turn ends each session
  because it drove the turns.
- `hindsight`: once forty turns are open, a model reads them and names the natural breaks; the
  turns after the last break stay open. Nothing is closed until forty turns are open, and what is
  left when the conversation stops stays open as the tail.
- `none`: no chapters. The whole conversation is sent, which for a conversation longer than
  `--max-tail` turns is more than the engine itself would send.

### Summarisers

- `prose`: the engine's own prompt, a record that stands alone.
- `index`: a short entry, at most a hundred words: the dates covered, then who and what the chapter is
  about as short phrases. It names things and does not explain them.

## What it prints

One line of settings, one line per chapter as its summary is written, then:

- the number of chapters and the words in all their summaries,
- the words in the context every question is asked with: the words of the summaries plus the words
  the open turns say (their inputs and replies), counted the same way for both,
- how many questions were answered correctly of those asked, and the same by category (1 multi-hop,
  2 dates, 3 inference, 4 single fact),
- how many questions got no answer from the model (recorded as `(no answer)` and graded wrong
  without asking the grader) and how many grading verdicts could not be read (a reply that does not
  begin with yes or no, or a grading call that failed; graded wrong),
- the input and output tokens the provider reported, once for writing summaries and cuts and once for
  answering and grading. Calls that failed report no usage and are not counted, so these figures are
  a lower bound. The input figure is all input, cached or not.
- under each, how much of that input was read from the provider's cache, how much was written to it,
  and how much was charged at the ordinary rate; or `not reported` when the provider gave no cache
  counts.

A refused or failed answer or grade costs one question, not the run. A failure while replaying (a
summary that cannot be written after four attempts, a policy that throws) stops the run with the
reason, since the context would no longer be what was asked for.

A verdict that cannot be read is printed with the grader's reply as it happens, or with the reason
the grading call failed.

Every question, the answer, the correct answer, the verdict and the grader's reply (`grader_reply`)
are appended as JSON lines to
`chapter-lab-<provider>-<model>-<policy>-<summarizer>.jsonl` in the working directory. The file is
appended to, not replaced, so remove it between runs you want to keep apart.

## How it works

1. **Replay.** A direct harness over the in-memory backend plays each recorded exchange as a turn:
   the first speaker's message is the input, prefixed with the session's date, and a scripted
   provider answers with the recorded reply, instantly. A message nobody answered is a turn whose
   reply is `(no reply)`.
2. **Cut and summarise.** The policy and a `ProseSummarizer` built on the real provider are set on
   the harness. The engine's chapter keeper works off the agent's thread, so after each turn the lab
   waits until the keeper has finished with it (the policy asked, its chapters stored, every closed
   chapter summarised), with a ten-minute deadline. A summary that fails four times stops the run
   with the reason.
3. **Ask.** For each question the lab builds the context straight from the store (the summaries, the
   turns after the last one, and the question as the active turn under a system prompt that asks for
   the answer in a few words, or `unknown`) and calls the real provider. Nothing is appended to the
   agent, so every question sees the same context.
4. **Grade.** The same provider is asked yes or no: the same fact is yes however it is worded or
   ordered, a date meaning the same day is yes, a list must be complete, more specific and consistent
   is yes, vaguer is no, and `unknown` is no.

The prompts are the ones the Python probe used, in `LabPrompts`.

## Caching

With `--provider anthropic` the lab turns the provider's prompt cache on, with the five-minute
lifetime. Every question is asked with the same context ahead of it, so the first question writes
that context to the cache and the rest read it back. The other providers are left as they are.

## Cost

A run spends tokens: a summary per chapter, then two calls per question, every one carrying the whole
context. `--policy none` sends the entire conversation with each question; try a small `--questions`
first.
