package org.jwcarman.nessy.prompt;

/** The engine: turns template source into a {@link PromptTemplate}. One per syntax. */
@FunctionalInterface
public interface PromptTemplateFactory {

  PromptTemplate compile(String source);
}
