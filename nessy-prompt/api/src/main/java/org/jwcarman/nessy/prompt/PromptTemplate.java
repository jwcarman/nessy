package org.jwcarman.nessy.prompt;

/**
 * A prompt with holes in it, compiled once and rendered as often as it is needed.
 *
 * <p>What the holes look like is the engine's business -- {@code ${name}} for the Spring flavour,
 * {@code {{name}}} for a Mustache -- and so is what happens to a hole nothing fills: the engines
 * this project ships refuse, loudly, rather than send a model a prompt with a hole still in it.
 */
@FunctionalInterface
public interface PromptTemplate {

  String render(PromptVariables variables);
}
