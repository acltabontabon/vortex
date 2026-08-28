#!/usr/bin/env bash
# Puts a mix of requests through the demo service so it has something for Prometheus to scrape and
# for Vortex's Production Reality support to observe. Dependency-free (curl only) — no k6 or Vortex
# required, so this works as the very first thing to run against a freshly started demo stack.
#
# Usage: ./generate-traffic.sh [base-url] [duration-seconds]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DURATION="${2:-120}"

echo "Generating traffic against ${BASE_URL} for ${DURATION}s..."
end=$((SECONDS + DURATION))

# A handful of account/order ids reused across requests, so GET /orders/{id} and
# POST /orders/{id}/cancel have something real to find — not just a stream of 404s.
order_ids=()

while [ "$SECONDS" -lt "$end" ]; do
  account_id="acct-$(( (RANDOM % 20) + 1 ))"

  case $(( RANDOM % 4 )) in
    0)
      curl -s -o /dev/null "${BASE_URL}/accounts/${account_id}"
      ;;
    1)
      response=$(curl -s -X POST "${BASE_URL}/orders" \
        -H 'Content-Type: application/json' \
        -d "{\"accountId\":\"${account_id}\",\"amount\":19.99}")
      id=$(echo "$response" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')
      if [ -n "$id" ]; then
        order_ids+=("$id")
      fi
      ;;
    2)
      if [ "${#order_ids[@]}" -gt 0 ]; then
        pick="${order_ids[$((RANDOM % ${#order_ids[@]}))]}"
        curl -s -o /dev/null "${BASE_URL}/orders/${pick}"
      fi
      ;;
    3)
      if [ "${#order_ids[@]}" -gt 0 ]; then
        pick="${order_ids[$((RANDOM % ${#order_ids[@]}))]}"
        curl -s -o /dev/null -X POST "${BASE_URL}/orders/${pick}/cancel"
      fi
      ;;
  esac

  # A little jitter so the traffic isn't perfectly uniform — closer to what a real request rate
  # looks like when Prometheus's rate()/increase() samples it.
  sleep "0.$(( (RANDOM % 20) + 5 ))"
done

echo "Done. ${#order_ids[@]} orders created."
