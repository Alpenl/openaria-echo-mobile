#!/usr/bin/env bash
set -euo pipefail

repository="Alpenl/openaria-echo-mobile"
workflow="mobile-release.yml"
api_version="2026-03-10"

usage() {
  echo "Usage: $0 <40-character-source-commit> <vX.Y.Z> [--allow-legacy-baseline-bootstrap]" >&2
  exit 2
}

[[ "$#" -eq 2 || "$#" -eq 3 ]] || usage
source_commit="$1"
release_tag="$2"
allow_legacy_baseline_bootstrap=false
if [[ "$#" -eq 3 ]]; then
  [[ "$3" == "--allow-legacy-baseline-bootstrap" ]] || usage
  allow_legacy_baseline_bootstrap=true
fi
if [[ ! "${source_commit}" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "${release_tag}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  usage
fi
if [[ "${allow_legacy_baseline_bootstrap}" == "true" && "${release_tag}" != "v0.1.7" ]]; then
  echo "Legacy baseline bootstrap is authorized only for v0.1.7." >&2
  exit 1
fi

for tool in gh jq sha256sum base64 date; do
  if ! command -v "${tool}" >/dev/null 2>&1; then
    echo "Missing required release operator tool: ${tool}" >&2
    exit 1
  fi
done

actor="$(gh api user --jq .login)"
repository_owner="${repository%%/*}"
if [[ "${actor}" != "${repository_owner}" ]]; then
  echo "The authenticated GitHub actor must be repository owner ${repository_owner}; got ${actor}." >&2
  exit 1
fi
remote_commit="$(gh api "repos/${repository}/commits/${source_commit}" --jq .sha)"
if [[ "${remote_commit}" != "${source_commit}" ]]; then
  echo "The exact source commit is not available in ${repository}." >&2
  exit 1
fi
default_branch="$(gh api "repos/${repository}" --jq .default_branch)"
default_branch_head="$(gh api "repos/${repository}/commits/${default_branch}" --jq .sha)"
if [[ "${default_branch_head}" != "${source_commit}" ]]; then
  echo "The release source must equal the current ${default_branch} head ${default_branch_head}; got ${source_commit}." >&2
  exit 1
fi

control_root="$(mktemp -d)"
cleanup() {
  rm -rf "${control_root}"
}
trap cleanup EXIT
raw_response="${control_root}/immutable-releases-response.json"

# This call intentionally uses the operator's local admin-capable gh identity.
# The workflow's GITHUB_TOKEN does not receive Administration permission.
gh api \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: ${api_version}" \
  "repos/${repository}/immutable-releases" \
  > "${raw_response}"
if ! jq -e '
  (type == "object") and
  (keys == ["enabled", "enforced_by_owner"]) and
  .enabled == true and
  (.enforced_by_owner | type) == "boolean"
' "${raw_response}" >/dev/null; then
  echo "GitHub immutable Releases are not enabled or the official response schema is unexpected." >&2
  exit 1
fi

checked_at="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
response_sha256="$(sha256sum "${raw_response}" | cut -d ' ' -f 1)"
response_raw_base64="$(base64 -w 0 "${raw_response}")"
preflight="$({
  jq -cn \
    --arg schema "openaria.github.immutable-releases-preflight.v1" \
    --arg repository "${repository}" \
    --arg actor "${actor}" \
    --arg source_commit "${source_commit}" \
    --arg default_branch "${default_branch}" \
    --arg default_branch_head "${default_branch_head}" \
    --arg release_tag "${release_tag}" \
    --argjson allow_legacy_baseline_bootstrap "${allow_legacy_baseline_bootstrap}" \
    --arg endpoint "GET /repos/${repository}/immutable-releases" \
    --arg api_version "${api_version}" \
    --arg checked_at "${checked_at}" \
    --arg response_sha256 "${response_sha256}" \
    --arg response_raw_base64 "${response_raw_base64}" \
    --slurpfile response "${raw_response}" \
    '{schema: $schema, repository: $repository, actor: $actor,
      source_commit: $source_commit,
      default_branch: $default_branch,
      default_branch_head: $default_branch_head,
      release_tag: $release_tag,
      allow_legacy_baseline_bootstrap: $allow_legacy_baseline_bootstrap,
      endpoint: $endpoint, api_version: $api_version,
      checked_at: $checked_at, enabled: true,
      response_sha256: $response_sha256,
      response_raw_base64: $response_raw_base64,
      response: $response[0]}'
})"

# Dispatch immediately. Rerunning this Actions run is deliberately rejected;
# every publication attempt needs a new GET and a new workflow_dispatch run.
gh workflow run "${workflow}" \
  --repo "${repository}" \
  --ref "${default_branch}" \
  -f ref="${source_commit}" \
  -f release_tag="${release_tag}" \
  -f allow_legacy_baseline_bootstrap="${allow_legacy_baseline_bootstrap}" \
  -f immutable_releases_preflight="${preflight}"

echo "Dispatched ${workflow} for ${release_tag} at ${source_commit}."
