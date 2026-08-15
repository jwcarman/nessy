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
package org.jwcarman.nessy.jdbc;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/**
 * The five databases {@code nessy-jdbc} speaks, and the resolver that picks one from a live
 * connection — Hibernate's {@code StandardDialectResolver} pattern, borrowed rather than depended
 * on: no new dependency, one small enum, one static method (design §2).
 *
 * <p>{@link #resolve(DatabaseMetaData)} reads {@link DatabaseMetaData#getDatabaseProductName()}
 * once, at the connection a store already borrows to bootstrap its schema (or, for a
 * non-bootstrapping construction, the first connection any real operation borrows) — never per
 * call. Every one of this module's store classes exposes a constructor/{@code create} overload that
 * accepts a {@link JdbcDialect} explicitly, bypassing this resolver entirely for a driver that lies
 * about its own metadata, or a caller that already knows.
 *
 * <p>{@link #POSTGRES} is also where CockroachDB and Yugabyte land: both report {@code PostgreSQL}
 * as their JDBC product name, deliberately, for exactly this kind of wire-compatible detection —
 * this resolver takes them at their word rather than trying to tell them apart from the genuine
 * article.
 */
public enum JdbcDialect {
  POSTGRES,
  MYSQL,
  MARIADB,
  SQLSERVER,
  ORACLE;

  private static final String POSTGRESQL_PRODUCT = "PostgreSQL";
  private static final String MYSQL_PRODUCT = "MySQL";
  private static final String MARIADB_PRODUCT = "MariaDB";
  private static final String SQLSERVER_PRODUCT = "Microsoft SQL Server";
  private static final String ORACLE_PRODUCT = "Oracle";

  /**
   * Resolves the dialect a live connection speaks, from {@link
   * DatabaseMetaData#getDatabaseProductName()} — normalized per the table design §2 spells out:
   *
   * <ul>
   *   <li>{@code "PostgreSQL"} → {@link #POSTGRES} (CockroachDB, Yugabyte included — see the class
   *       javadoc).
   *   <li>{@code "MySQL"} → {@link #MYSQL}, unless {@link
   *       DatabaseMetaData#getDatabaseProductVersion()} contains {@code "MariaDB"} → {@link
   *       #MARIADB} (the MariaDB Connector/J driver reports the MySQL product name for
   *       compatibility but stamps its own name into the version string — the Hibernate sniff).
   *   <li>{@code "MariaDB"} → {@link #MARIADB} directly, for drivers that do not play along with
   *       the compatibility name above.
   *   <li>{@code "Microsoft SQL Server"} → {@link #SQLSERVER}.
   *   <li>{@code "Oracle"} → {@link #ORACLE}.
   *   <li>Anything else fails loudly with an {@link IllegalStateException} naming the reported
   *       product, the five supported dialects, and the explicit-dialect override every store
   *       class's constructor/{@code create} overload accepts (plus, in a Spring Boot app, the
   *       {@code nessy.jdbc.dialect} property).
   * </ul>
   */
  public static JdbcDialect resolve(DatabaseMetaData metaData) throws SQLException {
    Objects.requireNonNull(metaData, "metaData must not be null");
    String product = metaData.getDatabaseProductName();
    if (POSTGRESQL_PRODUCT.equals(product)) {
      return POSTGRES;
    }
    if (MYSQL_PRODUCT.equals(product)) {
      return mysqlOrMariaDb(metaData);
    }
    if (MARIADB_PRODUCT.equals(product)) {
      return MARIADB;
    }
    if (SQLSERVER_PRODUCT.equals(product)) {
      return SQLSERVER;
    }
    if (ORACLE_PRODUCT.equals(product)) {
      return ORACLE;
    }
    throw unrecognized(product);
  }

  private static JdbcDialect mysqlOrMariaDb(DatabaseMetaData metaData) throws SQLException {
    String version = metaData.getDatabaseProductVersion();
    boolean reportsMariaDb =
        version != null && version.toUpperCase(Locale.ROOT).contains("MARIADB");
    return reportsMariaDb ? MARIADB : MYSQL;
  }

  private static IllegalStateException unrecognized(String product) {
    return new IllegalStateException(
        "unrecognized JDBC product \""
            + product
            + "\" — nessy-jdbc supports POSTGRES, MYSQL, MARIADB, SQLSERVER, and ORACLE;"
            + " pass an explicit JdbcDialect to the store's constructor/create overload (or set"
            + " nessy.jdbc.dialect in a Spring Boot application) to override detection for a"
            + " driver that reports something this resolver does not recognize");
  }

  /**
   * The classpath directory the per-dialect schema resources live in, next to each store class —
   * see design §3 (five schema resource sets, one directory per dialect).
   */
  String schemaDirectory() {
    return name().toLowerCase(Locale.ROOT);
  }
}
