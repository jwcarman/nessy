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
package org.jwcarman.nessy.examples.newsroom;

import javax.sql.DataSource;
import org.jwcarman.nessy.model.env.EnvModelProviders;
import org.jwcarman.nessy.model.env.EnvModelProviders.Selection;

/**
 * A subagent call is a tool call whose work is another agent's conversation: the writer delegates
 * research to the researcher through an ordinary tool call (see {@link NewsroomAgents}), and when
 * the researcher's own {@code ask_question} tool parks on approval, the writer's delegation call
 * parks right alongside it (see {@link NewsroomRepl}). Provider selection follows {@code
 * chat-cli}'s lesson — {@link EnvModelProviders#select()} picks Anthropic, OpenAI, Gemini, or xAI
 * by whichever API key is set — and persistence follows {@code dispatcher}'s: a durable
 * Postgres-backed {@link org.jwcarman.nessy.Harness}, so a park survives this process being killed
 * and restarted.
 */
public final class Newsroom {

  private Newsroom() {}

  public static void main(String[] args) {
    Selection selection;
    try {
      selection = EnvModelProviders.select();
    } catch (IllegalStateException e) {
      System.out.println(e.getMessage());
      System.exit(1);
      return;
    }
    DataSource dataSource = NewsroomDatabase.fromEnv();
    NewsroomAgents.Built built =
        NewsroomAgents.agentsFor(selection.provider(), selection.model(), dataSource);
    new NewsroomRepl(built).run();
    // The REPL is done, but the model-provider SDK's HTTP client keeps non-daemon worker
    // threads alive after the last call (idle connection pools linger for up to a minute).
    // Exiting here is honest cleanup of SDK threads we don't own, not a workaround for
    // state we failed to release.
    System.exit(0);
  }
}
