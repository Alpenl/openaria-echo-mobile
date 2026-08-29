#!/usr/bin/env bash
set -euo pipefail

candidate_manifest=""
candidate_apk=""
repository=""
release_tag=""
source_commit=""
baseline_tag=""
baseline_version_name=""
baseline_version_code=""
baseline_apk_name=""
baseline_release_id=""
baseline_apk_sha256=""
legacy_bootstrap_authorized=""
expected_version_name=""
expected_version_code=""
run_id=""
run_attempt=""
evidence_dir=""

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --candidate-manifest) candidate_manifest="$2"; shift 2 ;;
    --candidate-apk) candidate_apk="$2"; shift 2 ;;
    --repository) repository="$2"; shift 2 ;;
    --release-tag) release_tag="$2"; shift 2 ;;
    --source-commit) source_commit="$2"; shift 2 ;;
    --baseline-tag) baseline_tag="$2"; shift 2 ;;
    --baseline-version-name) baseline_version_name="$2"; shift 2 ;;
    --baseline-version-code) baseline_version_code="$2"; shift 2 ;;
    --baseline-apk-name) baseline_apk_name="$2"; shift 2 ;;
    --baseline-release-id) baseline_release_id="$2"; shift 2 ;;
    --baseline-apk-sha256) baseline_apk_sha256="$2"; shift 2 ;;
    --legacy-bootstrap-authorized) legacy_bootstrap_authorized="$2"; shift 2 ;;
    --expected-version-name) expected_version_name="$2"; shift 2 ;;
    --expected-version-code) expected_version_code="$2"; shift 2 ;;
    --run-id) run_id="$2"; shift 2 ;;
    --run-attempt) run_attempt="$2"; shift 2 ;;
    --evidence-dir) evidence_dir="$2"; shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

required=(
  candidate_manifest candidate_apk repository release_tag source_commit
  baseline_tag baseline_version_name baseline_version_code baseline_apk_name
  baseline_release_id baseline_apk_sha256 expected_version_name
  legacy_bootstrap_authorized expected_version_code run_id run_attempt evidence_dir
)
for name in "${required[@]}"; do
  if [[ -z "${!name}" ]]; then
    echo "Missing required staged acceptance value: ${name}" >&2
    exit 2
  fi
done

candidate_manifest="$(realpath "${candidate_manifest}")"
candidate_apk="$(realpath "${candidate_apk}")"
evidence_dir="$(realpath -m "${evidence_dir}")"
mkdir -p "${evidence_dir}"

if [[ ! "${source_commit}" =~ ^[0-9a-f]{40}$ ]] ||
  [[ ! "${baseline_apk_sha256}" =~ ^[0-9a-f]{64}$ ]] ||
  [[ ! "${run_id}" =~ ^[0-9]+$ ]] ||
  [[ ! "${run_attempt}" =~ ^[0-9]+$ ]] ||
  [[ ! "${legacy_bootstrap_authorized}" =~ ^(true|false)$ ]]; then
  echo "Staged acceptance identity inputs are malformed." >&2
  exit 1
fi

apk_url="$(jq -er '.android.apk.url | strings' "${candidate_manifest}")"
apk_name="$(basename "${apk_url}")"
manifest_apk_sha256="$(jq -er '.android.apk.sha256 | strings' "${candidate_manifest}")"
manifest_apk_bytes="$(jq -er '.android.apk.bytes | numbers' "${candidate_manifest}")"
candidate_apk_sha256="$(sha256sum "${candidate_apk}" | cut -d ' ' -f 1)"
candidate_apk_bytes="$(wc -c < "${candidate_apk}" | tr -d ' ')"
expected_apk_url="https://github.com/${repository}/releases/download/${release_tag}/${apk_name}"
if [[ "${apk_url}" != "${expected_apk_url}" ]] ||
  [[ "$(basename "${candidate_apk}")" != "${apk_name}" ]] ||
  [[ "${candidate_apk_sha256}" != "${manifest_apk_sha256}" ]] ||
  [[ "${candidate_apk_bytes}" != "${manifest_apk_bytes}" ]]; then
  echo "The staged candidate APK is not the exact manifest-bound artifact." >&2
  exit 1
fi

control_root="$(mktemp -d)"
ca_key="${control_root}/ca.key"
ca_cert="${control_root}/ca.crt"
server_key="${control_root}/github.key"
server_csr="${control_root}/github.csr"
server_cert="${control_root}/github.crt"
server_extensions="${control_root}/github.ext"
original_hosts="${control_root}/hosts.original"
staged_hosts="${control_root}/hosts.staged"
request_log="${evidence_dir}/staged-endpoint-requests.jsonl"
server_log="${evidence_dir}/staged-endpoint-server.log"
server_pid=""
ca_device_path=""
hosts_installed=false
system_write_probe_path="/system/etc/.openaria-staged-write-probe"

wait_for_framework() {
  local framework_ready=false
  for attempt in $(seq 1 120); do
    local boot_completed
    boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "${boot_completed}" == "1" ]] && adb shell pm path android >/dev/null 2>&1; then
      framework_ready=true
      break
    fi
    sleep 2
  done
  if [[ "${framework_ready}" != "true" ]]; then
    echo "Android framework and package manager did not become ready." >&2
    return 1
  fi
}

system_is_writable() {
  # A mount-point mode check alone is insufficient with API 33 overlayfs. Verify
  # an actual create/delete operation in the system partition instead.
  adb shell rm -f "${system_write_probe_path}" >/dev/null 2>&1 || true
  if ! adb shell touch "${system_write_probe_path}" >/dev/null 2>&1; then
    adb shell rm -f "${system_write_probe_path}" >/dev/null 2>&1 || true
    return 1
  fi
  if ! adb shell test -f "${system_write_probe_path}" >/dev/null 2>&1; then
    adb shell rm -f "${system_write_probe_path}" >/dev/null 2>&1 || true
    return 1
  fi
  adb shell rm -f "${system_write_probe_path}" >/dev/null 2>&1 || true
  return 0
}

ensure_system_writable() {
  local remount_output
  local remount_attempt

  # `adb remount` on API 33 can successfully configure overlayfs while leaving
  # /system read-only until the next boot. Probe after every remount and reboot
  # before attempting any copy when the probe fails.
  for remount_attempt in 1 2 3; do
    adb root >/dev/null
    adb wait-for-device
    remount_output="$(adb remount 2>&1)" || {
      printf '%s\n' "${remount_output}" >&2
      return 1
    }
    printf '%s\n' "${remount_output}"
    if system_is_writable; then
      return 0
    fi
    if [[ "${remount_attempt}" -eq 3 ]]; then
      echo "Android /system remained read-only after bounded overlayfs remount attempts." >&2
      return 1
    fi
    echo "Android /system overlayfs is not writable yet; rebooting before retry ${remount_attempt}/3." >&2
    adb reboot
    adb wait-for-device
    wait_for_framework
  done
}

cleanup() {
  status=$?
  cleanup_status=0
  trap - EXIT
  set +e
  if [[ -n "${server_pid}" ]]; then
    sudo kill "${server_pid}" >/dev/null 2>&1
    wait "${server_pid}" >/dev/null 2>&1
  fi
  if [[ "${hosts_installed}" == "true" ]]; then
    if ensure_system_writable >/dev/null 2>&1 &&
      adb push "${original_hosts}" /data/local/tmp/openaria-hosts.original >/dev/null 2>&1 &&
      adb shell cp /data/local/tmp/openaria-hosts.original /system/etc/hosts >/dev/null 2>&1 &&
      adb shell cmp /data/local/tmp/openaria-hosts.original /system/etc/hosts >/dev/null 2>&1; then
      if [[ -n "${ca_device_path}" ]]; then
        adb shell rm -f "${ca_device_path}" >/dev/null 2>&1 || cleanup_status=1
      fi
      adb shell rm -f /data/local/tmp/openaria-hosts.original /data/local/tmp/openaria-hosts.staged /data/local/tmp/openaria-staged-ca.0 >/dev/null 2>&1 || true
    else
      cleanup_status=1
      echo "Failed to restore Android system files after staged acceptance." >&2
    fi
  fi
  rm -rf "${control_root}"
  if [[ "${status}" -eq 0 && "${cleanup_status}" -ne 0 ]]; then
    status=1
  fi
  exit "${status}"
}
trap cleanup EXIT

openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 2 \
  -subj "/CN=OpenAria staged update acceptance CA" \
  -keyout "${ca_key}" -out "${ca_cert}" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -sha256 \
  -subj "/CN=github.com" \
  -keyout "${server_key}" -out "${server_csr}" >/dev/null 2>&1
cat > "${server_extensions}" <<'EOF'
subjectAltName=DNS:github.com
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
EOF
openssl x509 -req -sha256 -days 2 \
  -in "${server_csr}" -CA "${ca_cert}" -CAkey "${ca_key}" -CAcreateserial \
  -extfile "${server_extensions}" -out "${server_cert}" >/dev/null 2>&1
ca_hash="$(openssl x509 -subject_hash_old -in "${ca_cert}" -noout)"
ca_device_path="/system/etc/security/cacerts/${ca_hash}.0"

ensure_system_writable
adb pull /system/etc/hosts "${original_hosts}" >/dev/null
if grep -Eq '(^|[[:space:]])github\.com([[:space:]]|$)' "${original_hosts}"; then
  echo "Emulator hosts already overrides github.com; refusing ambiguous interception." >&2
  exit 1
fi
cp "${original_hosts}" "${staged_hosts}"
printf '\n10.0.2.2 github.com\n' >> "${staged_hosts}"
adb push "${staged_hosts}" /data/local/tmp/openaria-hosts.staged >/dev/null
adb push "${ca_cert}" /data/local/tmp/openaria-staged-ca.0 >/dev/null
hosts_installed=true
adb shell cp /data/local/tmp/openaria-hosts.staged /system/etc/hosts
adb shell cp /data/local/tmp/openaria-staged-ca.0 "${ca_device_path}"
adb shell chmod 0644 /system/etc/hosts "${ca_device_path}"
adb shell cmp /data/local/tmp/openaria-hosts.staged /system/etc/hosts >/dev/null
adb shell cmp /data/local/tmp/openaria-staged-ca.0 "${ca_device_path}" >/dev/null
adb reboot
adb wait-for-device
wait_for_framework
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell test -f "${ca_device_path}"
adb shell "grep -F -- '10.0.2.2 github.com' /system/etc/hosts" >/dev/null

touch "${request_log}" "${server_log}"
sudo python3 "$(dirname "$0")/android-staged-update-server.py" \
  --bind 0.0.0.0 \
  --port 443 \
  --cert "${server_cert}" \
  --key "${server_key}" \
  --manifest "${candidate_manifest}" \
  --apk "${candidate_apk}" \
  --repository "${repository}" \
  --release-tag "${release_tag}" \
  --request-log "${request_log}" \
  > "${server_log}" 2>&1 &
server_pid=$!

manifest_url="https://github.com/${repository}/releases/latest/download/android-update.json"
for attempt in $(seq 1 20); do
  if curl --fail --silent --show-error --noproxy '*' \
    --cacert "${ca_cert}" \
    --resolve github.com:443:127.0.0.1 \
    "${manifest_url}" \
    --output "${control_root}/served-manifest.json" \
    && cmp --silent "${candidate_manifest}" "${control_root}/served-manifest.json"; then
    break
  fi
  if [[ "${attempt}" -eq 20 ]]; then
    echo "Controlled staged TLS endpoint did not become ready." >&2
    exit 1
  fi
  sleep 1
done
sudo truncate -s 0 "${request_log}"

python3 "$(dirname "$0")/android-in-app-update-acceptance.py" \
  --repository "${repository}" \
  --release-tag "${release_tag}" \
  --expected-version-name "${expected_version_name}" \
  --expected-version-code "${expected_version_code}" \
  --baseline-tag "${baseline_tag}" \
  --baseline-version-name "${baseline_version_name}" \
  --baseline-version-code "${baseline_version_code}" \
  --baseline-apk-name "${baseline_apk_name}" \
  --baseline-release-id "${baseline_release_id}" \
  --baseline-apk-sha256 "${baseline_apk_sha256}" \
  --legacy-bootstrap-authorized "${legacy_bootstrap_authorized}" \
  --candidate-manifest-path "${candidate_manifest}" \
  --candidate-apk-sha256 "${candidate_apk_sha256}" \
  --source-commit "${source_commit}" \
  --run-id "${run_id}" \
  --run-attempt "${run_attempt}" \
  --evidence-dir "${evidence_dir}"

manifest_path="/${repository}/releases/latest/download/android-update.json"
apk_path="/${repository}/releases/download/${release_tag}/${apk_name}"
candidate_manifest_sha256="$(sha256sum "${candidate_manifest}" | cut -d ' ' -f 1)"
candidate_manifest_bytes="$(wc -c < "${candidate_manifest}" | tr -d ' ')"
manifest_get_count="$(jq -s --arg path "${manifest_path}" '[.[] | select(.method == "GET" and .path == $path and .status == 200)] | length' "${request_log}")"
apk_head_count="$(jq -s --arg path "${apk_path}" '[.[] | select(.method == "HEAD" and .path == $path and .status == 200)] | length' "${request_log}")"
apk_get_count="$(jq -s --arg path "${apk_path}" '[.[] | select(.method == "GET" and .path == $path and .status == 200)] | length' "${request_log}")"
unexpected_count="$(jq -s \
  --arg manifest "${manifest_path}" \
  --arg apk "${apk_path}" \
  --arg manifest_sha256 "${candidate_manifest_sha256}" \
  --arg apk_sha256 "${candidate_apk_sha256}" \
  --argjson manifest_bytes "${candidate_manifest_bytes}" \
  --argjson apk_bytes "${candidate_apk_bytes}" \
  '[.[] | select(
    .host != "github.com" or
    .status != 200 or
    (if .path == $manifest then
      .method != "GET" or .sha256 != $manifest_sha256 or .bytes != $manifest_bytes
    elif .path == $apk then
      (.method != "GET" and .method != "HEAD") or .sha256 != $apk_sha256 or .bytes != $apk_bytes
    else true end)
  )] | length' "${request_log}")"
if [[ "${manifest_get_count}" -lt 1 ]] ||
  [[ "${apk_head_count}" -lt 1 ]] ||
  [[ "${apk_get_count}" -lt 1 ]] ||
  [[ "${unexpected_count}" -ne 0 ]]; then
  echo "Production updater did not complete the exact closed staged endpoint request sequence." >&2
  exit 1
fi

acceptance_evidence="${evidence_dir}/android-in-app-update-evidence.json"
jq -e \
  --arg run_id "${run_id}" \
  --arg run_attempt "${run_attempt}" \
  --arg source_commit "${source_commit}" \
  --arg baseline_release_id "${baseline_release_id}" \
  --arg baseline_sha "${baseline_apk_sha256}" \
  --arg candidate_sha "${candidate_apk_sha256}" \
  --argjson legacy_bootstrap_authorized "${legacy_bootstrap_authorized}" \
  '.actionsRunId == $run_id and
   .actionsRunAttempt == $run_attempt and
   .sourceCommit == $source_commit and
   (.baseline.releaseId | tostring) == $baseline_release_id and
   .baseline.apkSha256 == $baseline_sha and
   .baseline.legacyBootstrapAuthorized == $legacy_bootstrap_authorized and
   .candidate.apkSha256 == $candidate_sha and
   .flow.stagedCandidateManifestAccessed == true and
   .flow.candidateDownloadedByBaselineApp == true and
   .flow.manualCandidateDownload == false and
   .flow.installedPackageUpgraded == true' \
  "${acceptance_evidence}" >/dev/null

jq -n \
  --arg schema "openaria.echo.mobile.staged-endpoint-evidence.v1" \
  --arg repository "${repository}" \
  --arg release_tag "${release_tag}" \
  --arg source_commit "${source_commit}" \
  --arg run_id "${run_id}" \
  --arg run_attempt "${run_attempt}" \
  --arg ca_sha256 "$(sha256sum "${ca_cert}" | cut -d ' ' -f 1)" \
  --arg manifest_sha256 "${candidate_manifest_sha256}" \
  --arg apk_sha256 "${candidate_apk_sha256}" \
  --argjson legacy_bootstrap_authorized "${legacy_bootstrap_authorized}" \
  --argjson manifest_get_count "${manifest_get_count}" \
  --argjson apk_head_count "${apk_head_count}" \
  --argjson apk_get_count "${apk_get_count}" \
  '{schema: $schema, repository: $repository, releaseTag: $release_tag,
    sourceCommit: $source_commit, actionsRunId: $run_id,
    actionsRunAttempt: $run_attempt, tls: {hostname: "github.com",
      ephemeralSystemCaSha256: $ca_sha256},
    candidate: {manifestSha256: $manifest_sha256, apkSha256: $apk_sha256},
    legacyBootstrapAuthorized: $legacy_bootstrap_authorized,
    productionUpdaterRequests: {manifestGet: $manifest_get_count,
      apkHead: $apk_head_count, apkGet: $apk_get_count}}' \
  > "${evidence_dir}/staged-endpoint-evidence.json"
