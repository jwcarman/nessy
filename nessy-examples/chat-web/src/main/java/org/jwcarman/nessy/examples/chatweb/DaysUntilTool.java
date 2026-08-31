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
package org.jwcarman.nessy.examples.chatweb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * The ungated tool: counts days to a date.
 *
 * <p>Something a model is bad at and a tool is trivially good at, so watching it get reached for is
 * watching tool use earn its keep. It runs with no ceremony at all, which is what makes the gated
 * {@link SendEmailTool} beside it legible as the exception rather than the rule.
 */
public final class DaysUntilTool implements Tool<DaysUntilTool.Input> {

  public record Input(String date) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = JSON.createObjectNode();
    schema.put("type", "object");
    ObjectNode date = schema.putObject("properties").putObject("date");
    date.put("type", "string");
    date.put("description", "The target date, as ISO-8601: 2026-12-25");
    schema.putArray("required").add("date");
    return schema;
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
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
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
