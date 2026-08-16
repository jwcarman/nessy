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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Function;
import javax.sql.DataSource;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Harness;
import org.jwcarman.nessy.Nessy;
import org.jwcarman.nessy.api.ConversationSettled;
import org.jwcarman.nessy.api.approval.Approver;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.SubjectId;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.UsagePolicy;
import org.jwcarman.nessy.jdbc.JdbcPersistence;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.spi.model.ModelProvider;
import org.jwcarman.nessy.spi.notebook.NotebookTools;
import org.jwcarman.nessy.spi.plan.PlanStore;
import org.jwcarman.nessy.spi.plan.PlanTools;
import org.jwcarman.nessy.spi.subagent.AgentTools;
import org.jwcarman.nessy.spi.subagent.CallbackRouter;
import org.jwcarman.nessy.spi.subagent.SubagentLinks;

/**
 * The newsroom's two agents, wired over one shared, durable substrate (spec §9): {@code writer}
 * delegates research to {@code researcher} via {@link AgentTools#subagent}; {@code researcher}'s
 * one gated tool, {@link AskQuestionTool}, parks on approval alone ({@link Approver#parkAll()}),
 * and because a subagent call is an ordinary tool call, the writer's own delegation call parks
 * right alongside it — the child-parks-therefore-parent-parks chain the module demonstrates.
 *
 * <p>Fan-out here is sequential, not parallel (spec §9): the writer waits on one {@code researcher}
 * call at a time before it can act on the answer or delegate again — there is no scatter/gather
 * across several outstanding delegations in this demo.
 *
 * <p>Both agents resolve to the same fixed {@link SubjectId} and share one {@link
 * org.jwcarman.nessy.spi.notebook.Notebook} (spec §9 continuity ruling): a note the writer keeps is
 * visible in the researcher's own context on its very next turn, and vice versa, because both
 * agents' {@link Memory} pipelines carry the same {@link NotebookTools#transformer} over the same
 * notebook and resolver.
 */
final class NewsroomAgents {

  static final ConversationId WRITER_CONVERSATION_ID =
      new ConversationId("newsroom-writer-session");

  private static final SubjectId SUBJECT = new SubjectId("newsroom");

  private static final String WRITER_SYSTEM_PROMPT =
      "You are the writer for a small newsroom. When a story needs facts, delegate the research to"
          + " the researcher tool with one clear, specific task description, then wait for its"
          + " answer before you write. For any multi-step story, maintain your plan with"
          + " update_plan. When the researcher tells you something worth keeping for later"
          + " stories, remember it. Write tersely.";

  private static final String RESEARCHER_SYSTEM_PROMPT =
      "You are the newsroom's researcher. Use search_notes to look up a topic (try: octopus,"
          + " volcano, coffee). If the task you were given is ambiguous — you cannot tell which"
          + " topic it means, or what the writer actually wants — call ask_question and wait for"
          + " your editor to answer; do not guess. Otherwise, answer directly and concisely from"
          + " what search_notes told you.";

  private NewsroomAgents() {}

  /** The agents built, plus the pieces {@link NewsroomRepl} needs to drive them. */
  record Built(
      Agent<String> writer,
      Agent<String> researcher,
      PlanStore planStore,
      PendingAnswers answers) {}

  static Built agentsFor(ModelProvider provider, String model, DataSource dataSource) {
    ObjectMapper mapper = new ObjectMapper();
    JdbcPersistence persistence = JdbcPersistence.create(dataSource, mapper);
    SubagentLinks links = persistence.subagentLinks();
    CallbackRouter router = new CallbackRouter();
    PendingAnswers pendingAnswers = new PendingAnswers();
    Function<ConversationId, SubjectId> subjectResolver = id -> SUBJECT;

    Harness harness =
        Nessy.harness(provider)
            .defaultModel(model)
            .store(persistence.store())
            .parks(persistence.parks())
            .listen(
                ConversationSettled.class,
                AgentTools.completions(links, persistence.parks(), router))
            .build();

    Agent<String> researcher =
        harness
            .agent()
            .name("researcher")
            .systemPrompt(RESEARCHER_SYSTEM_PROMPT)
            .tools(
                ToolGrant.grant(new SearchNotesTool(), UsagePolicy.allow()),
                ToolGrant.grant(new AskQuestionTool(pendingAnswers), UsagePolicy.requireApproval()))
            .approver(Approver.parkAll())
            .memory(
                Memory.pipeline(persistence.transcript())
                    .transform(NotebookTools.transformer(persistence.notebook(), subjectResolver))
                    .build())
            .build();

    Agent<String> writer =
        harness
            .agent()
            .name("writer")
            .systemPrompt(WRITER_SYSTEM_PROMPT)
            .tools(
                ToolGrant.grant(PlanTools.updatePlan(persistence.planStore()), UsagePolicy.allow()),
                ToolGrant.grant(
                    AgentTools.subagent(
                        researcher,
                        "Delegates a research task to the researcher subagent. The researcher may"
                            + " ask a clarifying question before it answers.",
                        links),
                    UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.remember(persistence.notebook(), subjectResolver),
                    UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.recall(persistence.notebook(), subjectResolver),
                    UsagePolicy.allow()),
                ToolGrant.grant(
                    NotebookTools.forget(persistence.notebook(), subjectResolver),
                    UsagePolicy.allow()))
            .memory(
                Memory.pipeline(persistence.transcript())
                    .transform(PlanTools.transformer(persistence.planStore()))
                    .transform(NotebookTools.transformer(persistence.notebook(), subjectResolver))
                    .build())
            .build();

    router.register(writer);
    router.register(researcher);

    return new Built(writer, researcher, persistence.planStore(), pendingAnswers);
  }
}
