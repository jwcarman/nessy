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
package org.jwcarman.nessy.examples.watchman;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import org.jwcarman.nessy.agent.CompletionDesk;
import org.jwcarman.nessy.api.tool.ComputationId;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolGrant;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Every tool this agent has, and — just as important — every tool it does not.
 *
 * <p>The starter turns each of these beans into a grant: a bare {@link Tool} bean is granted {@code
 * Approvers.allow()}, and a {@link ToolGrant} bean is taken exactly as declared. So the split on
 * this page IS the authority model: read-only and note-writing tools are declared as tools;
 * everything that changes the box is declared as a grant, and every one of those grants defers.
 *
 * <p>Feature detection (spec §2.1) is a {@code which} at startup, run once by {@link Detect} before
 * the context exists and published as {@code watchman.detected.*}. A bean gated on a command the
 * host does not have is never created, so the tool never reaches the registry and the model never
 * sees it. That is the honest version of "this agent can restart containers": on a box without
 * docker, it cannot, and it is not told it can.
 */
@Configuration(proxyBeanMethods = false)
public class ToolBeans {

  private static final String DETECTED = Detect.PREFIX;
  private static final String APT_OR_DNF =
      "${" + Detect.PREFIX + "apt:false} or ${" + Detect.PREFIX + "dnf:false}";

  /** The host seam. A test declares its own and this one steps aside. */
  @Bean
  @ConditionalOnMissingBean
  public CommandRunner commandRunner(WatchmanProperties properties) {
    return new ProcessRunner(properties.commandTimeout());
  }

  /** The notes directory, shared by the two tools that read and write it. */
  @Bean
  @ConditionalOnMissingBean
  public Notes notes(WatchmanProperties properties) {
    return new Notes(properties.notesDir(), Clock.systemDefaultZone());
  }

  /**
   * Where {@code long_job}'s watcher reports a finished process. Bound to the desk here and to a
   * recording queue in {@code LongJobTest} — see {@link LongJob} for why this is a {@link
   * BiConsumer} and not the desk itself.
   */
  @Bean
  @ConditionalOnMissingBean
  public BiConsumer<ComputationId, ToolResult> completions(ObjectProvider<CompletionDesk> desks) {
    // The desk is resolved when a job finishes, not when this bean is built. That keeps the tool
    // list independent of auto-configuration ordering — and lets the wiring tests assemble the
    // tools without a harness behind them.
    return (id, result) -> desks.getObject().complete(id, result);
  }

  /**
   * The watcher pool: virtual threads, because every task on it is one blocked {@code waitFor} and
   * nothing else. Deliberately NOT the harness's executor — the whole point of {@code
   * ToolContext.defer()} is that the harness thread is given back before the work starts.
   */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnMissingBean
  public ExecutorService watchers() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  // ---------------------------------------------------------------- read-only tools

  @Bean
  @ConditionalOnProperty(name = DETECTED + "df", havingValue = "true")
  public Tool<DiskUsage.Mounts> diskUsage(CommandRunner runner) {
    return DiskUsage.tool(runner);
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "systemctl", havingValue = "true")
  public Tool<FailedUnits.Failures> failedUnits(CommandRunner runner) {
    return FailedUnits.tool(runner);
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "journalctl", havingValue = "true")
  public Tool<JournalErrors.Window> journalErrors(CommandRunner runner) {
    return JournalErrors.tool(runner);
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "docker", havingValue = "true")
  public Tool<Containers.Inventory> containers(CommandRunner runner) {
    return Containers.tool(runner);
  }

  /**
   * One tool, two possible package managers. {@code apt} wins a tie because a host with both is a
   * Debian box someone installed {@code dnf} on, not the other way around.
   */
  @Bean
  @ConditionalOnExpression(APT_OR_DNF)
  public Tool<UpdatesPending.Upgradable> updatesPending(
      CommandRunner runner, Environment environment) {
    return UpdatesPending.tool(runner, packageManager(environment));
  }

  /**
   * {@code /proc} is not a command, so {@link Detect} answers for it with a readability check
   * instead of a {@code which} — false on macOS and inside some containers, and the tool is then
   * simply not there.
   */
  @Bean
  @ConditionalOnProperty(name = Detect.PROC, havingValue = "true")
  public Tool<UptimeLoad.Health> uptimeLoad() {
    return new UptimeLoad(Path.of("/proc")).tool();
  }

  @Bean
  public Tool<PreviousNotes.Lookback> previousNotes(Notes notes, WatchmanProperties properties) {
    return PreviousNotes.tool(notes, properties.noteHistory());
  }

  @Bean
  public Tool<WriteNote.Note> writeNote(Notes notes) {
    return WriteNote.tool(notes);
  }

  // ---------------------------------------------------------------- remediation grants

  @Bean
  @ConditionalOnProperty(name = DETECTED + "systemctl", havingValue = "true")
  public ToolGrant restartUnit(CommandRunner runner) {
    return RestartUnit.grant(runner);
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "docker", havingValue = "true")
  public ToolGrant restartContainer(CommandRunner runner) {
    return RestartContainer.grant(runner);
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "docker", havingValue = "true")
  public ToolGrant pruneImages(CommandRunner runner) {
    return PruneImages.grant(runner);
  }

  @Bean
  @ConditionalOnExpression(APT_OR_DNF)
  public ToolGrant applyUpdates(CommandRunner runner, Environment environment) {
    return ApplyUpdates.grant(runner, packageManager(environment));
  }

  @Bean
  @ConditionalOnProperty(name = DETECTED + "journalctl", havingValue = "true")
  public ToolGrant cleanJournal(CommandRunner runner) {
    return CleanJournal.grant(runner);
  }

  // ---------------------------------------------------------------- the deferred tool

  @Bean
  @ConditionalOnProperty(name = DETECTED + "fstrim", havingValue = "true")
  public Tool<LongJob.Job> longJob(
      CommandRunner runner,
      BiConsumer<ComputationId, ToolResult> completions,
      ExecutorService watchers) {
    return LongJob.tool(runner, completions, watchers);
  }

  private static PackageManager packageManager(Environment environment) {
    return environment.getProperty(Detect.PREFIX + "apt", Boolean.class, false)
        ? PackageManager.APT
        : PackageManager.DNF;
  }
}
