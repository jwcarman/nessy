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
package org.jwcarman.nessy.spi.inference;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Objects;
import org.jwcarman.nessy.api.block.Block;

/**
 * What an inference came to.
 *
 * <p>Nouns, not events: each arm names the thing that came back, which is why this reads {@code
 * Answer} and {@code Refusal} where {@link org.jwcarman.nessy.engine.agent.EffectOutcome} reads
 * {@code InferenceAnswered} and {@code InferenceRefused}. That one is the fold's story and is told
 * in the past tense on purpose. Two vocabularies, deliberately.
 *
 * <p><b>The axis is whether the turn can close.</b> An answer, a refusal and a fault all end it;
 * nothing is owed. The arm still missing is {@code Exchange} -- a model asking for tool calls,
 * which ends nothing and leaves the turn open. It is absent because its payload is {@code
 * ExchangeContentBlock}, which this project has not ported yet, and an arm with no producer would
 * be a stub.
 *
 * <p><b>Every provider models this as a flag.</b> OpenAI hangs {@code finish_reason} off one
 * response object, Anthropic hangs {@code stop_reason}, Gemini {@code finishReason}. Reifying that
 * flag into arms is the deliberate departure: the caller does something different with each, and a
 * flag is how "different" gets missed.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = InferenceResult.Answer.class, name = "answer"),
  @JsonSubTypes.Type(value = InferenceResult.Refusal.class, name = "refusal"),
  @JsonSubTypes.Type(value = InferenceResult.Fault.class, name = "fault")
})
public sealed interface InferenceResult {

  /** Said by every arm, which all carry a usage that may be unknown but never absent. */
  String USAGE_NOT_NULL = "usage must not be null";

  /**
   * What the call cost, whatever it came back as. A refusal and a fault that reached the model are
   * billed too; an adapter that was not told reports {@link Usage#unknown()}.
   */
  Usage usage();

  /** The same result, with its cost: how an adapter attaches what the vendor counted. */
  InferenceResult withUsage(Usage usage);

  /**
   * The model stopped and owes nothing.
   *
   * <p>Carries blocks rather than a message, and never a string. Not a string because content is
   * wire content: today only text, and the grammar says so -- {@code Block.AnswerContent} permits
   * {@code Block.Text} alone, and widening that one clause is the whole change when an answer may
   * carry more. Not a message because where it sits in the story is the fold's to decide, and a
   * provider adapter has no business knowing a seq.
   */
  record Answer(List<Block.AnswerContent> blocks, Usage usage) implements InferenceResult {

    public Answer {
      Objects.requireNonNull(usage, USAGE_NOT_NULL);
      Objects.requireNonNull(blocks, "blocks must not be null");
      blocks = List.copyOf(blocks);
    }

    public Answer(List<Block.AnswerContent> blocks) {
      this(blocks, Usage.unknown());
    }

    @Override
    public Answer withUsage(Usage usage) {
      return new Answer(blocks, usage);
    }
  }

  /**
   * The model declined, and the call succeeded.
   *
   * <p>Not a fault: HTTP 200, input tokens billed, nothing wrong with the request. And not an
   * answer either -- measured against Anthropic, a refusal carries no content at all, so making an
   * answer of it would mean storing an emptiness or inventing prose the model never said.
   *
   * <p>{@code category} is whatever the provider was willing to say -- "bio", for instance.
   * Providers differ sharply here and the type should not pretend otherwise: Anthropic signals a
   * refusal structurally through {@code stop_reason}, while OpenAI returns ordinary prose that no
   * adapter can tell from an answer. An adapter that cannot detect a refusal simply never produces
   * this, which is honest rather than a gap.
   */
  record Refusal(String category, Usage usage) implements InferenceResult {

    public Refusal {
      Objects.requireNonNull(usage, USAGE_NOT_NULL);
      Objects.requireNonNull(category, "category must not be null");
    }

    public Refusal(String category) {
      this(category, Usage.unknown());
    }

    @Override
    public Refusal withUsage(Usage usage) {
      return new Refusal(category, usage);
    }
  }

  /**
   * No answer could be obtained.
   *
   * <p>An arm rather than an exception, and that is a change of mind worth recording. The failure
   * was always caught by the immediate caller, every time, because an agent mid-turn waits forever
   * unless something reaches the fold -- and an exception unconditionally caught one frame up is a
   * return value in a costume. As an arm it is exhaustively checked like everything else here.
   *
   * <p>It does not make faults and refusals interchangeable. A sibling arm keeps them exactly as
   * distinct as they were; only a flag would have flattened them.
   *
   * <p><b>Classified failures are results; bugs are still exceptions.</b> A provider's status code
   * becomes a {@link Failure} here. A null dereference in an adapter does not -- it throws, and
   * should.
   */
  record Fault(Failure failure, Usage usage) implements InferenceResult {

    public Fault {
      Objects.requireNonNull(usage, USAGE_NOT_NULL);
      Objects.requireNonNull(failure, "failure must not be null");
    }

    public Fault(Failure failure) {
      this(failure, Usage.unknown());
    }

    @Override
    public Fault withUsage(Usage usage) {
      return new Fault(failure, usage);
    }
  }

  /**
   * The model asked for work before it would answer.
   *
   * <p>Not a failure and not an answer: the call succeeded and produced a message, and that message
   * is a request. Kept apart from {@link Answer} because the two have opposite effects on a turn --
   * one ends it, the other extends it -- and an adapter that conflated them would close a turn
   * holding calls nobody will ever run.
   *
   * <p>Carries the whole message rather than just the calls, because the prose and the vendor state
   * around them are part of it and are re-sent with it.
   */
  record Actions(List<Block.ActionRequestContent> blocks, Usage usage) implements InferenceResult {

    public Actions {
      Objects.requireNonNull(usage, USAGE_NOT_NULL);
      Objects.requireNonNull(blocks, "blocks must not be null");
      if (blocks.stream().noneMatch(Block.ToolCall.class::isInstance)) {
        // A request for actions that asks for nothing would move the agent into waiting
        // for work it never requested, and nothing would ever arrive to move it on.
        throw new IllegalArgumentException("a request for actions must contain at least one call");
      }
      blocks = List.copyOf(blocks);
    }

    public Actions(List<Block.ActionRequestContent> blocks) {
      this(blocks, Usage.unknown());
    }

    @Override
    public Actions withUsage(Usage usage) {
      return new Actions(blocks, usage);
    }
  }
}
