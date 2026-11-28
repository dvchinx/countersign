# Helpers for the banking-agent demo, used to record docs/images/banking-demo.gif. Source it, don't run it:
#
#   cd examples/banking-agent && source demo/demo.sh
#   demo_start                                       # starts the app in the background
#   ask "Move \$5,000 from checking to ACME Corp."   # Alice talks to the agent
#   approve ops@bank.example                         # someone else approves
#   ask 'Open a support ticket: card 4111 1111 1111 1111 was charged twice'
#   helpdesk                                         # what the outside helpdesk received
#   receipts && verify
#   demo_stop
#
# Needs curl and jq, and the modules installed once: ./mvnw -pl examples/banking-agent -am install -DskipTests
# The app listens on $DEMO_PORT (default 18080) so it does not clash with anything on 8080.

DEMO_PORT="${DEMO_PORT:-18080}"
DEMO_URL="http://localhost:${DEMO_PORT}"
DEMO_LOG="${TMPDIR:-/tmp}/countersign-banking-demo.log"
_demo_reply=""

_dim=$'\e[2m' _bold=$'\e[1m' _cyan=$'\e[36m' _green=$'\e[32m' _red=$'\e[31m' _yellow=$'\e[33m' _magenta=$'\e[35m' _off=$'\e[0m'

demo_start() {
    local root
    root="$(git rev-parse --show-toplevel)" || return 1
    (cd "$root" && setsid -f ./mvnw -q -pl examples/banking-agent spring-boot:run \
        -Dspring-boot.run.arguments="--server.port=${DEMO_PORT}" >"$DEMO_LOG" 2>&1)
    for _ in $(seq 1 240); do
        if curl -sf "$DEMO_URL/actuator/health" >/dev/null; then
            echo "ready"
            return 0
        fi
        sleep 0.5
    done
    echo "The app did not start; see $DEMO_LOG" >&2
    return 1
}

demo_stop() {
    pkill -f "server.port=${DEMO_PORT}" && echo "stopped"
}

# Alice sends a message. If a tool call needs approval, says so and leaves the request waiting.
ask() {
    local reply
    reply="$(mktemp)"
    rm -f "$reply"
    DEMO_BODY="$(jq -nc --arg m "$1" '{message: $m}')" DEMO_OUT="$reply" DEMO_URL="$DEMO_URL" setsid -f bash -c \
        'curl -s -X POST "$DEMO_URL/chat" -H "X-User: alice" -H "Content-Type: application/json" \
             -d "$DEMO_BODY" >"$DEMO_OUT.part" && mv "$DEMO_OUT.part" "$DEMO_OUT"'
    for _ in $(seq 1 100); do
        if [[ -f "$reply" ]]; then
            _answer "$reply"
            return
        fi
        local pending
        pending="$(curl -s "$DEMO_URL/approvals")"
        if [[ "$(jq length <<<"$pending")" -gt 0 ]]; then
            _demo_reply="$reply"
            jq -r --arg y "$_yellow" --arg b "$_bold" --arg d "$_dim" --arg o "$_off" '.[0] |
                "\($y)\($b)● \(.tool)\($o)\($y) \(.arguments | tojson)\($o)",
                "\($y)  waiting for a second person: \(.reason)\($o) \($d)(asked by \(.requestedBy))\($o)"' \
                <<<"$pending"
            return
        fi
        sleep 0.1
    done
    echo "${_red}No answer yet${_off}"
}

# Someone answers the pending approval. The requester cannot approve their own call.
approve() {
    local approver="${1:-ops@bank.example}" id status
    id="$(curl -s "$DEMO_URL/approvals" | jq -r '.[0].invocationId // empty')"
    if [[ -z "$id" ]]; then
        echo "Nothing is waiting for approval"
        return 1
    fi
    status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DEMO_URL/approvals/$id/approve" -H "X-User: $approver")"
    if [[ "$status" == 403 ]]; then
        echo "${_red}${_bold}✘ $approver cannot approve their own request${_off}"
        return
    fi
    echo "${_green}${_bold}✓ approved by $approver${_off}"
    for _ in $(seq 1 100); do
        [[ -f "$_demo_reply" ]] && break
        sleep 0.1
    done
    _answer "$_demo_reply"
    _demo_reply=""
}

# What the outside helpdesk received for the latest ticket, read from its table rather than through the agent.
helpdesk() {
    curl -s "$DEMO_URL/helpdesk/tickets" | jq -r --arg y "$_yellow" --arg b "$_bold" --arg o "$_off" '
        if length == 0 then "No tickets yet" else "\($y)\($b)Helpdesk received ▸\($o)\($y) \(.[0].description)\($o)" end'
}

# Every receipt of this run: what was decided, under which policy, and how each one links to the one before.
receipts() {
    curl -s "$DEMO_URL/receipts" | jq -r --arg d "$_dim" --arg o "$_off" '.[] |
        "#\(.sequence)  \(.tool | .[0:20] | . + " " * (20 - length)) \(.decision | . + " " * (17 - length))"
        + "\(.matchedPolicies[0] // "default" | . + " " * (24 - length))"
        + "\($d)\(.hash[0:4])…\(.hash[-4:])  prev \(.previousHash[0:4])…\(.previousHash[-4:])  Ed25519\($o)"'
}

verify() {
    curl -s "$DEMO_URL/receipts/verify" | jq -r --arg g "$_green" --arg r "$_red" --arg b "$_bold" --arg o "$_off" '
        if .valid then "\($g)\($b)✓ \(.receipts) receipts verified\($o)\($g): hashes, chain links and signatures\($o)"
        else "\($r)\($b)✘ \(.problem)\($o)" end'
}

_answer() {
    echo "${_cyan}${_bold}Agent ▸${_off}${_cyan} $(jq -r .answer "$1")${_off}"
    rm -f "$1"
    curl -s "$DEMO_URL/receipts" | jq -r --arg g "$_green" --arg r "$_red" --arg m "$_magenta" --arg d "$_dim" \
        --arg o "$_off" 'last |
        (if .decision == "DENY" or (.approval != null and .approval != "APPROVED") then $r else $g end) as $c
        | "\($c)● \(.decision)\(if .approval then " → \(.approval)" else "" end)"
          + " · policy \(.matchedPolicies[0] // "default")\($o)"
          + "\($d) · signed receipt #\(.sequence) \(.hash[0:4])…\(.hash[-4:])\($o)",
          (select((.redactions // []) | length > 0) | "\($m)  ↳ redacted by \(.redactions | join(", "))\($o)")'
}
