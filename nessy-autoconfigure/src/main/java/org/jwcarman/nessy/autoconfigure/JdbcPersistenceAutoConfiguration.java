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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.jwcarman.nessy.spi.conversation.ConversationStore;
import org.jwcarman.nessy.spi.memory.Memory;
import org.jwcarman.nessy.store.jdbc.JdbcConversationStore;
import org.jwcarman.nessy.store.jdbc.JdbcMemory;
import org.jwcarman.nessy.store.jdbc.JdbcPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * A {@link DataSource} bean plus {@code nessy-store-jdbc} on the classpath is the whole opt-in: the
 * app becomes durable the moment both are true, no other wiring required (design §3). Add the jar
 * next to a datasource and sessions survive a restart; leave either one out and nothing here
 * activates.
 *
 * <p>{@code nessy.jdbc.enabled=false} is the master switch, overriding both signals above. Absent
 * that override, each bean method still yields to a user-declared {@link ConversationStore} or
 * {@link Memory} — see the individual {@code @ConditionalOnMissingBean} bean methods.
 *
 * <p>{@link org.jwcarman.nessy.autoconfigure.NessyProperties#bootstrapSchema()} chooses between
 * {@code JdbcConversationStore}/{@code JdbcMemory}'s bootstrapping {@code create} factories (the
 * default: {@code CREATE TABLE IF NOT EXISTS} run once at startup) and their public constructors,
 * which skip DDL entirely for a datasource another process already bootstrapped.
 *
 * <p>An {@link ObjectMapper} bean is an {@link ObjectProvider}, not a hard constructor parameter:
 * unlike {@link NessyAutoConfiguration}, which only ever runs in a webmvc app where Boot's own
 * Jackson autoconfiguration has already put an {@code ObjectMapper} in context, this configuration
 * has no such guarantee — a non-web Boot application with {@code store-jdbc} and a {@link
 * DataSource} but no Jackson autoconfiguration would otherwise fail with {@code
 * NoSuchBeanDefinitionException} the moment either bean method resolved its parameter. The
 * fallback, a bare {@code new ObjectMapper()}, is safe precisely because {@code JdbcPersistence}'s
 * codec (see {@code StateCodec}) never uses the mapper it is handed as-is: it registers its own
 * sealed-type mixins on a private {@link ObjectMapper#copy() copy}, so a plain, unconfigured mapper
 * here loses nothing the wire format needs.
 */
@AutoConfiguration
@ConditionalOnClass(JdbcPersistence.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(
    name = JdbcProperties.JDBC_ENABLED_PROPERTY,
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(NessyProperties.class)
public class JdbcPersistenceAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  ConversationStore conversationStore(
      DataSource dataSource, ObjectProvider<ObjectMapper> mapper, NessyProperties properties) {
    return build(
        properties.bootstrapSchema(),
        dataSource,
        resolveMapper(mapper),
        JdbcConversationStore::create,
        JdbcConversationStore::new);
  }

  @Bean
  @ConditionalOnMissingBean
  Memory memory(
      DataSource dataSource, ObjectProvider<ObjectMapper> mapper, NessyProperties properties) {
    return build(
        properties.bootstrapSchema(),
        dataSource,
        resolveMapper(mapper),
        JdbcMemory::create,
        JdbcMemory::new);
  }

  private static ObjectMapper resolveMapper(ObjectProvider<ObjectMapper> mapper) {
    return mapper.getIfAvailable(ObjectMapper::new);
  }

  /**
   * Shared by both bean methods: {@code bootstrapSchema} picks the bootstrapping factory (DDL run
   * once, safe to repeat) or the bare constructor (no DDL, no connection opened at all) — the one
   * branch point either bean's construction needs.
   */
  private static <T> T build(
      boolean bootstrapSchema,
      DataSource dataSource,
      ObjectMapper mapper,
      BiFunction<DataSource, ObjectMapper, T> factory,
      BiFunction<DataSource, ObjectMapper, T> constructor) {
    return bootstrapSchema
        ? factory.apply(dataSource, mapper)
        : constructor.apply(dataSource, mapper);
  }
}
