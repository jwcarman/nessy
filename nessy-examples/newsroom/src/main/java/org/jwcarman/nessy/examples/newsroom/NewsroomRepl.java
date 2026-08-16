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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jwcarman.nessy.Agent;
import org.jwcarman.nessy.Conversation;
import org.jwcarman.nessy.Subagent;
import org.jwcarman.nessy.api.conversation.ConversationId;
import org.jwcarman.nessy.api.conversation.ConversationSnapshot;
import org.jwcarman.nessy.api.conversation.ConversationStatus;
import org.jwcarman.nessy.api.conversation.ParkedCall;
import org.jwcarman.nessy.api.message.ContentBlock;
import org.jwcarman.nessy.api.message.Context;
import org.jwcarman.nessy.api.message.Message;
import org.jwcarman.nessy.api.message.Role;
import org.jwcarman.nessy.api.message.TextBlock;
import org.jwcarman.nessy.console.ConsoleRenderer;
import org.jwcarman.nessy.spi.plan.Plan;
import org.jwcarman.nessy.spi.plan.PlanStore;

/**
 * The newsroom's own loop, one console turn at a time — {@code ConsoleRepl}'s chrome (banner,
 * prompt, plan checklist) reused where {@code ConsoleRepl} itself could serve, and hand-rolled only
 * where it could not: {@link org.jwcarman.nessy.Conversation#tell} returns a {@code RunOutcome}
 * carrying whether the turn parked, but {@code ConsoleRepl.run()} discards that return value —
 * reasonably, since none of its other examples need it — leaving this the one console example that
 * needs to see it, to drive the approval exchange this module exists to demonstrate.
 *
 * <p>The park chain, end to end: the writer's own conversation lives at a single fixed {@link
 * NewsroomAgents#WRITER_CONVERSATION_ID}, not a fresh one per run, so a park survives a restart
 * (README's transcript). After every {@code tell}, and once more at startup (in case the previous
 * run was killed mid-delegation), {@link #driveApprovalLoop()} checks whether the writer is still
 * {@link ConversationStatus#PARKED}: if so, it finds the researcher's own pending {@code
 * ask_question} call underneath — via {@link org.jwcarman.nessy.Subagent#snapshot(ConversationId)}
 * on {@link #researcher}, the child conversation id derived the same way the delegation tool
 * derives it: {@code writerConversationId/toolCallId} — prints the question, and reads an
 * approve/deny decision from the console. Approving or denying drives the <em>researcher's</em>
 * conversation to settlement through the same {@link #researcher} handle; the internally-wired
 * completions listener then wakes the writer automatically — this loop never resumes the writer
 * directly.
 */
final class NewsroomRepl {

  private static final Set<String> EXIT_WORDS = Set.of("exit", "quit");

  private final Agent<String> writer;
  private final Subagent researcher;
  private final PlanStore planStore;
  private final PendingAnswers pendingAnswers;
  private final Conversation<String> writerConversation;
  private final BufferedReader reader;
  private final Writer out;
  private Plan lastRenderedPlan;

  NewsroomRepl(NewsroomAgents.Built built) {
    this(
        built,
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
        new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
  }

  /** The testability seam: everything above is exercised headless against these streams. */
  NewsroomRepl(NewsroomAgents.Built built, BufferedReader reader, Writer out) {
    this.writer = built.writer();
    this.researcher = built.researcher();
    this.planStore = built.planStore();
    this.pendingAnswers = built.answers();
    this.writerConversation = writer.conversation(NewsroomAgents.WRITER_CONVERSATION_ID);
    this.reader = reader;
    this.out = out;
  }

  void run() {
    write(
        "newsroom — the writer delegates research to the researcher. Type exit or quit to"
            + " leave.\n");
    resumeAnyPendingParkFromEarlierRun();
    while (true) {
      write("writer> ");
      String line = readLine();
      if (line == null || EXIT_WORDS.contains(line.trim())) {
        write("goodbye.\n");
        return;
      }
      if (line.isBlank()) {
        continue;
      }
      tell(line);
    }
  }

  /**
   * Honest about the restart story before the first prompt: if the writer conversation is already
   * parked (this process was killed mid-delegation and just came back up), says so, drives the
   * approval loop, and prints the answer the earlier turn was waiting on — all before the operator
   * types anything new.
   */
  private void resumeAnyPendingParkFromEarlierRun() {
    if (writerStatus() == ConversationStatus.PARKED) {
      write("resuming a delegation parked before this process last exited...\n");
      driveApprovalLoop();
      printWriterAnswer();
    }
  }

  /**
   * The same render-and-continue discipline {@code ConsoleRepl.tell} uses (nessy-console): a raw
   * {@link RuntimeException} out of {@link Conversation#tell} — a provider/network blip — prints
   * one error line and lets the loop reprompt, rather than crashing the whole session over a single
   * bad turn.
   */
  private void tell(String line) {
    try {
      writerConversation.tell(line, ConsoleRenderer.observer(out));
    } catch (RuntimeException e) {
      String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getName());
      write("\n! " + reason + "\n");
      return;
    }
    boolean woken = driveApprovalLoop();
    if (woken) {
      // The post-wake turn ran inside the internal completion wiring, with no console
      // observer attached — nothing streamed it, so echo the settled answer here. On the
      // no-park path the observer above already painted every delta; echoing again would
      // print the answer twice.
      printWriterAnswer();
    }
    renderPlanIfChanged();
  }

  /**
   * Resolves the researcher's pending {@code ask_question} call, once per outstanding question,
   * until the writer's own park clears — the writer may delegate again, or ask a further question
   * through the same researcher, before it finally settles.
   */
  private boolean driveApprovalLoop() {
    boolean resolvedAny = false;
    while (writerStatus() == ConversationStatus.PARKED) {
      resolvePendingQuestion();
      resolvedAny = true;
    }
    return resolvedAny;
  }

  private ConversationStatus writerStatus() {
    return writer.snapshot(NewsroomAgents.WRITER_CONVERSATION_ID).status();
  }

  private void resolvePendingQuestion() {
    ParkedCall delegation = onlyParkedCall(writer.snapshot(NewsroomAgents.WRITER_CONVERSATION_ID));
    ConversationId childId =
        new ConversationId(
            NewsroomAgents.WRITER_CONVERSATION_ID.value() + "/" + delegation.call().id());
    ParkedCall question = onlyParkedCall(researcher.snapshot(childId));
    String questionText = question.call().arguments().get("question").asText();

    write("\nresearcher asks: " + questionText + "\n");
    write("answer it? y/n> ");
    String decision = readLine();
    if (decision != null && decision.trim().equalsIgnoreCase("y")) {
      write("your answer> ");
      String answer = readLine();
      pendingAnswers.record(question.call().id(), answer == null ? "" : answer);
      researcher.approve(question.token());
    } else {
      write("reason for declining> ");
      String reason = readLine();
      researcher.deny(question.token(), reason == null ? "declined at the console" : reason);
    }
  }

  private static ParkedCall onlyParkedCall(ConversationSnapshot snapshot) {
    List<ParkedCall> parked = snapshot.parkedCalls();
    if (parked.isEmpty()) {
      throw new IllegalStateException(
          "conversation status is PARKED but no parked call was found — the loop and the parks"
              + " registry disagree");
    }
    return parked.get(0);
  }

  private void printWriterAnswer() {
    Context context = writer.contextFor(NewsroomAgents.WRITER_CONVERSATION_ID);
    write("\n" + lastAssistantText(context) + "\n\n");
  }

  private static String lastAssistantText(Context context) {
    List<Message> messages = context.messages();
    for (int i = messages.size() - 1; i >= 0; i--) {
      Message message = messages.get(i);
      if (message.role() == Role.ASSISTANT) {
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
          if (block instanceof TextBlock(String blockText)) {
            text.append(blockText);
          }
        }
        return text.toString();
      }
    }
    return "";
  }

  private void renderPlanIfChanged() {
    planStore
        .find(NewsroomAgents.WRITER_CONVERSATION_ID)
        .filter(plan -> !plan.isEmpty())
        .filter(plan -> !plan.equals(lastRenderedPlan))
        .ifPresent(
            plan -> {
              ConsoleRenderer.checklist(out, plan);
              lastRenderedPlan = plan;
            });
  }

  private void write(String text) {
    try {
      out.write(text);
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String readLine() {
    try {
      return reader.readLine();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
