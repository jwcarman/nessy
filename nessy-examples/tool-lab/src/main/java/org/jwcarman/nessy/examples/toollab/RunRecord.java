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
package org.jwcarman.nessy.examples.toollab;

import java.util.List;
import org.jwcarman.nessy.examples.toollab.Task.Kind;

/**
 * Everything measured about one run of one task under one variant.
 *
 * @param taskId the task's id
 * @param kind the task's kind
 * @param variant the variant the run used
 * @param runIndex which repetition of this task and variant, from 0
 * @param ending how the run ended
 * @param success whether it did what the task asked
 * @param catalogCalls calls to catalog tools, whether or not they ran
 * @param wrongCalls catalog calls that matched no expected call; every write call of an ambiguous
 *     task
 * @param metaCalls calls to {@code search_tools} or {@code load_tools}
 * @param unloadedCalls calls to a catalog tool that was not offered
 * @param unknownCalls calls to a name that is not a tool at all
 * @param rounds how many times the model was called
 * @param usage what the calls cost, summed
 * @param wallMillis how long the run took
 * @param maxOffered the most tools offered on any one call
 * @param answer the model's final text, empty if it did not answer
 * @param note what went wrong, if the run ended in a fault
 * @param calls every call the model made, one line each, in order
 */
record RunRecord(
    String taskId,
    Kind kind,
    Variant variant,
    int runIndex,
    Ending ending,
    boolean success,
    int catalogCalls,
    int wrongCalls,
    int metaCalls,
    int unloadedCalls,
    int unknownCalls,
    int rounds,
    Tally usage,
    long wallMillis,
    int maxOffered,
    String answer,
    String note,
    List<String> calls) {}
