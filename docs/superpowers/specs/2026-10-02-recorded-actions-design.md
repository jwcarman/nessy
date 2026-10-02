# Recorded actions: what a tool call did is written down, in words, when it happens

**Status: APPROVED FOR BUILDING, NOT BUILT.** Written on 2026-10-02 from rulings James made in
conversation that day; he ruled on each point in §2 as it was drafted. Nothing here is built. Written on Opus because the session was on Opus; the model policy
puts specifications on Fable, so this is a draft for James to revise, not a finished record.

§2 says what James ruled and which names are provisional. A provisional name is what a build would
use so the design can be tried; it has not had his yes.

Date: 2026-10-02. Amends the stratified-context design (`2026-10-01-stratified-context-design.md`
§3, the summariser) and the approval lifecycle (the action shown to an approver).

---

## 1. What this changes, in one page

A tool call is written down twice in words, each time once and for good:

```text
the model asks for a tool
  -> the binding's action stringifier says what the call would do   (stored on the call, in the event)
the tool returns
  -> the binding's result stringifier says what came back           (stored on the outcome, in the event)
```

Both are short. Each stringifier's text is cut to a limit: by default an action keeps its start, and
a result keeps both ends and drops the middle.

Readers of those two lines:

- an approver is shown the first (already true; now it comes from the event)
- the chapter summariser is shown both, in place of the call and its result (new)

Two things do not change. The turn being answered is sent whole: every call with its arguments
and every result in full. And nothing stored is removed: the model's own request and each tool's
result stay in the story as they are today.

## 2. Rulings and provisional names

Ruled by James on 2026-10-02:

- The turn in progress keeps every call and result in its entirety. The description is never a
  substitute for them there.
- The description of a tool call is stored on the tool call request when the request arrives, as
  state, in the event.
- A turn hands its calls' descriptions over on `Exchange`, per call.
- On `Exchange` the two maps are `actions` and `results`, read with `actionOf(CallId)` and
  `resultOf(CallId)`.
- The stored call's description is `action`; the stored success's line is `rendered`;
  `Stringifier`'s method is `stringify`; a finished call reads
  `assistant did: <action> -- succeeded: <result>` in the summariser's transcript.
- `ActionRenderer` goes. A `Stringifier<I>` does its job.
- A binding's stringifier is always wrapped in a dropper, so nothing can overflow. A stringifier
  that is already dropping at or below that limit is left as it is: asked to drop to a limit it
  is already within, it returns itself.
- The hard cap on a line is 1,000 characters. The default caps at 255: an action keeps its start,
  a result drops its middle.
- What the summariser is fed is a projection: the description of each call and how it ended, not
  the call and its result.
- These lines are bounded, generously. A string is brought down to a limit by a **`Truncator`**,
  which takes the limit and the original string.
- The output of a tool call has a stringifier too, with a default, bounded at about 255 characters.
- The truncation belongs with the stringifier, for both the action and the output. A stringifier has
  **`dropTail(int limit)`**, **`dropHead(int limit)`** and **`dropMiddle(int limit)`**, each
  returning the same kind of stringifier with that cut applied to what it writes, as a wrapper.
- By default an action keeps its start (`dropTail`) and a result drops its middle (`dropMiddle`).
  All of it is configurable.
- The default stringifier is `String.valueOf`. A tool's input record may override `toString()`
  to say what a call does, or take the record's own, which is good enough. A JSON flavour is
  supplied for whoever wants it; it is not the default.
- Turning a thing into a string is a common need, so there is one concept for it,
  **`Stringifier<T>`**, re-used wherever a textual thing is needed. The "droppers" hang off it and
  return the same type with the truncation applied.
- A reply cut off at the output limit is reported and not continued; users tune `maxTokens` or
  chapter size.
- Rolling back, old builds and events stored before this change are not a concern. Nothing here
  reads an event that has no description.

Provisional, needing his yes:

| Name | Where | What it is |
|---|---|---|
| `truncated(Truncator, int limit)` | on `Stringifier` | the wrapper the three `drop` methods are made of, for a truncator of the application's own |
| `ToolConfig.LINE_CAP` = 1000, `ToolConfig.DEFAULT_LINE_LIMIT` = 255 | `nessy-api` | the names of the two numbers James ruled |
| `ToolConfig.result(...)` | `nessy-api` | sets the result stringifier on a binding |
| `ToolConfig.resultText()` | `nessy-api` | the default result stringifier, the result's text |
| `ToolBinding.describe(String arguments)` | engine, not API | writes the action, or the fallback |

## 3. Why

Measured on 2026-10-02, running `chat-web` on Postgres against `claude-sonnet-4-5` with chapters
of four turns:

- The first chapter, four turns of plain conversation, was summarised in 7.5 seconds.
- The second chapter held two turns in which the agent called tools. The summariser's call came
  back empty: `stop_reason=end_turn`, no content, 8 output tokens. The summariser refused it, the
  chapter stayed unsummarised, and every later chapter waits behind it.

A raw-HTTP probe replayed that exact chapter, rebuilt from the run's database, five times each
way (`~/IdeaProjects/nessy-context-probes/anthropic_summary_tools_probe.py`):

| What was sent | Empty replies | Input tokens |
|---|---|---|
| The turns as messages with their tool blocks, no tools declared (what the summariser sends) | 5 of 5 | 1,161 |
| The same, with the two tools declared | 0 of 5 | 1,776 |
| The chapter as text in one message | 0 of 5 | 980 |
| Control: only the turns that called no tool | 0 of 5 | |

So a summariser that replays tool calls as structured blocks cannot summarise a chapter with a
tool call in it on that model. Any agent that uses tools reaches this at its first such chapter.
The lab never saw it because its conversation has no tool calls, and the unit tests script the
model.

Sending text fixes it. The question is what the text says about a call. The raw arguments are
what the model wrote, for a machine, and a raw result can be any size. The engine already has,
for approvals, a sentence written by the application for a reader. Writing that down for every
call, and a matching short line for what came back, gives the summariser something bounded and
readable, and serves the readers that are coming: an old result hidden from the live context
needs something to stand in its place, and a memory of what an agent did is made of such lines.

## 4. Truncator

In `nessy-api`, `org.jwcarman.nessy.api`, beside `Stringifier`:

```java
@FunctionalInterface
public interface Truncator {

  /** {@code text}, no longer than {@code limit} characters. */
  String truncate(String text, int limit);

  /** Keeps the start and drops the rest. */
  static Truncator dropTail() { ... }

  /** Keeps the end and drops what came before it. */
  static Truncator dropHead() { ... }

  /** Keeps the start and the end and drops what lies between. */
  static Truncator dropMiddle() { ... }
}
```

What the three supplied ones promise:

- Text already within the limit comes back as it was given.
- What comes back is never longer than the limit, the marker included.
- The marker is `...`: at the end for `dropTail`, at the start for `dropHead`, in the middle for
  `dropMiddle`.
- `dropMiddle` gives the start and the end an equal share of what the limit leaves after the
  marker; an odd character goes to the start.
- They cut between whole characters. A character made of two code units is kept or dropped whole.
- A limit too small to hold the marker and one character on each side it keeps gives a plain cut
  to the limit, with no marker.

A truncator of the application's own may do anything that honours the limit: cut on words, on
lines, on a tokeniser's count.

Which end matters depends on the output. A header and an error at the bottom want both ends. The
last lines of a log want the end. The first hit of a search wants the start. No published
comparison says which keeps a task on course best; the three are convention, and the choice is
the binding's.

## 5. Stringifier, used twice on a binding

Saying a thing in a line of text is a common need: what a call would do, what it returned, and
whatever comes next. So there is one type for it, `Stringifier<T>`, and the bounding methods live
on that type and are available wherever one is taken.

In `nessy-api`, `org.jwcarman.nessy.api`:

```java
/** Says a thing in a line of text. */
@FunctionalInterface
public interface Stringifier<T> {

  String stringify(T value);

  /** This stringifier, keeping the start of what it writes. */
  default Stringifier<T> dropTail(int limit) { return truncated(Truncator.dropTail(), limit); }

  /** This stringifier, keeping the end of what it writes. */
  default Stringifier<T> dropHead(int limit) { return truncated(Truncator.dropHead(), limit); }

  /** This stringifier, keeping both ends of what it writes. */
  default Stringifier<T> dropMiddle(int limit) { return truncated(Truncator.dropMiddle(), limit); }

  /** This stringifier, cut to {@code limit} by {@code truncator}. */
  default Stringifier<T> truncated(Truncator truncator, int limit) { ... }

  /** The default: {@code String.valueOf(value)}, the value's own {@code toString()}, null-safe. */
  static <T> Stringifier<T> byToString() { ... }

  /** The value as JSON, written by {@code mapper}. */
  static <T> Stringifier<T> json(JsonMapper mapper) { ... }
}
```

`byToString()` is `String::valueOf` and nothing more, and it is the default everywhere. That
makes the simplest way to give a tool a good action line the input type itself: a record
`DaysUntilRequest` either overrides `toString()` to say what the call does, or takes the record's
own (`DaysUntilRequest[date=2025-12-21]`), which reads well enough.

An overridden `toString()` is written by whoever wrote the input type. For a tool the application
wrote, that is the application. For a tool it did not write and gates behind approval, the
sentence a person consents to should still come from the binding, with `config.action(...)`.

`json(mapper)` is for a value whose
`toString()` says nothing useful, and for anyone who wants a call written the way the model wrote
it: `{"date":"2025-12-21"}` where `byToString()` gives `DaysUntil[date=2025-12-21]`. It takes the
mapper because the API holds none of its own; `nessy-api` already depends on Jackson and already
takes a `JsonMapper` in `OutputReader`. A value the mapper cannot write makes `stringify` throw,
which the engine treats like any other stringifier that throws (§6).

Each of the four returns a wrapper: a `Stringifier<T>` that runs the one it wraps and then:

1. makes the text one line, turning every run of whitespace, line breaks included, into one space
   and trimming the ends;
2. cuts it with the truncator to the limit.

A limit below 1 is refused when the wrapper is made. A truncator that returns more than the limit
has a bug: the wrapper cuts what it returned to the limit, keeping the start, and logs a WARN.

**A wrapper asked to drop to a limit it is already within returns itself.** `dropTail`,
`dropHead`, `dropMiddle` and `truncated` on a wrapper whose own limit is at or below the limit
asked for do nothing: what it writes already fits, and how it was cut is left as its author chose.
Asked for a smaller limit, it wraps again, and the text is cut a second time to the smaller one.

**`ActionRenderer<I>` goes.** It is a `Stringifier<I>` and nothing more; what its documentation
says about who writes the sentence and who reads it moves to `ToolConfig.action`. `InputRenderer`
is a different thing and is untouched: it turns an input into blocks for the model, not into a
string.

On `ToolConfig<I>`:

```java
/** The most an action line or a result line may be, whatever a binding asks for. */
int LINE_CAP = 1000;

/** What a line is cut to when the binding names no stringifier of its own. */
int DEFAULT_LINE_LIMIT = 255;

/** What a call of this tool would do, in words a person can consent to. */
ToolConfig<I> action(Stringifier<I> action);

/** What a call of this tool returned, in a line a reader can take in. */
ToolConfig<I> result(Stringifier<ToolResult.Success> result);
```

so a binding reads:

```java
config.action(order -> "refund " + order.id() + " in full")                  // capped at 1,000
      .result(ToolConfig.resultText().dropHead(400));                         // a log: the last 400

config.action(Stringifier.<Purge>byToString().dropMiddle(200));             // the binding's own cut
```

**Nothing is stored unbounded.** When a binding is built, each line's stringifier is settled like
this:

| The binding | Action line | Result line |
|---|---|---|
| names no stringifier | `Stringifier.byToString().dropTail(255)` | `ToolConfig.resultText().dropMiddle(255)` |
| names one | that one, wrapped in `dropTail(1000)` | that one, wrapped in `dropMiddle(1000)` |

So 255 is what a tool gets when its author says nothing, and 1,000 is the most any tool can have.
A binding that names its own stringifier may cut shorter than 1,000 and may cut a different way:
its own dropper at or below the cap is left alone, by the rule above. One that asks for more than
the cap is cut to it.

An action keeps its start because that is where a sentence says what is being done and to what,
and because a person approves against it: a marker at the end says something is missing, where a
hole in the middle of a sentence someone is consenting to can hide what matters. A result keeps
both ends because output tends to open with what it is and close with how it went.

The stringifiers stay on the binding and never on the `Tool`, for the reason `ActionRenderer` gives
today: a sentence authored by the tool being governed is not a control.

## 6. What is stored, and when

### 6.1 The action

`ActionRequest.ToolCall` (`nessy-backend-spi`, `org.jwcarman.nessy.backend.event`) gains it:

```java
record ToolCall(CallId id, ToolName name, String action) implements ActionRequest {}
```

It is never null and never blank; the record refuses either. It travels wherever the call already
travels: `EffectOutcome.InferenceRequestedActions`, `AgentCommand.RequestedActions`,
`AgentEvent.ActionsRequested` and `OutstandingAction`. The fold decides nothing by it.

It is written in `InferenceHandler`, when the model's reply is `InferenceResult.Actions`, for each
`Block.ToolCall` in it. The handler is given the agent type's bound tools (`Tools`) for this. It
is the one place the model's request, its arguments and the bindings are all at hand, and it is
before the event is appended.

`ToolBinding` gains `String describe(String arguments)`, which never throws:

| Case | The action stored |
|---|---|
| The arguments read and the stringifier returns text | that text, one line and within the binding's limit |
| The stringifier returns null or blank | `<tool name>` |
| The arguments do not read into the input type | `<tool name> (its arguments could not be read)` |
| The stringifier throws | `<tool name> (its arguments could not be read)`, and a WARN |
| The model named a tool that is not bound | `<tool name> (no such tool)` |

### 6.2 The result

`ToolSucceeded` gains the line, in both places it is written:

```java
// EffectOutcome
record ToolSucceeded(CallId callId, PayloadRef result, String rendered) implements EffectOutcome {}
// AgentEvent
record ToolSucceeded(Seq seq, TurnId turn, CallId callId, PayloadRef result, String rendered)
```

It is never null. It may be empty: a tool that returned nothing a reader needs, or a stringifier that
said nothing. It is written in `ToolCallHandler`, when the tool returns a success, from the
binding's result stringifier. A stringifier that throws gives an empty line and a WARN; the call still
succeeded.

`ToolFailed` and `ToolDenied` are unchanged. They already hold a message and a reason.

### 6.3 Fixed when written

Neither line is worked out again. This is the rule `ApprovalRequest.action` has today, for the
same reason and one more: bindings change between deployments, and a line re-rendered later would
describe the call with a stringifier that did not exist when the call was made.

### 6.4 Who reads them

`ActionRenderer`'s documentation names three readers, "none of them the model". The summarising
model is now a fourth, and `ToolConfig.action`, which inherits that documentation, says so. What matters is untouched: the
lines are authored on the binding, by the application, never by the tool and never by the model.

A stringifier can leave a field out. The default cannot: `toString()` prints every component. The
raw arguments are already in the story and already replayed to the answering model, so the
default discloses nothing to a model that it did not already see; what is new is that a bounded
copy sits in the event and may be quoted in a summary. The warning now on
`ActionRenderer.byToString()` goes to `ToolConfig.action` and is extended to say so.

## 7. Approval reads the stored action

`ApprovalHandler` builds the `ApprovalRequest` from the action on the outstanding call and stops
rendering one of its own. `ToolBinding.question(...)` takes the action as an argument.

It still reads the call's arguments first, as it does today, because a call whose arguments will
not read cannot run whatever anybody answers and is discharged without asking. That behaviour and
its message to the model do not change.

## 8. A turn hands the lines over

`Exchange` (`nessy-api`, `org.jwcarman.nessy.api.turn`) gains them:

```java
public record Exchange(
    Seq seq,
    List<Block.ActionRequestContent> request,
    List<ToolOutcome> outcomes,
    Map<CallId, String> actions,
    Map<CallId, String> results) {

  /** What the call was recorded as doing, when it was requested. */
  public String actionOf(CallId id) { ... }

  /** What the call was recorded as returning, if it has succeeded. */
  public Optional<String> resultOf(CallId id) { ... }
}
```

Every call in `request` has an action; the record refuses an exchange where one is missing, and
`actionOf(CallId)` refuses an id that is not one of its calls. `results` holds a line for each call
that succeeded and for no other. There is no shorter constructor. `Transcript` (engine), which
builds exchanges from events, fills both maps from the events. `request` and `outcomes` are
unchanged: the adapters still build the live context from them.

## 9. What the summariser is shown

`Transcripts.render` writes a finished call as one line: the action, then how it ended.

```text
user: my birthday is on 12/21
assistant did: DaysUntil[date=2025-12-21] -- succeeded: -285 days
assistant did: Remember[hook=Birthday is December 21st, body=User's birthday is on December 21st (12/21)] -- succeeded: noted as n_4
assistant: Got it! So your birthday was about 9.5 months ago (last December). ...
user: how about my upcoming birthday in 2026
assistant did: DaysUntil[date=2026-12-21] -- succeeded: 80 days
assistant: Perfect! Your birthday is in 80 days ...
```

- Succeeded with an empty result line: `assistant did: <action> -- succeeded`.
- Failed: `assistant did: <action> -- failed: <the failure's message>`.
- Denied: `assistant did: <action> -- denied: <the reason>`.
- No outcome recorded: `assistant did: <action> -- no outcome recorded`.
- A failure's message and a denial's reason are made one line and cut with
  `Truncator.dropMiddle()` at `ToolConfig.DEFAULT_LINE_LIMIT` as they are written into the
  transcript. They are stored whole, as today.
- What the model said beside its calls is kept, as `assistant: <text>`, as today.

`ProseSummarizer` sends that text. Its request holds no summaries, an empty tail, and one active
turn whose input is the rendered chapter, a blank line, and the closing ask ("Write the record of
everything above now."). It declares no tools, sends no tool blocks and no provider (reasoning)
blocks, and is a one-off, as today.

`Transcripts.render` is also what the chapter lab's hindsight policy reads; it gets the same
lines.

How much this costs a summary request: with nothing configured a call's line is at most its
action and its result at 255 each, 510 characters, about 130 tokens, so sixty calls in a chapter
is under 8,000 tokens. At the cap a call is 2,000 characters, about 500 tokens. Most lines are
far shorter than either.

## 10. What does not change

- The context of the turn being answered. The adapters build it from the model's own blocks and
  the tools' full results.
- What is stored besides the two lines: the request payload, each result payload, every other
  event.
- The tail. Completed turns after the last summary are sent whole.
- `Block.ToolCall`. The action is the engine's record of a call, not something the model wrote, so
  it is not on the block.

## 11. What a caller has to change

- `Exchange` has two more components and no shorter constructor: code that builds one supplies
  the lines.
- `ActionRequest.ToolCall` and `ToolSucceeded` each have one more component. Both are in the
  backend SPI, so a custom backend that builds them supplies it.
- A database holding events written before this change cannot be read by this build. Recreate it.

## 12. Testing

- `Truncator`: for each of the three, text within the limit is untouched; text over it comes back
  at exactly the limit with the marker where §4 says; a two-unit character at the cut is kept or
  dropped whole; a limit too small for the marker gives a plain cut.
- `Stringifier.byToString()` is `String.valueOf`, null included; `Stringifier.json(mapper)` writes
  a record as the JSON the mapper gives, and throws for a value the mapper cannot write.
- The wrappers on `Stringifier`: `dropTail`, `dropHead`, `dropMiddle` and `truncated` return a
  stringifier that makes its text one line and cuts it; a limit below 1 is refused; a truncator
  that returns more than the limit is cut to it and a WARN is logged; a wrapper asked for a limit
  at or above its own returns itself, whichever dropper is asked for; asked for a smaller one it
  wraps again and both cuts apply.
- `ToolBinding.describe`: each row of the table in §6.1.
- The result line: the default is the result's text, one line, middle dropped at 255; a
  stringifier that throws gives an empty line and the call still succeeds.
- `ToolConfig`: with no stringifier named, an action over 255 characters keeps its start and a
  result over 255 keeps both ends; a plain stringifier the binding names is cut at 1,000, the
  action keeping its start and the result both ends; one already dropping at or below 1,000 is
  used as given, cut its own way; one dropping above 1,000 is cut to 1,000.
- `InferenceHandler`: the outcome's calls carry their actions; an unbound tool and unreadable
  arguments carry their fallbacks.
- `ToolCallHandler`: a success carries its rendered line.
- The event round trip: `actions-requested` and `tool-succeeded` read back with their lines; a call
  with a null or blank action is refused.
- `ApprovalHandler`: the question carries the stored action; unreadable arguments are still
  discharged without asking.
- `Exchange`: a call with no action is refused; `actionOf(CallId)` refuses an id it does not hold;
  `resultOf(CallId)` is empty for a call that did not succeed.
- `Transcripts.render`: each line shape in §9.
- `ProseSummarizer`: for a chapter with tool turns, the request holds no `Block.ToolCall`, no tool
  outcome and no `Block.Provider`, and its one input is the rendered text followed by the ask.
- Both doors: a turn that calls a tool, then a chapter cut, then a summary, with a scripted
  provider that fails any summary request containing a tool block.
- Live, in `AnthropicLiveTest`: a chapter whose turns called a tool is summarised and the reply is
  not empty. This is the case that failed on 2026-10-02.

## 13. Not in this design

- **Hiding old results in the live context.** The two stored lines are what would stand in their
  place. Not built here.
- **Narrating tool use to a UI from the stored lines.** Narration announces a call from the
  handler today and is not changed.
- **Memory built from actions.**
- **The summary prompt.** The probe showed summaries of a chapter that changes topic keeping only
  the later topic. That is prompt work, with the length target.
- **Other vendors.** Whether OpenAI, Gemini or Bedrock also return nothing for tool history with
  no tools declared was not measured. This design stops sending such a request to any of them.

## 14. Open questions

1. The remaining provisional names in §2. None is a concept; each names something already ruled.
