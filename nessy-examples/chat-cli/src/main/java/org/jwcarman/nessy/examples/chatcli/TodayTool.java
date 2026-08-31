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

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolContext;
import org.jwcarman.nessy.api.tool.ToolResult;

/**
 * What day it is.
 *
 * <p>A model does not know. It has a training cutoff and a strong prior, and asked to help with
 * Christmas shopping it will confidently name whichever year it learned about — then reason from
 * that wrong anchor without ever noticing. This is the smallest tool that fixes a whole class of
 * wrong answers.
 *
 * <p>It answers with the weekday too, because "what day is it" usually means that, and a model
 * computing a weekday from a date is one more thing it is bad at.
 */
final class TodayTool implements Tool<TodayTool.Input> {

  /** No arguments: there is only one thing to ask. */
  record Input() {}

  private final Clock clock;

  TodayTool(Clock clock) {
    this.clock = clock;
  }

  @Override
  public Class<Input> inputType() {
    return Input.class;
  }

  @Override
  public String name() {
    return "today";
  }

  @Override
  public String description() {
    return "Reports today's date. Call this before any reasoning that depends on the current"
        + " date, year, or day of the week — you cannot know these on your own.";
  }

  @Override
  public Awaited<ToolResult> execute(Input input, ToolContext context) {
    LocalDate today = LocalDate.now(clock);
    DayOfWeek day = today.getDayOfWeek();
    return Awaited.ready(
        ToolResult.ok("%s, %s".formatted(day.getDisplayName(TextStyle.FULL, Locale.US), today)));
  }
}
