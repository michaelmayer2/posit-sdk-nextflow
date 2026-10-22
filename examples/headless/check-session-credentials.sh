#!/bin/bash
# Checks whether a Workbench Job can keep using a copy of this session's credentials, the
# session RPC cookie, after the session is suspended or stopped. That decides whether a
# headless Nextflow driver started from a Positron session can submit its tasks with the
# session's credentials.
#
# Run it in a terminal of a Positron or RStudio Pro session on Workbench:
#
#   ./check-session-credentials.sh <cluster> [<container image>]
#
# It submits a job that calls the Workbench API once a minute for 30 minutes, using a copy of
# the cookie, and logs each result. Suspend or stop the session while it runs, then read the
# log from a new session.
set -euo pipefail

cluster=${1:?usage: $0 <cluster> [<container image>]}
image=${2:-}

# Prefer the launcher installed with `uv tool install` over an older copy earlier on the PATH
# (e.g. from an activated launcher/.venv). Override with LAUNCHER=/path/to/launcher.
launcher=${LAUNCHER:-$HOME/.local/bin/posit-workbench-nf-launcher}
[ -x "$launcher" ] || launcher=$(command -v posit-workbench-nf-launcher || true)
if [ -z "$launcher" ] || ! "$launcher" submit --help | grep -q -- "--env"; then
    echo "error: ${launcher:-posit-workbench-nf-launcher} does not support 'submit --env'; install the launcher from the headless branch:" >&2
    echo "  uv tool install --force 'git+https://github.com/michaelmayer2/posit-sdk-nextflow@headless#subdirectory=launcher'" >&2
    echo "Found on the PATH: $(type -a posit-workbench-nf-launcher 2>/dev/null | tr '\n' ' ')" >&2
    exit 1
fi
echo "using $launcher"
dir="$HOME/.cache/nf-workbench-headless-check/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$dir/.session"
chmod 700 "$dir/.session"

if [ -n "${PWB_SESSION_RUNTIME_DIR:-}" ] && [ -f "$PWB_SESSION_RUNTIME_DIR/rpc_cookie" ]; then
    cp "$PWB_SESSION_RUNTIME_DIR/rpc_cookie" "$dir/.session/rpc_cookie"
elif [ -n "${RS_SESSION_RPC_COOKIE:-}" ]; then
    printf '%s' "$RS_SESSION_RPC_COOKIE" > "$dir/.session/rpc_cookie"
else
    echo "error: no session RPC cookie found; run this inside a Workbench session" >&2
    exit 1
fi
chmod 600 "$dir/.session/rpc_cookie"
expiry=$(cut -d'|' -f2 "$dir/.session/rpc_cookie" | sed 's/%2C/,/g; s/%20/ /g; s/%3A/:/g')
echo "cookie copied; it expires $expiry"

cat > "$dir/job.sh" <<JOB
#!/bin/bash
launcher="$launcher"
JOB
cat >> "$dir/job.sh" <<'JOB'
for i in $(seq 1 30); do
    if out=$("$launcher" status 2>&1); then
        echo "$(date -u +%FT%TZ) ok: the API accepted the cookie ($(echo "$out" | wc -l) jobs listed)"
    else
        echo "$(date -u +%FT%TZ) FAILED: $(echo "$out" | tail -1)"
    fi | tee -a check.log
    sleep 60
done
JOB
chmod +x "$dir/job.sh"

job=$("$launcher" submit --cluster "$cluster" --name nf-headless-check \
    ${image:+--container "$image"} \
    --env PWB_SESSION_RUNTIME_DIR="$dir/.session" \
    --env POSIT_PRODUCT=WORKBENCH \
    --env RS_SERVER_ADDRESS \
    --workdir "$dir" \
    "$dir/job.sh")
echo "submitted job $job. Now suspend or stop this session for a few minutes, then, from a new one:"
echo "  cat $dir/check.log"
