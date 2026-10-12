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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jwcarman.nessy.api.JsonSchema;
import org.jwcarman.nessy.examples.toollab.Schemas.Prop;
import tools.jackson.databind.JsonNode;

/**
 * The generated part of the catalog: systems times entities times verbs, interleaved so that a
 * small catalog touches many systems rather than all the verbs of one.
 */
final class Generated {

  /** A system and the kinds of thing it holds, as {@code system:entity,entity,...}. */
  private static final List<String> SYSTEMS =
      List.of(
          "jira:issue,project,sprint,board,comment,user",
          "linear:issue,project,cycle,team,label,comment",
          "github:repository,pull_request,issue,release,branch,workflow_run",
          "gitlab:project,merge_request,pipeline,issue,runner,branch",
          "salesforce:account,contact,lead,opportunity,case,campaign",
          "hubspot:contact,company,deal,ticket,email,pipeline",
          "stripe:customer,invoice,subscription,charge,refund,payout",
          "zendesk:ticket,user,organization,macro,view,article",
          "pagerduty:incident,service,schedule,escalation_policy,user,team",
          "datadog:monitor,dashboard,metric,log_index,downtime,synthetic_test",
          "aws:ec2_instance,s3_bucket,lambda_function,iam_role,rds_instance,cloudwatch_alarm",
          "notion:page,database,block,comment,user,template",
          "confluence:page,space,comment,attachment,label,user",
          "slack:channel,message,user,reminder,file,usergroup",
          "gdrive:file,folder,permission,comment,drive,revision",
          "servicenow:incident,change_request,problem,catalog_item,user,cmdb_item",
          "okta:user,group,application,policy,factor,session",
          "workday:employee,position,time_off_request,job_requisition,payslip,org_unit",
          "postgres:table,database,role,index,extension,backup",
          "snowflake:warehouse,database,schema,query,role,share",
          "sentry:issue,project,release,alert,team,event",
          "bitbucket:repository,pull_request,pipeline,branch,commit,webhook",
          "asana:task,project,section,tag,user,goal",
          "trello:card,board,list,label,member,checklist",
          "intercom:conversation,contact,company,article,tag,segment",
          "twilio:message,call,phone_number,verification,subaccount,recording",
          "sendgrid:email,template,contact_list,suppression,sender,campaign",
          "mailchimp:campaign,audience,member,template,segment,report",
          "shopify:order,product,customer,collection,discount,fulfillment",
          "quickbooks:invoice,bill,customer,vendor,payment,account",
          "docusign:envelope,template,recipient,folder,document,user",
          "box:file,folder,collaboration,comment,task,webhook",
          "dropbox:file,folder,shared_link,team_member,paper_doc,namespace",
          "zoom:meeting,webinar,recording,user,room,report",
          "msteams:team,channel,message,meeting,user,tab",
          "splunk:search,index,alert,dashboard,saved_search,lookup",
          "newrelic:application,alert_policy,dashboard,synthetic_monitor,deployment,entity",
          "grafana:dashboard,datasource,alert_rule,folder,annotation,team",
          "kubernetes:pod,deployment,service,namespace,config_map,node",
          "terraform:workspace,run,variable,state_version,policy,module",
          "vault:secret,policy,token,auth_method,mount,lease",
          "circleci:pipeline,job,workflow,project,context,schedule",
          "jenkins:job,build,node,credential,view,plugin",
          "azure:virtual_machine,storage_account,resource_group,app_service,key_vault,subscription",
          "gcp:compute_instance,bucket,cloud_function,service_account,project,firewall_rule",
          "mongodb:collection,database,index,user,cluster,backup",
          "bigquery:dataset,table,job,routine,model,reservation",
          "redshift:cluster,table,query,snapshot,user,workgroup",
          "tableau:workbook,view,datasource,project,user,subscription",
          "looker:dashboard,look,query,explore,user,schedule",
          "segment:source,destination,tracking_plan,workspace,audience,function",
          "mixpanel:event,cohort,funnel,report,user,annotation",
          "amplitude:event,cohort,chart,dashboard,user,experiment",
          "figma:file,project,comment,component,team,version",
          "airtable:base,table,record,view,webhook,field",
          "monday:board,item,group,column,user,update",
          "clickup:task,list,space,folder,goal,time_entry",
          "freshdesk:ticket,contact,company,agent,solution_article,group",
          "greenhouse:candidate,job,application,offer,interview,scorecard",
          "bamboohr:employee,time_off_request,report,benefit,job_opening,file",
          "docker:image,container,volume,network,registry,repository",
          "cloudflare:zone,dns_record,worker,firewall_rule,certificate,tunnel",
          "snyk:project,issue,organization,policy,integration,report",
          "sonarcloud:project,issue,quality_gate,rule,analysis,user",
          "launchdarkly:flag,project,environment,segment,experiment,member",
          "opsgenie:alert,schedule,team,integration,heartbeat,policy",
          "statuspage:incident,component,subscriber,metric,page,template");

  private static final List<String> VERBS =
      List.of("list", "get", "search", "create", "update", "delete");

  /** Verbs only the first two kinds of thing in each system have. */
  private static final List<String> EXTRA_VERBS = List.of("archive", "assign", "export");

  private static final Set<String> WRITES =
      Set.of("create", "update", "delete", "archive", "assign");

  private static final List<CatalogTool> TOOLS = build();

  private Generated() {}

  /** Every generated tool, in the fixed order the catalog takes them in. */
  static List<CatalogTool> tools() {
    return TOOLS;
  }

  private static List<CatalogTool> build() {
    List<List<CatalogTool>> perSystem = new ArrayList<>();
    Set<String> taken = new HashSet<>();
    for (CatalogTool hand : HandWritten.tools()) {
      taken.add(hand.name());
    }
    for (String system : SYSTEMS) {
      String id = system.substring(0, system.indexOf(':'));
      String[] entities = system.substring(system.indexOf(':') + 1).split(",");
      List<CatalogTool> own = new ArrayList<>();
      for (int e = 0; e < entities.length; e++) {
        List<String> verbs = new ArrayList<>(VERBS);
        if (e < 2) {
          verbs.addAll(EXTRA_VERBS);
        }
        for (String verb : verbs) {
          CatalogTool tool = tool(id, entities[e], verb);
          if (taken.add(tool.name())) {
            own.add(tool);
          }
        }
      }
      perSystem.add(own);
    }
    List<CatalogTool> interleaved = new ArrayList<>();
    for (int round = 0; ; round++) {
      boolean any = false;
      for (List<CatalogTool> own : perSystem) {
        if (round < own.size()) {
          interleaved.add(own.get(round));
          any = true;
        }
      }
      if (!any) {
        return List.copyOf(interleaved);
      }
    }
  }

  private static CatalogTool tool(String system, String entity, String verb) {
    boolean plural = verb.equals("list") || verb.equals("search") || verb.equals("export");
    String name = system + "_" + verb + "_" + (plural ? plural(entity) : entity);
    String display = system.substring(0, 1).toUpperCase(Locale.ROOT) + system.substring(1);
    String thing = entity.replace('_', ' ');
    String things = plural(entity).replace('_', ' ');
    boolean listing = verb.equals("list") || verb.equals("search") || verb.equals("export");
    return new CatalogTool(
        name,
        describe(verb, display, thing, things),
        schema(verb, display, thing),
        WRITES.contains(verb),
        listing ? arguments -> listed(system) : arguments -> single(system, arguments));
  }

  private static String describe(String verb, String display, String thing, String things) {
    return switch (verb) {
      case "list" ->
          "List %s in %s, optionally limited to one workspace.".formatted(things, display);
      case "get" -> "Get one %s from %s by its id.".formatted(thing, display);
      case "search" -> "Search %s %s by free text.".formatted(display, things);
      case "create" -> "Create a new %s in %s.".formatted(thing, display);
      case "update" -> "Update fields of an existing %s in %s.".formatted(thing, display);
      case "delete" -> "Permanently delete a %s from %s.".formatted(thing, display);
      case "archive" ->
          "Archive a %s in %s so it no longer appears in active views.".formatted(thing, display);
      case "assign" -> "Assign a %s in %s to a user or team.".formatted(thing, display);
      default -> "Export %s from %s as a downloadable file.".formatted(things, display);
    };
  }

  private static JsonSchema schema(String verb, String display, String thing) {
    return switch (verb) {
      case "list" ->
          Schemas.object(
              Prop.required(
                  "workspace", "string", "The %s workspace or account.".formatted(display)),
              Prop.optional("limit", "integer", "Maximum number of results to return."));
      case "search" ->
          Schemas.object(
              Prop.required("query", "string", "Free-text search terms."),
              Prop.optional("limit", "integer", "Maximum number of results to return."));
      case "get" ->
          Schemas.object(Prop.required("id", "string", "Identifier of the %s.".formatted(thing)));
      case "create" ->
          Schemas.object(
              Prop.required("name", "string", "Name or title of the new %s.".formatted(thing)),
              Prop.optional(
                  "description", "string", "Longer description of the new %s.".formatted(thing)));
      case "update" ->
          Schemas.object(
              Prop.required("id", "string", "Identifier of the %s.".formatted(thing)),
              Prop.required("changes", "string", "JSON object of the fields to change."));
      case "delete" ->
          Schemas.object(
              Prop.required("id", "string", "Identifier of the %s.".formatted(thing)),
              Prop.optional("reason", "string", "Why it is being deleted."));
      case "archive" ->
          Schemas.object(Prop.required("id", "string", "Identifier of the %s.".formatted(thing)));
      case "assign" ->
          Schemas.object(
              Prop.required("id", "string", "Identifier of the %s.".formatted(thing)),
              Prop.required("assignee", "string", "User or team to assign it to."));
      default ->
          Schemas.object(
              Prop.required(
                  "workspace", "string", "The %s workspace or account.".formatted(display)),
              Prop.optional("format", "string", "Either csv or json."));
    };
  }

  private static String listed(String system) {
    return "{\"ok\":true,\"system\":\"" + system + "\",\"items\":[]}";
  }

  private static String single(String system, JsonNode arguments) {
    String id = arguments.path("id").asString("");
    return "{\"ok\":true,\"system\":\""
        + system
        + "\",\"id\":"
        + Schemas.MAPPER.writeValueAsString(id)
        + "}";
  }

  /** The plural of a catalog entity name: enough English for the names above. */
  static String plural(String entity) {
    if (entity.endsWith("y")) {
      return entity.substring(0, entity.length() - 1) + "ies";
    }
    if (entity.endsWith("s") || entity.endsWith("x")) {
      return entity + "es";
    }
    return entity + "s";
  }
}
