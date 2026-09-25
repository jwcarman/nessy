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
package org.jwcarman.nessy.engine.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.jwcarman.nessy.inference.tool.CallId;
import org.jwcarman.nessy.inference.tool.ToolName;

/**
 * One thing the model asked to have done, named but not carried.
 *
 * <p><b>Sealed with one arm, deliberately.</b> A tool call is the only action anything can ask for
 * today and may be the only one ever. It is a grammar rather than a single record because these are
 * stored: a second kind of action -- a handoff, a sub-agent, a resource read -- arrives as a new
 * arm beside this one, where a record named for tools would have made every row in the database
 * wrong and a rename into a migration.
 *
 * <p><b>Identifiers only.</b> What the model actually wrote -- the arguments -- is content, and
 * lives in the payload the {@link AgentEvent.ActionsRequested} that holds this points at. The id is
 * what routes back into that payload when somebody needs them, which is at the moment the action is
 * performed and not before.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = ActionRequest.ToolCall.class, name = "tool-call")})
public sealed interface ActionRequest {

  /** Which one this is, and how it is found in the request that asked for it. */
  CallId id();

  /**
   * A tool, by name.
   *
   * @param id what the provider called this call, quoted back in its result
   * @param name which tool to run; the arguments are in the request's payload, under {@link #id()}
   */
  record ToolCall(CallId id, ToolName name) implements ActionRequest {}
}
