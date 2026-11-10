#!/usr/bin/env bash
# Windows Git Bash / Bash client. Credentials never enter the shell.
set +x
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
project_dir=$(cd -- "$script_dir/.." && pwd)
python_cmd=${TLQ_PYTHON:-python}
java_cmd=${TLQ_JAVA:-java}
csv_file= query_file= transform_url= load_url= query_url=
profile= region= transform_region= load_region= query_region=
auth=aws-iam
batch_size=200
timeout=60
output_root="$project_dir/test-output/runs"
jar_file="$project_dir/target/client-sync-tlq.jar"

usage() {
  cat <<'HELP'
Usage: bash scripts/tlq-sync.sh --csv FILE --transform-url URL --load-url URL --query-url URL
  [--query-file JSON_FILE] [--batch-size 200] [--output-root DIR] [--jar FILE]
  [--auth aws-iam|none] [--profile NAME] [--region REGION]
  [--transform-region REGION] [--load-region REGION] [--query-region REGION]
  [--timeout-seconds 60]
aws-iam requires an explicit profile and a signing region for every endpoint.
none is only for local loopback HTTP testing. No automatic request retries.
Environment: TLQ_PYTHON and TLQ_JAVA choose executables; never provide credentials here.
HELP
}
while (($#)); do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --csv|--query-file|--transform-url|--load-url|--query-url|--profile|--region|--transform-region|--load-region|--query-region|--auth|--batch-size|--timeout-seconds|--output-root|--jar)
      if (($# < 2)); then usage >&2; exit 2; fi
      case "$1" in
        --csv) csv_file=$2 ;; --query-file) query_file=$2 ;;
        --transform-url) transform_url=$2 ;; --load-url) load_url=$2 ;; --query-url) query_url=$2 ;;
        --profile) profile=$2 ;; --region) region=$2 ;;
        --transform-region) transform_region=$2 ;; --load-region) load_region=$2 ;; --query-region) query_region=$2 ;;
        --auth) auth=$2 ;; --batch-size) batch_size=$2 ;; --timeout-seconds) timeout=$2 ;;
        --output-root) output_root=$2 ;; --jar) jar_file=$2 ;;
      esac
      shift 2 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

# Normalize only filesystem arguments for native Python/Java on Windows.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -am -- "$1"; else printf '%s\n' "$1"; fi
}
helper=$(native_path "$script_dir/tlq_client.py")
output_root=$(native_path "$output_root")
jar_file=$(native_path "$jar_file")
if [[ -n "$csv_file" ]]; then csv_file=$(native_path "$csv_file"); fi
if [[ -n "$query_file" ]]; then query_file=$(native_path "$query_file"); fi

run_dir=$("$python_cmd" "$helper" init --output-root "$output_root")
run_dir=${run_dir%$'\r'}
finish() {
  local exit_code=$?
  trap - EXIT
  "$python_cmd" "$helper" finalize --run-dir "$run_dir" --exit-code "$exit_code" || true
  printf 'Run artifacts: %s\n' "$run_dir"
  exit "$exit_code"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"$python_cmd" "$helper" prepare --run-dir "$run_dir" --csv "$csv_file" --jar "$jar_file" \
  --java "$java_cmd" --query-file "$query_file" --batch-size "$batch_size" --timeout "$timeout" \
  --auth "$auth" --profile "$profile" \
  --transform-url "$transform_url" --load-url "$load_url" --query-url "$query_url" \
  --transform-region "${transform_region:-$region}" --load-region "${load_region:-$region}" --query-region "${query_region:-$region}"
"$python_cmd" "$helper" batch --run-dir "$run_dir"

while IFS=$'\t' read -r index count csv_name expected_name; do
  [[ -n "$index" ]] || continue
  "$python_cmd" "$helper" request --run-dir "$run_dir" --stage transform --batch-index "$index" --expected-count "$count" \
    --input "$run_dir/batches/$csv_name" --expected "$run_dir/batches/$expected_name"
  "$python_cmd" "$helper" request --run-dir "$run_dir" --stage load --batch-index "$index" --expected-count "$count" \
    --input "$run_dir/responses/batch-$(printf '%06d' "$index")-transform.json"
done < "$run_dir/batches.tsv"

# The helper also independently verifies all manifest batches have matching Load acknowledgements.
"$python_cmd" "$helper" request --run-dir "$run_dir" --stage query --input "$run_dir/query-request.json"
