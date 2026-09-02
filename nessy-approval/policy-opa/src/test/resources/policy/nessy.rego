# The gate, as data rather than code.
package nessy.tools

import rego.v1

# A decision is a DOCUMENT, not a boolean, because the answer that matters most in this system is
# neither yes nor no: it is "somebody else decides". The default is what makes this both safe and
# diagnosable -- always defined, so a response carrying no result at all means the policy is not
# being reached, which the engine reports as a broken gate rather than a quiet denial.
default decision := {"effect": "deny", "reason": "no rule allowed this"}

read_only := {"disk_usage", "containers", "days_until"}

decision := {"effect": "allow"} if {
	input.toolName in read_only
	not production
}

decision := {"effect": "allow"} if {
	input.toolName == "prune_images"
	input.agentType == "watchman"
	not production
}

decision := {"effect": "deny", "reason": "never in this tenant"} if input.toolName == "rm_rf"

# The rule a boolean could not express: route it to a person, for a term the POLICY names.
decision := {
	"effect": "delegate",
	"to": "humans",
	"term": "PT72H",
	"reason": sprintf("%s targets production", [input.toolName]),
} if production

production if startswith(object.get(input, ["arguments", "target"], ""), "prod-")
