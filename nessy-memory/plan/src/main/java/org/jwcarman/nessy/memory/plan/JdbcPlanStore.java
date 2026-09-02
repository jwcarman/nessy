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
package org.jwcarman.nessy.memory.plan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A plan in a table of its own, one row per task.
 *
 * <p><b>Saving is still wholesale</b>, which is the property the design rests on: the tool sends
 * the entire list every time, so a durable re-drive that executes the same call twice stores the
 * identical plan. Here that means deleting this agent's rows and inserting the new list, in ONE
 * transaction — otherwise a crash between the two leaves an agent with no plan at all, which is a
 * worse answer than either the old plan or the new one.
 *
 * <p>Rows rather than a blob so the order the model sent is a column rather than a list's
 * incidental order, and so a plan can be read in a database without a running JVM.
 */
public final class JdbcPlanStore implements PlanStore {

  private static final String SELECT =
      "SELECT title, status FROM nessy_plan_task "
          + "WHERE agent_type = ? AND agent_id = ? ORDER BY ordinal";
  private static final String DELETE =
      "DELETE FROM nessy_plan_task WHERE agent_type = ? AND agent_id = ?";
  private static final String INSERT =
      "INSERT INTO nessy_plan_task (agent_type, agent_id, ordinal, title, status) "
          + "VALUES (?, ?, ?, ?, ?)";

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final String agentType;

  public JdbcPlanStore(DataSource dataSource, AgentType agentType) {
    Objects.requireNonNull(dataSource, "dataSource must not be null");
    this.jdbc = JdbcClient.create(dataSource);
    this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    this.agentType = Objects.requireNonNull(agentType, "agentType must not be null").name();
  }

  @Override
  public Optional<Plan> find(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    List<Plan.Task> tasks =
        jdbc.sql(SELECT)
            .params(agentType, agentId.value())
            .query(
                (row, number) ->
                    new Plan.Task(
                        row.getString("title"), Plan.Status.valueOf(row.getString("status"))))
            .list();
    // An empty plan reads as NO plan: "cleared" and "never written" are one state, and nothing
    // downstream can tell them apart anyway.
    return tasks.isEmpty() ? Optional.empty() : Optional.of(new Plan(tasks));
  }

  @Override
  public void save(AgentId agentId, Plan plan) {
    Objects.requireNonNull(agentId, "agentId must not be null");
    Objects.requireNonNull(plan, "plan must not be null");
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql(DELETE).params(agentType, agentId.value()).update();
          List<Plan.Task> tasks = plan.tasks();
          for (int ordinal = 0; ordinal < tasks.size(); ordinal++) {
            Plan.Task task = tasks.get(ordinal);
            jdbc.sql(INSERT)
                .params(agentType, agentId.value(), ordinal, task.title(), task.status().name())
                .update();
          }
        });
  }
}
