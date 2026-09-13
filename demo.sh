#!/usr/bin/env bash
#
# Walks the whole version chain against a running server: full download of the first version,
# then a delta upgrade to every following one, and prints what each step cost.
#
# Shell counterpart of demo.ps1. Needs only curl, sed and awk -- no jq, because it cannot be
# relied on being installed. The JSON it parses comes from this project's own server, so the
# layout it depends on (Jackson's pretty printer, one field per line) is an internal contract
# that ApiContractTest keeps honest.
#
# The ELAPSED column is the client's own figure, so unlike demo.ps1's Seconds it leaves out JVM
# startup. Expect it to read a few tenths lower for the same work.
#
#   ./demo.sh --server http://localhost:8080 --work data/client
#
set -uo pipefail

here=$(cd "$(dirname "$0")" && pwd)
server='http://localhost:8080'
work="$here/data/client"
jar="$here/build/libs/jDeltaTransfer-1.0.0-all.jar"
java_opts='-Xmx4g'

usage() {
    sed -n '3,12p' "$0" | sed 's/^# \{0,1\}//'
    cat <<EOF

Options:
  --server URL    server base URL           (default: $server)
  --work DIR      where to put the archives (default: ./data/client)
  --jar PATH      fat jar                   (default: build/libs/jDeltaTransfer-1.0.0-all.jar)
  --java-opts S   JVM options               (default: $java_opts)
EOF
}

while [ $# -gt 0 ]; do
    case "$1" in
        --server)    server=$2; shift 2 ;;
        --work)      work=$2; shift 2 ;;
        --jar)       jar=$2; shift 2 ;;
        --java-opts) java_opts=$2; shift 2 ;;
        -h|--help)   usage; exit 0 ;;
        *)           echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

die() { echo "demo.sh: $*" >&2; exit 1; }

[ -f "$jar" ] || die "fat jar not found at $jar -- run: ./gradlew fatJar"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    java_bin="$JAVA_HOME/bin/java"
else
    command -v java >/dev/null 2>&1 || die "no java on PATH and JAVA_HOME is not set"
    java_bin=java
fi
command -v curl >/dev/null 2>&1 || die "curl is required"

mkdir -p "$work"
cache="$work/cache"

# Runs the client and returns its output; fails loudly, like the PowerShell version does.
jdt() {
    local out status
    out=$("$java_bin" $java_opts -jar "$jar" client "$@" 2>&1)
    status=$?
    if [ $status -ne 0 ]; then
        printf '%s\n' "$out" >&2
        die "client failed: client $*"
    fi
    printf '%s\n' "$out"
}

# All values of one string field, in document order. Anchoring on the quoted name matters:
# plain "sha256" must not also pick up "blueprintSha256".
json_strings() {
    sed -n "s/^[[:space:]]*\"$1\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*$/\1/p"
}

# One labelled value out of the client's own report, e.g. "transferred" or "elapsed".
client_field() {
    awk -v key="$1" '
        $1 == key {
            sub(/^[[:space:]]*[^[:space:]]+[[:space:]]+/, "")
            print
            exit
        }' <<<"$2"
}

versions_json=$(curl -fsS "$server/api/versions") \
    || die "cannot reach $server -- is the server running?"

mapfile -t ids      < <(printf '%s\n' "$versions_json" | json_strings id)
mapfile -t files    < <(printf '%s\n' "$versions_json" | json_strings fileName)
mapfile -t sizes    < <(printf '%s\n' "$versions_json" | json_strings sizeHuman)
mapfile -t hashes   < <(printf '%s\n' "$versions_json" | json_strings sha256)

[ ${#ids[@]} -gt 0 ] || die "server reports no versions"
[ ${#files[@]} -eq ${#ids[@]} ] && [ ${#hashes[@]} -eq ${#ids[@]} ] \
    || die "could not parse the version list (got ${#ids[@]} ids, ${#files[@]} names, ${#hashes[@]} hashes)"

echo "server has ${#ids[@]} versions"
echo

rows=()
previous=''
failures=0
for i in "${!ids[@]}"; do
    id=${ids[$i]}
    # Keep the server's own file name: the outermost container may be ZIP, CAB or 7z.
    target="$work/${files[$i]}"

    if [ -z "$previous" ]; then
        mode=full
        log=$(jdt fetch --server "$server" --cache "$cache" --version "$id" --out "$target")
    else
        mode=delta
        log=$(jdt fetch --server "$server" --cache "$cache" --version "$id" \
                        --base "$previous" --out "$target")
    fi

    transferred=$(client_field transferred "$log")
    elapsed=$(client_field elapsed "$log")
    saved=$(printf '%s\n' "$log" | sed -n 's/.*(\([0-9.]*%\) less than the full archive).*/\1/p' | head -1)
    [ -n "$saved" ] || saved='-'
    # "transferred" carries the saving in parentheses too; the table shows it separately.
    transferred=${transferred%% (*}

    # Independent check: hash the result and compare with what the server published.
    local_hash=$(jdt hash --file "$target" | awk '{print $1}')
    if [ "$local_hash" = "${hashes[$i]}" ]; then
        verify=OK
    else
        verify='HASH MISMATCH'
        failures=$((failures + 1))
    fi

    printf '  %-14s %-5s  archive %10s  transferred %12s  saved %7s  %s\n' \
        "$id" "$mode" "${sizes[$i]}" "$transferred" "$saved" "$verify"
    rows+=("$id|$mode|${sizes[$i]}|$transferred|$saved|$verify|$elapsed")

    [ "$verify" = OK ] || die "verification failed for $id"
    previous="$target"
done

echo
{
    printf 'VERSION|MODE|ARCHIVE|TRANSFERRED|SAVED|VERIFY|ELAPSED\n'
    printf '%s\n' "${rows[@]}"
} | awk -F'|' '{ for (i = 1; i <= NF; i++) printf "%-16s", $i; print "" }'

[ $failures -eq 0 ] || exit 1
