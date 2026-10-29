#!/usr/bin/env bash
# Prints the test pyramid of droso-backend: tests and time per layer, the Spring context
# configurations and the isolated PostgreSQL classes, as a Markdown table for a report or a PR.
#
# Usage, from backend/:
#   bash ../.claude/skills/test-pyramid/pyramid.sh          reads the results of the last test run
#   bash ../.claude/skills/test-pyramid/pyramid.sh --run    runs ./gradlew test --rerun-tasks first
#
# The layer of a class is guessed from its source; the counts (parameterized cases included) and
# the times come from build/test-results/test. Layers:
#   unit   no Spring, no database
#   slice  part of Spring without a database: @WebMvcTest, ApplicationContextRunner, standalone MockMvc
#   db     a real PostgreSQL without HTTP: @SpringBootTest without MockMvc, or startServer()
#   api    the whole application with PostgreSQL through MockMvc
#   http   the whole application over a real HTTP connection (RANDOM_PORT)
set -euo pipefail

if [[ ! -f build.gradle.kts || ! -d src/test/java ]]; then
    echo "Run it from backend/ (the Gradle project)." >&2
    exit 2
fi

wall=""
if [[ "${1:-}" == "--run" ]]; then
    start=$(date +%s)
    if ! ./gradlew test --rerun-tasks -q >/dev/null 2>&1; then
        echo "./gradlew test failed: the numbers below may be incomplete." >&2
    fi
    wall=$(( $(date +%s) - start ))
fi

results=build/test-results/test
if [[ ! -d $results ]]; then
    echo "No test results in $results: run ./gradlew test first, or pass --run." >&2
    exit 2
fi
newest_source=$(find src/test/java src/main/java -name '*.java' -newer "$results" | head -1 || true)
if [[ -n $newest_source ]]; then
    echo "Warning: $newest_source is newer than the test results; pass --run for current numbers." >&2
fi

layer_of() {
    local file=$1
    if grep -q 'WebEnvironment.RANDOM_PORT' "$file"; then
        echo http
    elif grep -qE '@SpringBootTest|extends (Isolated)?PostgresIntegrationTest' "$file"; then
        if grep -qE 'MockMvc' "$file"; then echo api; else echo db; fi
    elif grep -qE 'startServer\(\)' "$file"; then
        echo db
    elif grep -qE '@WebMvcTest|ApplicationContextRunner|standaloneSetup' "$file"; then
        echo slice
    else
        echo unit
    fi
}

# The configuration that decides whether Spring's context cache can reuse a context: the base
# class, the @SpringBootTest attributes, the beans replaced by mocks. An isolated class always
# gets a context of its own.
context_key() {
    local file=$1 base annotation mocks
    base=$(grep -oE 'extends (Isolated)?PostgresIntegrationTest' "$file" || echo none)
    annotation=$(grep -oE '@SpringBootTest(\([^)]*\))?' "$file" | head -1 || true)
    mocks=$(grep -A1 '@MockitoBean' "$file" | grep -oE 'private [A-Za-z<>]+' | sort | tr '\n' ',' || true)
    if [[ $base == *Isolated* ]]; then
        echo "isolated:$file"
    else
        echo "$base|$annotation|$mocks"
    fi
}

declare -A tests=([unit]=0 [slice]=0 [db]=0 [api]=0 [http]=0)
declare -A seconds=([unit]=0 [slice]=0 [db]=0 [api]=0 [http]=0)
declare -A classes=([unit]=0 [slice]=0 [db]=0 [api]=0 [http]=0)
declare -A contexts=()
isolated=()
missing=()

while IFS= read -r file; do
    grep -qE '@(Test|ParameterizedTest|TestFactory|RepeatedTest)\b' "$file" || continue
    fqn=${file#src/test/java/}
    fqn=${fqn%.java}
    fqn=${fqn//\//.}
    shopt -s nullglob
    reports=("$results/TEST-$fqn.xml" "$results/TEST-$fqn\$"*.xml)
    shopt -u nullglob
    count=0
    time=0
    found=0
    for report in "${reports[@]}"; do
        [[ -f $report ]] || continue
        found=1
        header=$(head -2 "$report" | grep -o '<testsuite [^>]*>')
        count=$(( count + $(sed -E 's/.* tests="([0-9]+)".*/\1/' <<<"$header") ))
        time=$(awk -v a="$time" -v b="$(sed -E 's/.* time="([0-9.]+)".*/\1/' <<<"$header")" 'BEGIN { print a + b }')
    done
    if (( ! found )); then
        missing+=("$fqn")
        continue
    fi
    layer=$(layer_of "$file")
    tests[$layer]=$(( ${tests[$layer]} + count ))
    seconds[$layer]=$(awk -v a="${seconds[$layer]}" -v b="$time" 'BEGIN { print a + b }')
    classes[$layer]=$(( ${classes[$layer]} + 1 ))
    if grep -q '@SpringBootTest' "$file"; then
        contexts[$(context_key "$file")]=1
    fi
    if grep -q 'extends IsolatedPostgresIntegrationTest' "$file"; then
        isolated+=("${fqn##*.}")
    fi
done < <(find src/test/java -name '*.java' | sort)

total=0
total_seconds=0
for layer in unit slice db api http; do
    total=$(( total + ${tests[$layer]} ))
    total_seconds=$(awk -v a="$total_seconds" -v b="${seconds[$layer]}" 'BEGIN { print a + b }')
done
if (( total == 0 )); then
    echo "No test results matched the test sources." >&2
    exit 2
fi

echo "| Layer | Classes | Tests | Share | Time |"
echo "|---|---|---|---|---|"
for layer in unit slice db api http; do
    awk -v l="$layer" -v c="${classes[$layer]}" -v t="${tests[$layer]}" -v total="$total" -v s="${seconds[$layer]}" \
        'BEGIN { printf "| %s | %d | %d | %.0f%% | %.1f s |\n", l, c, t, 100 * t / total, s }'
done
awk -v t="$total" -v s="$total_seconds" 'BEGIN { printf "| **total** | | **%d** | | %.1f s |\n", t, s }'
echo
top=$(( ${tests[api]} + ${tests[http]} ))
top_share=$(awk -v a="$top" -v t="$total" 'BEGIN { printf "%.0f", 100 * a / t }')
echo "- Top (api + http): $top tests, $top_share %"
echo "- Spring context configurations with PostgreSQL: ${#contexts[@]} (isolated classes included)"
echo "- Isolated PostgreSQL classes: ${#isolated[@]}${isolated:+ (${isolated[*]})}"
[[ -n $wall ]] && echo "- ./gradlew test --rerun-tasks: ${wall} s"
if (( ${#missing[@]} )); then
    echo "- No results for: ${missing[*]}"
fi
if (( top_share > 40 )); then
    echo
    echo "WARNING: the top of the pyramid exceeds 40 % of the tests." >&2
fi
