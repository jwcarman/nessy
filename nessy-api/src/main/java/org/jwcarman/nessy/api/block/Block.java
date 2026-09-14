/*
 * Copyright © 2026 James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessy.api.block;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.ToolName;

/**
 * One piece of message content that crosses the wire to a provider.
 *
 * <p>That is the whole definition, and it is the test for whether a type belongs here: if it
 * appears inside a message's content array in a provider request or response, it is a {@code
 * Block}; if it does not, it is not. A tool's {@code ToolResult} is its answer and never crosses
 * the wire, so it is not a block; what the engine builds from one is.
 *
 * <p><b>The whole grammar lives in this file</b>, which is what lets every {@code permits} clause
 * be left off: a sealed type whose subtypes share its compilation unit has them inferred. So each
 * relationship is stated exactly once, on the record, and the matrix of what may appear where can
 * be read straight down the page instead of assembled from six files.
 *
 * <p><b>The position interfaces are the enforcement.</b> A block does not carry a list of places it
 * is allowed; it implements the ones it belongs to, and a container names the position it accepts
 * -- {@code List<AnswerContent>} rather than {@code List<Block>}. Illegal content then fails to
 * compile in the code that produced it, which matters because the producers are other people's: a
 * renderer, a tool, a provider adapter. The alternative is a constructor that throws, and every one
 * of those authors writing tests to prove they never trip it.
 *
 * <p>They also narrow exhaustiveness where it is consumed. A switch over {@link AnswerContent}
 * needs only the arms legal in an answer -- the compiler will not let an adapter handle a case that
 * cannot arise, nor forget one that can.
 *
 * <p>Carries a {@code "type"} discriminator naming the record on the wire. The values are a
 * compatibility surface and must never change: stored transcripts name them.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Block.Text.class, name = "text"),
  @JsonSubTypes.Type(value = Block.Provider.class, name = "provider"),
  @JsonSubTypes.Type(value = Block.Commentary.class, name = "commentary"),
  @JsonSubTypes.Type(value = Block.ToolCall.class, name = "tool-call")
})
public sealed interface Block {

  // -------------------------------------------------------------------------------------
  // Positions: what may appear where. One interface per slot in the conversation.
  // -------------------------------------------------------------------------------------

  /**
   * What an observation may carry.
   *
   * <p>Named for the observation rather than for the wire role it lands on. A provider calls this
   * slot {@code user}, but plenty of observations have no user behind them -- a sensor reading, a
   * webhook, a scheduled tick -- and naming the domain after the format would make the engine
   * describe a thermometer as a person. The adapter maps observation to {@code user} when it builds
   * a request; that is the one place the word belongs.
   */
  sealed interface ObservationContent extends Block {}

  /**
   * What an answer may carry.
   *
   * <p>An answer is one of the two shapes an inference comes back in: the model said its piece and
   * the turn is over. No tool call will ever be permitted here, and that is not tidiness -- an
   * answer asks for nothing, and a turn that is still asking has not answered. It is a
   * state-machine invariant wearing a type. An answer able to carry a call would let an adapter
   * close a turn that has not finished, which is the one mistake in this area that corrupts the
   * story rather than merely failing.
   */
  sealed interface AnswerContent extends Block {}

  /**
   * What a request for actions may carry.
   *
   * <p>The other shape an inference comes back in: the model wants something done before it can
   * finish. The slot holds more than the calls themselves -- the prose written alongside them, and
   * whatever reasoning state the vendor attached -- because all of it is one message on the wire
   * and re-sent as one.
   *
   * <p>Deliberately not a narrowing of {@link AnswerContent}, nor the reverse. The two slots
   * overlap in what they accept, and saying so by subtyping would be true but unreadable: every
   * record below would stop naming where it may appear, and the matrix this file exists to make
   * legible would have to be assembled from the {@code extends} clauses instead. Four more words
   * per record buys each one the complete statement of where it belongs.
   */
  sealed interface ActionRequestContent extends Block {}

  /**
   * What background may carry.
   *
   * <p>Things nobody said. Saved notes, a standing plan, what time it is -- assembled when the
   * model is called, shown once, and thrown away. Its own position rather than a reuse of {@link
   * ObservationContent}, because an observation is something that <em>happened</em> and is written
   * down forever, while this is a view of the world as it stands right now. Letting one stand in
   * for the other is how a note ends up in a transcript.
   */
  sealed interface AmbientContent extends Block {}

  /**
   * What a tool may hand back.
   *
   * <p>A result may not contain a result. Nesting has no meaning on any provider's wire, and
   * leaving it expressible would only invite an adapter to decide what it meant.
   */
  sealed interface ToolResultContent extends Block {}

  // -------------------------------------------------------------------------------------
  // Kinds: each declares the positions it is legal in, and that is the only declaration.
  // -------------------------------------------------------------------------------------

  /**
   * A run of text. Legal everywhere: from either side of the conversation, and inside a tool
   * result.
   *
   * <p><b>Not legal beside a tool call.</b> A turn that is still asking has not answered, so prose
   * arriving with calls is the model talking while it works -- {@link Commentary}, always. Leaving
   * {@code Text} representable there would make that a judgement an adapter could get wrong, and
   * getting it wrong files progress talk as the answer.
   *
   * <p>Empty is rejected; whitespace is not. Whether {@code " "} means anything is a judgement
   * about content, and a block type has no business making it -- every answer this engine has
   * stored so far begins with two newlines, which a blank check would have thrown away.
   */
  record Text(String text)
      implements ObservationContent, AnswerContent, ToolResultContent, AmbientContent {

    public Text {
      Objects.requireNonNull(text, "text must not be null");
      if (text.isEmpty()) {
        // A block that renders to nothing is not content, and two of the three providers
        // measured reject a request whose rendered user turn is empty -- one of them by
        // failing to build a prompt at all. Stored, such a block is re-sent on every later
        // turn, so a single one dooms a conversation. Unrepresentable beats remembered.
        throw new IllegalArgumentException("text must not be empty");
      }
    }
  }

  /**
   * Opaque state a provider attached and expects back.
   *
   * <p><b>Text, never a tree.</b> A vendor signs this -- Anthropic's extended thinking carries a
   * signature verified on the way back in, OpenAI's reasoning items are encrypted -- and a
   * signature covers bytes. Anything that parses and re-serialises is free to reorder a key,
   * reformat a number or unescape a slash, and the block stops validating. So it is stored exactly
   * as handed over and handed back unchanged. Nothing here ever looks inside, which is also why
   * there is no schema for it: what it means is the vendor's business entirely.
   *
   * <p><b>Tagged, because history outlives a provider choice.</b> The same agent may be answered by
   * one vendor today and another tomorrow -- a model switch, a fallback, a cost decision -- and one
   * vendor's reasoning state is meaningless to the next. An adapter drops the blocks it does not
   * own rather than translating them, which is the one place the projection is not
   * provider-neutral, and the tag is what lets the adapter decide that locally instead of the
   * projection needing to know who it is building for.
   *
   * <p>So a turn re-sent to a different vendor simply arrives without its reasoning state, and that
   * has to be fine. These are the one kind whose absence is always legal.
   *
   * <p><b>Order is part of the payload.</b> A signature may cover a whole content array, so a
   * projection must not reorder blocks within a turn or drop the siblings around one.
   *
   * @param vendor who attached it, and the only one who may be given it back
   * @param payload the vendor's own bytes, uninterpreted
   */
  record Provider(String vendor, String payload) implements AnswerContent, ActionRequestContent {

    public Provider {
      Objects.requireNonNull(vendor, "vendor must not be null");
      Objects.requireNonNull(payload, "payload must not be null");
      if (vendor.isBlank()) {
        throw new IllegalArgumentException("vendor must not be blank");
      }
    }
  }

  /**
   * The model asked for a tool to be run.
   *
   * <p><b>Arguments are text, not a tree.</b> Not for byte-fidelity -- nothing signs these, and the
   * wires disagree anyway: OpenAI already sends a string, Anthropic sends an object, so one of the
   * two is re-serialised no matter which we pick. The reason is that a tree component would be a
   * mutable one. Every {@code JsonNode} in hand is really an {@code ObjectNode}, and a record
   * holding one is not a value -- a caller can reach into a stored block and change it afterwards.
   * Avoiding that means a defensive deep copy on every construction, which is a real cost paid
   * forever to buy a parse we do once.
   *
   * <p>The trade, stated so it is not re-argued from scratch: stored inside the entry's payload the
   * arguments are escaped rather than nested, so Postgres cannot reach into them with {@code ->>}.
   * If querying them ever matters, a generated column extracting the parse is the cheaper fix than
   * making the block mutable.
   *
   * <p>Unparsed here also means unvalidated here. A model emits malformed JSON often enough that it
   * is ordinary, and that is a tool failure to be reported back to the model so it can try again,
   * not a block that refuses to exist. Rejecting it at construction would put the failure in the
   * adapter decoding the response, where the only available move is to fail the turn.
   *
   * <p><b>One of these obliges the engine to write down exactly one outcome for it.</b> Not every
   * block in this slot does: a provider-executed tool -- web search, a code interpreter -- is
   * requested and resolved inside the provider and arrives already settled, so it is content to be
   * re-sent and nothing to be dispatched. Today {@code ToolCall} is the only kind here that obliges
   * anything, so it is the boundary rather than naming one. When a second kind lands in this slot,
   * the fold's switch stops compiling and somebody says which it is.
   *
   * <p>The obligation is not optional. A call left without a result makes the conversation
   * unsendable -- every provider rejects a request containing one -- so the agent is wedged rather
   * than merely wrong.
   *
   * @param id the provider's own correlation id, which its result must quote back
   * @param name the tool as offered, which need not still be bound when the call arrives
   * @param arguments a JSON object as the model wrote it, uninterpreted and possibly invalid
   */
  record ToolCall(CallId id, ToolName name, String arguments) implements ActionRequestContent {

    public ToolCall {
      Objects.requireNonNull(id, "id must not be null");
      Objects.requireNonNull(name, "name must not be null");
      Objects.requireNonNull(arguments, "arguments must not be null");
    }

    /** From a provider's own strings, which is where every one of these comes from. */
    public ToolCall(String id, String name, String arguments) {
      this(new CallId(id), new ToolName(name), arguments);
    }
  }

  /**
   * The model talking while it works -- "I'll look that up for you".
   *
   * <p>Distinct from {@link Text}, which is an answer. The difference is not decoration: a reader
   * wants the answer, and progress talk is colour a transcript may show quietly or not at all.
   * Splitting them means nothing downstream has to infer which it is by checking whether the
   * message happened to make tool calls.
   *
   * <p><b>That inference is the thing this exists to remove, and it is wrong more often than it
   * looks.</b> A model that says "let me check" and then stops without emitting any calls -- a
   * length limit, arguments that would not parse -- has produced commentary in a message with no
   * calls in it. Position would file that as the answer. And a single message can carry commentary,
   * a provider-executed search and the answer together, where position cannot separate them at all
   * because they are one list.
   *
   * <p><b>Decided in exactly one place: the adapter, when the turn stops.</b> That is where the
   * stop reason says whether the model was working or answering, and it is the only place that
   * knows. Everything past it reads a block that already says which it is.
   *
   * <p>Legal in both slots, and it is the ONLY prose legal beside a call. A turn that is still
   * asking has not answered, so there is nothing in that slot an answer could be made of -- and a
   * turn that ends without asking can still have said "let me check" on its way to stopping, which
   * is why it is legal in an answer too.
   */
  record Commentary(String text) implements AnswerContent, ActionRequestContent {

    public Commentary {
      Objects.requireNonNull(text, "text must not be null");
      if (text.isEmpty()) {
        // Same hole as an empty Text: a block that renders to nothing is not content, and
        // it is stored and re-sent for the life of the conversation.
        throw new IllegalArgumentException("text must not be empty");
      }
    }
  }
}
