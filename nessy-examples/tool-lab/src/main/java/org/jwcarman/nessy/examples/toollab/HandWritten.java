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
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.examples.toollab.Schemas.Prop;
import tools.jackson.databind.JsonNode;

/**
 * The tools the tasks need, and the near-duplicates that make choosing between them hard. Every
 * catalog holds all of them, first. Their answers chain: a search returns the id the next call
 * needs.
 */
final class HandWritten {

  private static final String NO_INVOICES = "{\"invoices\":[]}";

  private static final List<CatalogTool> TOOLS = build();

  private HandWritten() {}

  /** The hand-written tools, in catalog order. */
  static List<CatalogTool> tools() {
    return TOOLS;
  }

  private static List<CatalogTool> build() {
    return List.of(
        read(
            "stripe_list_invoices",
            "List the invoices of one Stripe customer.",
            Schemas.object(
                Prop.required(
                    "customer_id", "string", "Stripe customer id, which starts with cus_."),
                Prop.optional("status", "string", "Only invoices with this status.")),
            byArgument(
                "customer_id",
                Map.of(
                    "cus_8823",
                    "{\"invoices\":[{\"id\":\"in_1001\",\"status\":\"open\",\"amount_due\":12000},"
                        + "{\"id\":\"in_1002\",\"status\":\"paid\",\"amount_due\":0}]}",
                    "cus_9917",
                    "{\"invoices\":[{\"id\":\"in_2001\",\"status\":\"open\",\"amount_due\":45000}]}",
                    "cus_4410",
                    "{\"invoices\":[{\"id\":\"in_3001\",\"status\":\"past_due\","
                        + "\"amount_due\":9900}]}"),
                NO_INVOICES)),
        read(
            "stripe_search_customers",
            "Find Stripe customers by email address.",
            Schemas.object(Prop.required("email", "string", "The customer's email address.")),
            byArgument(
                "email",
                Map.of(
                    "dana@globex.com",
                    "{\"customers\":[{\"id\":\"cus_9917\",\"email\":\"dana@globex.com\","
                        + "\"name\":\"Dana Whitfield\"}]}",
                    "ravi@initech.io",
                    "{\"customers\":[{\"id\":\"cus_4410\",\"email\":\"ravi@initech.io\","
                        + "\"name\":\"Ravi Menon\"}]}"),
                "{\"customers\":[]}")),
        read(
            "aws_list_regions",
            "List the AWS regions an account has resources in.",
            Schemas.object(Prop.required("account_id", "string", "The AWS account id.")),
            arguments ->
                "{\"account_id\":"
                    + quoted(arguments.path("account_id").asString(""))
                    + ",\"regions\":[\"us-east-1\",\"eu-west-1\",\"ap-southeast-2\"]}"),
        read(
            "salesforce_search_accounts",
            "Search Salesforce accounts (customers and prospects) by name.",
            Schemas.object(Prop.required("name", "string", "Account name or part of it.")),
            byArgument(
                "name",
                Map.of(
                    "acme",
                    "{\"accounts\":[{\"id\":\"001ACME\",\"name\":\"Acme Corp\"}]}",
                    "initech",
                    "{\"accounts\":[{\"id\":\"001INIT\",\"name\":\"Initech\"}]}",
                    "hooli",
                    "{\"accounts\":[{\"id\":\"001HOOLI\",\"name\":\"Hooli\"}]}"),
                "{\"accounts\":[]}")),
        read(
            "salesforce_list_opportunities",
            "List the sales opportunities of one Salesforce account.",
            Schemas.object(
                Prop.required("account_id", "string", "Salesforce account id."),
                Prop.optional("stage", "string", "Only opportunities in this stage.")),
            byArgument(
                "account_id",
                Map.of(
                    "001ACME",
                    "{\"opportunities\":[{\"id\":\"006ACME1\",\"name\":\"Acme Renewal 2027\","
                        + "\"stage\":\"Negotiation\",\"amount\":84000},{\"id\":\"006ACME2\","
                        + "\"name\":\"Acme Expansion\",\"stage\":\"Prospecting\","
                        + "\"amount\":25000}]}",
                    "001INIT",
                    "{\"opportunities\":[{\"id\":\"006INIT1\",\"name\":\"Initech Platform Deal\","
                        + "\"stage\":\"Closed Won\",\"amount\":120000}]}",
                    "001HOOLI",
                    "{\"opportunities\":[{\"id\":\"006HOOLI1\",\"name\":\"Hooli Data Pipeline\","
                        + "\"stage\":\"Closed Won\",\"amount\":310000},{\"id\":\"006HOOLI2\","
                        + "\"name\":\"Hooli Support Upgrade\",\"stage\":\"Qualification\","
                        + "\"amount\":18000}]}"),
                "{\"opportunities\":[]}")),
        write(
            "salesforce_create_account",
            "Create a new account (a customer or prospect) in Salesforce.",
            Schemas.object(
                Prop.required("name", "string", "Name of the new account."),
                Prop.optional("industry", "string", "The account's industry.")),
            arguments -> "{\"id\":\"001NEW01\",\"created\":true}"),
        read(
            "hubspot_search_companies",
            "Search HubSpot companies (customers and prospects) by name.",
            Schemas.object(Prop.required("name", "string", "Company name or part of it.")),
            byArgument(
                "name",
                Map.of(
                    "globex",
                    "{\"companies\":[{\"id\":\"7731\",\"name\":\"Globex Corporation\","
                        + "\"domain\":\"globex.com\"}]}",
                    "umbrella",
                    "{\"companies\":[{\"id\":\"8120\",\"name\":\"Umbrella Group\","
                        + "\"domain\":\"umbrella.example\"}]}"),
                "{\"companies\":[]}")),
        write(
            "hubspot_create_company",
            "Create a new company (a customer or prospect) in HubSpot.",
            Schemas.object(
                Prop.required("name", "string", "Name of the new company."),
                Prop.optional("domain", "string", "The company's web domain.")),
            arguments -> "{\"id\":\"9001\",\"created\":true}"),
        read(
            "jira_search_issues",
            "Search Jira issues by free text.",
            Schemas.object(Prop.required("query", "string", "Free-text search terms.")),
            arguments -> {
              String query = arguments.path("query").asString("").toLowerCase(Locale.ROOT);
              if (query.contains("checkout")) {
                return "{\"issues\":[{\"key\":\"PAY-311\","
                    + "\"summary\":\"Checkout times out under load\"}]}";
              }
              if (query.contains("login")) {
                return "{\"issues\":[{\"key\":\"AUTH-87\","
                    + "\"summary\":\"Login redirect loop on Safari\"}]}";
              }
              if (query.contains("invoice")) {
                return "{\"issues\":[{\"key\":\"BILL-204\","
                    + "\"summary\":\"Invoice PDF shows the wrong tax rate\"}]}";
              }
              return "{\"issues\":[]}";
            }),
        read(
            "jira_get_issue",
            "Get one Jira issue by its key.",
            Schemas.object(Prop.required("issue_key", "string", "The issue key, like PAY-311.")),
            byArgument(
                "issue_key",
                Map.of(
                    "PAY-311",
                    "{\"key\":\"PAY-311\",\"summary\":\"Checkout times out under load\","
                        + "\"status\":\"In Progress\",\"assignee\":\"mlopez\"}",
                    "AUTH-87",
                    "{\"key\":\"AUTH-87\",\"summary\":\"Login redirect loop on Safari\","
                        + "\"status\":\"Open\",\"assignee\":\"tkhan\"}",
                    "BILL-204",
                    "{\"key\":\"BILL-204\",\"summary\":\"Invoice PDF shows the wrong tax rate\","
                        + "\"status\":\"Open\",\"assignee\":\"jpark\"}"),
                "{\"error\":\"issue not found\"}")),
        write(
            "jira_create_issue",
            "Create a new issue in a Jira project.",
            Schemas.object(
                Prop.required("project", "string", "Jira project key, like OPS."),
                Prop.required("summary", "string", "One-line summary of the issue."),
                Prop.optional("description", "string", "Longer description.")),
            arguments ->
                "{\"key\":"
                    + quoted(arguments.path("project").asString("") + "-901")
                    + ",\"created\":true}"),
        write(
            "jira_add_comment",
            "Add a comment to a Jira issue.",
            Schemas.object(
                Prop.required("issue_key", "string", "The issue key, like PAY-311."),
                Prop.required("body", "string", "The comment text.")),
            arguments -> "{\"comment_id\":\"10431\",\"added\":true}"),
        write(
            "linear_create_issue",
            "Create a new issue in a Linear team.",
            Schemas.object(
                Prop.required("team", "string", "Linear team key, like PLAT."),
                Prop.required("title", "string", "Title of the issue."),
                Prop.optional("description", "string", "Longer description.")),
            arguments ->
                "{\"identifier\":"
                    + quoted(arguments.path("team").asString("") + "-77")
                    + ",\"created\":true}"),
        read(
            "linear_get_issue",
            "Get one Linear issue by its identifier.",
            Schemas.object(
                Prop.required("identifier", "string", "The issue identifier, like ENG-52.")),
            byArgument(
                "identifier",
                Map.of(
                    "ENG-52",
                    "{\"identifier\":\"ENG-52\",\"title\":\"Migrate the build to Gradle 9\","
                        + "\"state\":\"In Review\",\"assignee\":\"rchen\"}"),
                "{\"error\":\"issue not found\"}")),
        read(
            "github_list_pull_requests",
            "List the pull requests of a GitHub repository.",
            Schemas.object(
                Prop.required("repo", "string", "Repository as owner/name."),
                Prop.optional("state", "string", "open, closed or all.")),
            byArgument(
                "repo",
                Map.of(
                    "acme/api",
                    "{\"pull_requests\":[{\"number\":412,\"title\":\"Add retry to webhook sender\","
                        + "\"state\":\"open\"},{\"number\":418,\"title\":\"Bump jackson to 3.2.3\","
                        + "\"state\":\"open\"}]}",
                    "acme/web",
                    "{\"pull_requests\":[{\"number\":77,"
                        + "\"title\":\"Fix dark mode contrast on settings page\","
                        + "\"state\":\"open\"}]}",
                    "acme/mobile",
                    "{\"pull_requests\":[{\"number\":203,\"title\":\"Crash on rotate in checkout\","
                        + "\"state\":\"open\"}]}"),
                "{\"pull_requests\":[]}")),
        read(
            "github_get_pull_request",
            "Get one GitHub pull request by repository and number.",
            Schemas.object(
                Prop.required("repo", "string", "Repository as owner/name."),
                Prop.required("number", "integer", "The pull request number.")),
            arguments -> {
              String key =
                  arguments.path("repo").asString("") + "#" + arguments.path("number").asString("");
              return switch (key) {
                case "acme/api#412" ->
                    "{\"number\":412,\"title\":\"Add retry to webhook sender\","
                        + "\"author\":\"mlopez\",\"state\":\"open\",\"checks\":\"passing\"}";
                case "acme/api#418" ->
                    "{\"number\":418,\"title\":\"Bump jackson to 3.2.3\","
                        + "\"author\":\"dependabot\",\"state\":\"open\",\"checks\":\"failing\"}";
                case "acme/web#77" ->
                    "{\"number\":77,\"title\":\"Fix dark mode contrast on settings page\","
                        + "\"author\":\"tkhan\",\"state\":\"open\",\"checks\":\"passing\"}";
                case "acme/mobile#203" ->
                    "{\"number\":203,\"title\":\"Crash on rotate in checkout\","
                        + "\"author\":\"jpark\",\"state\":\"open\",\"checks\":\"pending\"}";
                default -> "{\"error\":\"pull request not found\"}";
              };
            }),
        read(
            "pagerduty_list_incidents",
            "List PagerDuty incidents with a given status.",
            Schemas.object(
                Prop.required("status", "string", "triggered, acknowledged or resolved.")),
            byArgument(
                "status",
                Map.of(
                    "triggered",
                    "{\"incidents\":[{\"id\":\"P4XQ2\",\"title\":\"Checkout API 5xx spike\","
                        + "\"status\":\"triggered\"}]}",
                    "acknowledged",
                    "{\"incidents\":[{\"id\":\"PM77L\",\"title\":\"Search latency is high\","
                        + "\"status\":\"acknowledged\"}]}",
                    "resolved",
                    "{\"incidents\":[{\"id\":\"PZ91K\",\"title\":\"Disk full on batch host\","
                        + "\"status\":\"resolved\"}]}"),
                "{\"incidents\":[]}")),
        write(
            "pagerduty_acknowledge_incident",
            "Acknowledge a triggered PagerDuty incident.",
            Schemas.object(Prop.required("incident_id", "string", "The PagerDuty incident id.")),
            arguments ->
                "{\"id\":"
                    + quoted(arguments.path("incident_id").asString(""))
                    + ",\"status\":\"acknowledged\"}"),
        write(
            "slack_post_message",
            "Post a message to a Slack channel.",
            Schemas.object(
                Prop.required("channel", "string", "Slack channel id or name."),
                Prop.required("text", "string", "The message text.")),
            arguments ->
                "{\"ok\":true,\"channel\":"
                    + quoted(arguments.path("channel").asString(""))
                    + ",\"ts\":\"1719500000.000100\"}"),
        read(
            "okta_get_user",
            "Get an Okta user, with account status and groups, by login.",
            Schemas.object(Prop.required("login", "string", "The user's login, an email address.")),
            byArgument(
                "login",
                Map.of(
                    "priya@acme.com",
                    "{\"id\":\"00u7PRIYA\",\"login\":\"priya@acme.com\",\"status\":\"ACTIVE\","
                        + "\"groups\":[\"eng\",\"oncall\"]}",
                    "sam@acme.com",
                    "{\"id\":\"00u3SAM\",\"login\":\"sam@acme.com\",\"status\":\"SUSPENDED\","
                        + "\"groups\":[\"finance\"]}"),
                "{\"error\":\"user not found\"}")));
  }

  private static CatalogTool read(
      String name, String description, JsonSchema schema, Function<JsonNode, String> stub) {
    return new CatalogTool(name, description, schema, false, stub);
  }

  private static CatalogTool write(
      String name, String description, JsonSchema schema, Function<JsonNode, String> stub) {
    return new CatalogTool(name, description, schema, true, stub);
  }

  /** An answer chosen by one argument, compared without regard to case. */
  private static Function<JsonNode, String> byArgument(
      String argument, Map<String, String> answers, String otherwise) {
    return arguments -> {
      String value = arguments.path(argument).asString("");
      for (Map.Entry<String, String> answer : answers.entrySet()) {
        if (answer.getKey().equalsIgnoreCase(value.strip())) {
          return answer.getValue();
        }
      }
      return otherwise;
    };
  }

  private static String quoted(String text) {
    return Schemas.MAPPER.writeValueAsString(text);
  }
}
