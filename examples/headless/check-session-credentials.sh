#!/bin/bash
# Checks whether a Workbench Job keeps working credentials of its own after the session that
# launched it is suspended or stopped. Workbench gives each job its own RS_SESSION_RPC_COOKIE,
# POSIT_PRODUCT and RS_SERVER_ADDRESS. If that cookie outlives the session, a headless
# Nextflow driver started from a Positron session can submit its tasks with it, and nothing
# needs to be passed to the job.
#
# Run it in a terminal of a Positron or RStudio Pro session on Workbench:
#
#   ./check-session-credentials.sh <cluster> [<container image>]
#
# The job runs in the session's own image (PWB_SESSION_CONTAINER_IMAGE) unless another one is
# given. Kubernetes clusters require an image.
#
# The job calls the Workbench API once a minute for 30 minutes and logs each result. Suspend
# or stop the session while it runs, then read the log from a new session.
set -euo pipefail

cluster=${1:?usage: $0 <cluster> [<container image>]}
image=${2:-${PWB_SESSION_CONTAINER_IMAGE:-}}

# Prefer the launcher installed with `uv tool install` over an older copy earlier on the PATH
# (e.g. from an activated launcher/.venv). Override with LAUNCHER=/path/to/launcher.
launcher=${LAUNCHER:-$HOME/.local/bin/posit-workbench-nf-launcher}
[ -x "$launcher" ] || launcher=$(command -v posit-workbench-nf-launcher || true)
if [ -z "$launcher" ] || ! "$launcher" submit --help | grep -q -- "--env"; then
    echo "error: ${launcher:-posit-workbench-nf-launcher} does not support 'submit --env'; install the launcher from the headless branch:" >&2
    echo "  uv tool install --force 'git+https://github.com/sol-eng/posit-sdk-nextflow@headless#subdirectory=launcher'" >&2
    echo "Found on the PATH: $(type -a posit-workbench-nf-launcher 2>/dev/null | tr '\n' ' ')" >&2
    exit 1
fi
echo "using $launcher"
echo "image: ${image:-none, the cluster default}"

dir="$HOME/.cache/nf-workbench-headless-check/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$dir"

cat > "$dir/job.sh" <<JOB
#!/bin/bash
launcher="$launcher"
JOB
cat >> "$dir/job.sh" <<'JOB'
# only the job's own credentials: no session cookie is passed in
unset PWB_SESSION_RUNTIME_DIR
cookie_expiry() {
    printf '%s' "${RS_SESSION_RPC_COOKIE:-}" | cut -d'|' -f2 | sed 's/%2C/,/g; s/%20/ /g; s/%3A/:/g'
}
if [ -n "${RS_SESSION_RPC_COOKIE:-}" ]; then
    echo "$(date -u +%FT%TZ) job cookie: set, expires $(cookie_expiry)" | tee -a check.log
else
    echo "$(date -u +%FT%TZ) job cookie: NOT SET" | tee -a check.log
fi
for i in $(seq 1 30); do
    if out=$("$launcher" status 2>&1); then
        echo "$(date -u +%FT%TZ) ok: the API accepted the job's cookie ($(echo "$out" | wc -l) jobs listed)"
    else
        echo "$(date -u +%FT%TZ) FAILED: $(echo "$out" | tail -1)"
    fi | tee -a check.log
    sleep 60
done
JOB
chmod +x "$dir/job.sh"

job=$("$launcher" submit --cluster "$cluster" --name nf-headless-check \
    ${image:+--container "$image"} \
    --workdir "$dir" \
    "$dir/job.sh")
echo "submitted job $job. Now suspend or stop this session for a few minutes, then, from a new one:"
echo "  cat $dir/check.log"
