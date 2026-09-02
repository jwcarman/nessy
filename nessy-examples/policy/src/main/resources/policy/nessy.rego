# The gate, as data rather than code.
#
# Every rule below judges the JSON document an ApprovalRequest becomes. Nothing here knows Java
# exists, and changing any of it is a policy review rather than a release.
package nessy.tools

import rego.v1

# A decision is a DOCUMENT, not a boolean, because the answer that matters most in this system is
# neither yes nor no: it is "ask a person". `effect` is one of allow, deny, ask.
#
# The default is what makes this safe AND diagnosable. Rego's own answer to "may I" is no, so a
# request matching nothing is denied by this line rather than by an oversight -- and because the
# rule is always defined, a response carrying no result at all means the policy is not being
# reached, which the approver reports as a misconfiguration instead of a quiet denial.
default decision := {"effect": "deny", "reason": "no rule allowed this"}

# --- What anyone may do ------------------------------------------------------------------------

# Reading is not acting. These report and change nothing, so they never wait on a person.
read_only := {"disk_usage", "containers", "days_until"}

decision := {"effect": "allow"} if {
	input.toolName in read_only
	not production
}

# --- What only some agents may do --------------------------------------------------------------

# Reclaiming disk is destructive but recoverable, and it is the watchman's job. Another agent type
# asking for it is a sign something is wrong, not a request to grant.
decision := {"effect": "allow"} if {
	input.toolName == "prune_images"
	input.agentType == "watchman"
	not production
}

# --- What nobody may do without a person -------------------------------------------------------

# Production is the line, and this is the rule a boolean could not express. The check reads the
# ARGUMENTS the tool would actually run with, which is why the request carries them: "restart a
# host" is not a decision anyone can make, but "restart prod-eu-1" is.
#
# `term` is how long the person has. That "production waits three days" is a sentence in the
# policy rather than a constant in Java is the point of putting the gate here.
decision := {
	"effect": "ask",
	"reason": sprintf("%s targets production", [input.toolName]),
	"term": "PT72H",
} if production

production if startswith(object.get(input, ["arguments", "target"], ""), "prod-")
