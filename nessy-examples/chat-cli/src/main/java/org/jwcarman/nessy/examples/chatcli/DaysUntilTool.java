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
package org.jwcarman.nessy.examples.chatcli;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * One tool, so the REPL shows a tool call and not just an echo.
 *
 * <p>It counts the days until a date. That is a deliberate choice of subject: it is something a
 * language model is genuinely bad at and a tool is trivially good at, so watching the model reach
 * for it is watching tool use earn its keep rather than perform it.
 *
 * <p>It also takes a real typed input rather than the no-argument shape, because binding arguments
 * to a record IS the part an example should show.
 */
final class DaysUntilTool implements Tool<DaysUntilTool.Input> {

  record Input(@JsonPropertyDescription("The target date, as ISO-8601: 2026-12-25") String date) {}

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public String name() {
    return "days_until";
  }

  @Override
  public String description() {
    return "Counts the whole days from today until a given ISO-8601 date. Negative if it is past.";
  }

  @Override
  public Awaited<ToolResult> execute(ToolCallRequest<Input> call) {
    Input input = call.input();
    try {
      long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(input.date()));
      return Awaited.ready(ToolResult.ok(days + " days"));
    } catch (java.time.format.DateTimeParseException e) {
      // A failure, not an exception: the model can read this and try again with a better date,
      // which is the whole reason ToolResult has a failed arm.
      return Awaited.ready(
          ToolResult.error("'" + input.date() + "' is not an ISO-8601 date like 2026-12-25"));
    }
  }
}
