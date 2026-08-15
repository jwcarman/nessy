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
package org.jwcarman.nessy.autoconfigure;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jwcarman.nessy.spi.memory.Transcript;
import org.jwcarman.nessy.store.cassandra.CassandraTranscript;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.cassandra.autoconfigure.CassandraAutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The starter's arbitration seam (design §4): a {@link CqlSession} bean plus {@code
 * nessy-store-cassandra} on the classpath wins the {@link Transcript} bean over {@code
 * nessy-store-jdbc}'s own — this class is pinned {@code before =
 * JdbcPersistenceAutoConfiguration.class} so its {@link Transcript} bean always lands first, and
 * {@link JdbcPersistenceAutoConfiguration}'s own {@code @ConditionalOnMissingBean} {@code
 * Transcript} bean method backs off by the rule it already lives by. {@link
 * JdbcPersistenceAutoConfiguration}'s {@code Memory} bean composes over whichever {@code
 * Transcript} won, so the JDBC {@code ConversationStore} and {@code Parks} doors stay exactly as
 * they are today — only the transcript itself moves to Cassandra. No edit to the JDBC
 * auto-configuration was needed to make this work.
 *
 * <p>{@code nessy.cassandra.enabled=false} is the master switch, the Cassandra sibling of {@link
 * JdbcProperties#JDBC_ENABLED_PROPERTY}. Absent that override, the bean method still yields to a
 * user-declared {@link Transcript} — see {@link #transcript}'s own
 * {@code @ConditionalOnMissingBean}.
 *
 * <p>{@link CassandraTranscript#create(CqlSession, ObjectMapper)} bootstraps {@code
 * nessy_transcript} every time (design §4 names no bootstrap-schema toggle for Cassandra, unlike
 * the JDBC side) — its {@code CREATE TABLE IF NOT EXISTS} is safe to run more than once.
 *
 * <p>{@code CqlSession} itself arrives from Boot's own Cassandra auto-configuration (service
 * connections included) — this configuration adds no session configuration of its own, mirroring
 * {@link JdbcPersistenceAutoConfiguration}'s relationship to {@code DataSource}.
 *
 * <p>An {@link ObjectMapper} bean is an {@link ObjectProvider}, not a hard constructor parameter,
 * for the same non-web-app reason {@link JdbcPersistenceAutoConfiguration}'s javadoc explains: no
 * Boot application is guaranteed to carry Jackson's own auto-configuration.
 *
 * <p>Pinned after {@link CassandraAutoConfiguration} because
 * {@code @ConditionalOnBean(CqlSession.class)} is only reliable once Boot's own Cassandra
 * auto-configuration has run — without the pin, {@code CqlSession}-free evaluation order would
 * silently drop Cassandra persistence, the same reliability gap {@link
 * JdbcPersistenceAutoConfiguration} solves with its own {@code after = DataSourceAutoConfiguration}
 * pin.
 */
@AutoConfiguration(
    after = CassandraAutoConfiguration.class,
    before = JdbcPersistenceAutoConfiguration.class)
@ConditionalOnClass({CassandraTranscript.class, CqlSession.class})
@ConditionalOnBean(CqlSession.class)
@ConditionalOnProperty(
    name = CassandraProperties.CASSANDRA_ENABLED_PROPERTY,
    havingValue = "true",
    matchIfMissing = true)
public class CassandraTranscriptAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  Transcript transcript(CqlSession session, ObjectProvider<ObjectMapper> mapper) {
    return CassandraTranscript.create(session, mapper.getIfAvailable(ObjectMapper::new));
  }
}
