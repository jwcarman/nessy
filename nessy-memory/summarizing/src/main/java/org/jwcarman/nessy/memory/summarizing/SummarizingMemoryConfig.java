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
package org.jwcarman.nessy.memory.summarizing;

import java.time.Clock;
import java.util.concurrent.Executor;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.spi.memory.TranscriptMemory;
import org.jwcarman.nessy.spi.model.Model;

/** How one {@link SummarizingMemory} is put together. */
public interface SummarizingMemoryConfig {

  /**
   * What actually holds the conversation. Required.
   *
   * <p>A {@link TranscriptMemory} rather than any {@code Memory}, because this reads from a
   * POSITION — everything after the sequence a summary covers. {@code Memory.recall} cannot express
   * that: it returns everything, or a newest-first window whose start moves, so a coverage marker
   * could only be applied by loading the whole transcript and discarding the front of it. Pointless
   * for a whole memory, and wrong for a windowed one.
   */
  SummarizingMemoryConfig transcript(TranscriptMemory transcript);

  /** Where the summary sidecar lives. Required. */
  SummarizingMemoryConfig dataSource(DataSource dataSource);

  /** Which kind of agent this serves — an id is unique only within its type. Required. */
  SummarizingMemoryConfig agentType(AgentType agentType);

  /** Who does the compressing. Required. It is given no tools. */
  SummarizingMemoryConfig model(Model model);

  /**
   * Where compressing runs. Required, and deliberately not defaulted.
   *
   * <p>There is no safe default: this project keeps vendor calls off the threads that matter, and
   * only the application knows which pool it can spare. Making it explicit is what stops a model
   * call quietly landing on whatever thread happened to be writing.
   *
   * <p>Passing {@code Runnable::run} makes compression synchronous, which is what a test wants and
   * what production does not.
   */
  SummarizingMemoryConfig executor(Executor executor);

  /**
   * How far an agent's transcript may outrun its summary before compressing is worth a model call.
   * Must be greater than {@link #keepVerbatim}, or there is never anything to compress.
   */
  SummarizingMemoryConfig summarizeAfter(long messages);

  /**
   * How many of the newest messages are never summarized.
   *
   * <p>Also how often this looks at the database at all: writes in between cost one in-memory
   * increment.
   */
  SummarizingMemoryConfig keepVerbatim(int messages);

  /** How long a summary may be. */
  SummarizingMemoryConfig maxTokens(int maxTokens);

  /** Defaults to the system clock. */
  SummarizingMemoryConfig clock(Clock clock);
}
