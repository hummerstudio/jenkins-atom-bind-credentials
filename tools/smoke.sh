#!/usr/bin/env bash
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

dir="$(mktemp -d)"
printf '{"secretTextAs": "API_TOKEN", "usernameAs": "ARTIFACT_USER", "sshKeyFile": "secrets/deploy.key", "envFile": "build.env"}' >"$dir/input.json"
printf '{"secretText": "s3cr3t", "username": "robot", "sshKey": "KEY"}' >"$dir/.secrets.json"
run "$dir" >"$dir/log.txt" 2>&1 || fail "run failed: $(cat "$dir/log.txt")"
[ "$(status "$dir")" = "success" ] || fail "not a success"
[ "$(output "$dir" API_TOKEN)" = "s3cr3t" ] || fail "variable"
[ -f "$dir/secrets/deploy.key" ] || fail "key file missing"
[ "$(stat -f '%Lp' "$dir/secrets/deploy.key")" = "600" ] || fail "key file must be 0600"
grep -q "API_TOKEN=s3cr3t" "$dir/build.env" || fail "dotenv"
grep -q "s3cr3t" "$dir/log.txt" && fail "secret leaked to the log"

dir="$(mktemp -d)"; printf '{}' >"$dir/input.json"
run "$dir" >/dev/null 2>&1 && fail "no credential must fail"
grep -q "no credential was provided" "$dir/output.json" || fail "message"

echo "smoke ok: variables, 0600 file, dotenv, no leak, missing credential"
