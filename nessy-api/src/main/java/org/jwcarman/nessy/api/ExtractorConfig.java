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
package org.jwcarman.nessy.api;

import org.jwcarman.nessy.api.tool.ToolName;

/**
 * What varies between one extractor and the next.
 *
 * <p>Only what a caller chooses. Which provider does the reading, how a type becomes a schema and
 * how the answer becomes an object are wiring, settled once where the factory is built; the model
 * and what it is told are a decision per kind of document, and belong here.
 */
public interface ExtractorConfig {

  /**
   * Which model reads the document.
   *
   * <p>Named rather than defaulted, because the right one is a judgement about the documents: an
   * invoice number wants the cheapest thing that can find it, and a contract does not.
   */
  ExtractorConfig model(String modelName);

  /** A ceiling on the answer. Left alone, the provider's own. */
  ExtractorConfig maxTokens(int maxTokens);

  /**
   * Replaces what the model is told before the document.
   *
   * <p>The default already says the document is data rather than instructions, which is the one
   * sentence that matters. Anything set here replaces it outright, so a prompt of your own should
   * say it too.
   */
  ExtractorConfig systemPrompt(SystemPrompt systemPrompt);

  /** What the recording tool is called. Worth changing only if the name confuses a model. */
  ExtractorConfig toolName(ToolName toolName);
}
