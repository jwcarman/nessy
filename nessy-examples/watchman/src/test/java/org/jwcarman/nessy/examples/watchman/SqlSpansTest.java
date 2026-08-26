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

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import net.ttddyy.observation.tracing.JdbcObservationDocumentation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * SQL spans (soak finding F5, 2026-08-26). Spring Boot instruments no JDBC on its own — the built
 * application carried 132 jars and not one of them was a JDBC instrumentation — so {@code
 * search_memory} and {@code create_memory} arrived in Tempo as leaves, and "is recall slow, or is
 * the transcript huge?" had no answer in the trace at all. {@code datasource-micrometer} wraps the
 * {@code DataSource} and records each query as an Observation, which nests under whichever
 * observation is open around it.
 *
 * <p>This proves both halves without a collector: a query records an observation, and that
 * observation descends from whichever one the caller had open — which is what makes it part of the
 * memory span's subtree in a real round rather than another root.
 */
@SpringBootTest(
    classes = {WatchmanApplication.class, SqlSpansTest.Host.class},
    properties = {
      "spring.main.allow-bean-definition-overriding=true",
      "watchman.scheduling.enabled=false",
      "watchman.user=ops",
      "watchman.password=lan-only",
      "watchman.notes-dir=target/sql-span-test-notes"
    })
@ActiveProfiles("scripted")
@Tag("container")
class SqlSpansTest {

  /** The observation {@code datasource-micrometer} records per query. */
  private static final String QUERY = JdbcObservationDocumentation.QUERY.getName();

  @Autowired private DataSource dataSource;

  @Autowired private ObservationRegistry observations;

  private final List<Observation.Context> stopped = new ArrayList<>();

  @BeforeEach
  void captureStoppedObservations() {
    observations.observationConfig().observationHandler(new Captor(stopped));
  }

  @Test
  void a_query_through_the_applications_datasource_records_its_own_observation() {
    new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class);

    assertThat(stopped).isNotEmpty();
    assertThat(stopped).anySatisfy(context -> assertThat(context.getName()).isEqualTo(QUERY));
  }

  /**
   * The half that makes it useful: run the same query inside an enclosing observation — which is
   * what {@code search_memory} and {@code create_memory} are around every store call — and the
   * query descends from it rather than starting a trace of its own. Not a direct child: the library
   * nests {@code jdbc.query} under a {@code jdbc.connection} of its own, which is the shape a
   * reader wants anyway (connection acquisition and query time told apart). So the claim is about
   * the ANCESTRY, walked to its root.
   */
  @Test
  void a_query_run_inside_a_memory_observation_descends_from_it() {
    Observation memory =
        Observation.createNotStarted("search_memory", observations).contextualName("search_memory");

    memory.observe(() -> new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class));

    List<Observation.Context> queries =
        stopped.stream().filter(context -> QUERY.equals(context.getName())).toList();
    assertThat(queries).isNotEmpty();
    assertThat(queries)
        .allSatisfy(context -> assertThat(rootNameOf(context)).isEqualTo("search_memory"));
  }

  /** The name of the outermost observation {@code context} descends from. */
  private static String rootNameOf(Observation.Context context) {
    Observation.ContextView root = context;
    while (root.getParentObservation() != null) {
      root = root.getParentObservation().getContextView();
    }
    return root.getName();
  }

  /** A hand-written handler, because there is no mocking library here (design of record). */
  private record Captor(List<Observation.Context> stopped)
      implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return true;
    }

    @Override
    public void onStop(Observation.Context context) {
      stopped.add(context);
    }
  }

  /** A real database, because a query needs one; nothing here touches the host. */
  @TestConfiguration(proxyBeanMethods = false)
  static class Host {

    @Bean
    DataSource dataSource() {
      return WatchmanPostgres.dataSource();
    }

    @Bean
    CommandRunner commandRunner() {
      return new FakeRunner();
    }
  }
}
