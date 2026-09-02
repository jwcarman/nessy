# The gate, as data rather than code.
#
# Every rule below judges the JSON document an ApprovalRequest becomes. Nothing here knows Java
# exists, and changing any of it is a policy review rather than a release.
package nessy.tools

import rego.v1

# Rego's default answer to "may I" is no. Every rule that follows has to earn a yes, and a request
# matching nothing at all is denied by this line rather than by an oversight.
default allow := false

# --- What anyone may do ------------------------------------------------------------------------

# Reading is not acting. These report and change nothing, so they never wait on a person.
read_only := {"disk_usage", "containers", "days_until"}

allow if input.toolName in read_only

# --- What only some agents may do --------------------------------------------------------------

# Reclaiming disk is destructive but recoverable, and it is the watchman's job. Another agent type
# asking for it is a sign something is wrong, not a request to grant.
allow if {
	input.toolName == "prune_images"
	input.agentType == "watchman"
	not production
}

# --- What nobody may do without a person -------------------------------------------------------

# Production is the line. The rule reads the ARGUMENTS the tool would actually run with, which is
# the whole reason the request carries them: "restart a host" is not a decision anyone can make,
# but "restart prod-eu-1" is.
production if startswith(input.arguments.target, "prod-")

reason := "targets production; a person has to say yes" if {
	production
}

reason := sprintf("%s is not a tool %s may call", [input.toolName, input.agentType]) if {
	not production
	not allow
}
