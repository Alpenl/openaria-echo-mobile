#!/usr/bin/env bash
# ShellCheck cannot see functions invoked through mutation/restore callbacks or the EXIT trap.
# shellcheck disable=SC2317
set -uo pipefail

readonly package_name="com.openaria.openaria_echo_mobile"
readonly test_class="com.openaria.openaria_echo_mobile.CurrentUiVisualGateTest"
readonly device_evidence_root="/data/local/tmp/openaria-current-ui"
readonly host_evidence_root="${1:-android-current-ui-evidence}"
readonly adb_bin="${OPENARIA_UI_GATE_ADB:-adb}"
readonly gradlew_bin="${OPENARIA_UI_GATE_GRADLEW:-./gradlew}"
readonly poll_attempts="${OPENARIA_UI_GATE_MAX_ATTEMPTS:-30}"
readonly poll_interval_seconds="${OPENARIA_UI_GATE_POLL_INTERVAL_SECONDS:-1}"
readonly convergence_failure=70
readonly missing_test_results_failure=71
readonly missing_evidence_failure=72
readonly state_capture_failure=73
readonly checksum_failure=74
readonly evidence_identity_failure=75
readonly profiles=(small_gesture small_three_button landscape_gesture cutout_three_button)
readonly cutout_overlay_prefix="com.android.internal.display.cutout.emulation."
readonly navigation_gestural_overlay="com.android.internal.systemui.navbar.gestural"
readonly navigation_three_button_overlay="com.android.internal.systemui.navbar.threebutton"
readonly navigation_two_button_overlay="com.android.internal.systemui.navbar.twobutton"

snapshot_complete=0
initial_physical_size=""
initial_size_override=""
initial_effective_size=""
initial_physical_density=""
initial_density_override=""
initial_effective_density=""
initial_accelerometer_rotation=""
initial_user_rotation=""
initial_surface_rotation=""
initial_navigation_overlays=""
initial_navigation_mode=""
initial_cutout_overlays=""
initial_cutout_state=""

profile_expected_wm_size=""
profile_expected_window_width=""
profile_expected_window_height=""
profile_expected_density=""
profile_expected_rotation=""
profile_expected_navigation_overlay=""
profile_expected_navigation_mode=""
profile_expected_cutout_overlays=""
profile_expected_cutout_state=""

evidence_png_status=0
evidence_json_status=0
evidence_results_status=0
evidence_state_status=0
evidence_identity_status=0

if [[ ! "$poll_attempts" =~ ^[1-9][0-9]*$ ]]; then
  printf 'OPENARIA_UI_GATE_MAX_ATTEMPTS must be a positive integer; got %s\n' "$poll_attempts" >&2
  exit 64
fi
if [[ ! "$poll_interval_seconds" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  printf 'OPENARIA_UI_GATE_POLL_INTERVAL_SECONDS must be a non-negative number; got %s\n' "$poll_interval_seconds" >&2
  exit 64
fi
case "$host_evidence_root" in
  "" | / | . | .. | ../* | */../* | */..)
    printf 'Refusing unsafe Android UI evidence path: %s\n' "$host_evidence_root" >&2
    exit 64
    ;;
esac

mkdir -p "$host_evidence_root" || exit $missing_evidence_failure

adb_shell() {
  "$adb_bin" shell "$@"
}

wm_output() {
  local kind="$1"
  adb_shell wm "$kind" | tr -d '\r'
}

wm_value() {
  local kind="$1"
  local label="$2"
  local output value
  output="$(wm_output "$kind")" || return $?
  value="$(printf '%s\n' "$output" | sed -n "s/^[[:space:]]*${label} ${kind}:[[:space:]]*//p" | tail -n 1)"
  case "$kind" in
    size) [[ -z "$value" || "$value" =~ ^[0-9]+x[0-9]+$ ]] || return 1 ;;
    density) [[ -z "$value" || "$value" =~ ^[0-9]+$ ]] || return 1 ;;
  esac
  printf '%s\n' "$value"
}

current_physical_size() { wm_value size Physical; }
current_override_size() { wm_value size Override; }
current_physical_density() { wm_value density Physical; }
current_override_density() { wm_value density Override; }

current_effective_size() {
  local value
  value="$(current_override_size)" || return $?
  if [[ -z "$value" ]]; then value="$(current_physical_size)" || return $?; fi
  printf '%s\n' "$value"
}

current_effective_density() {
  local value
  value="$(current_override_density)" || return $?
  if [[ -z "$value" ]]; then value="$(current_physical_density)" || return $?; fi
  printf '%s\n' "$value"
}

current_setting() {
  local name="$1"
  local value
  value="$(adb_shell settings get system "$name" | tr -d '\r[:space:]')" || return $?
  [[ "$value" =~ ^[0-3]$ ]] || return 1
  printf '%s\n' "$value"
}

current_surface_rotation() {
  local output value
  output="$(adb_shell dumpsys input | tr -d '\r')" || return $?
  value="$(printf '%s\n' "$output" | sed -n 's/.*SurfaceOrientation:[[:space:]]*\([0-3]\).*/\1/p' | head -n 1)"
  if [[ -z "$value" ]]; then
    output="$(adb_shell dumpsys window displays | tr -d '\r')" || return $?
    value="$(
      printf '%s\n' "$output" |
        sed -n 's/^[[:space:]]*mRotation=\([0-3]\)[[:space:]]\+mDeferredRotationPauseCount=.*/\1/p' |
        LC_ALL=C sort -u
    )"
  fi
  [[ "$value" =~ ^[0-3]$ ]] || return 1
  printf '%s\n' "$value"
}

enabled_overlays_with_prefix() {
  local prefix="$1"
  local output
  output="$(adb_shell cmd overlay list | tr -d '\r')" || return $?
  printf '%s\n' "$output" | awk -v prefix="$prefix" \
    '$1 ~ /^\[[xX]\]$/ && index($2, prefix) == 1 { print $2 }' | LC_ALL=C sort
}

enabled_navigation_mode_overlays() {
  local output
  output="$(adb_shell cmd overlay list | tr -d '\r')" || return $?
  printf '%s\n' "$output" | awk \
    -v gestural="$navigation_gestural_overlay" \
    -v three_button="$navigation_three_button_overlay" \
    -v two_button="$navigation_two_button_overlay" \
    '$1 ~ /^\[[xX]\]$/ && ($2 == gestural || $2 == three_button || $2 == two_button) { print $2 }' | \
    LC_ALL=C sort
}

current_navigation_mode() {
  local output value
  output="$(adb_shell cmd overlay lookup android android:integer/config_navBarInteractionMode 2>/dev/null | tr -d '\r')" || return $?
  value="$(printf '%s\n' "$output" | awk '/^[[:space:]]*[0-9]+[[:space:]]*$/ { gsub(/[[:space:]]/, ""); value=$0 } END { print value }')"
  [[ "$value" =~ ^[0-9]+$ ]] || return 1
  printf '%s\n' "$value"
}

current_cutout_resource_state() {
  local output command_status compact lowered
  output="$(adb_shell cmd overlay lookup android android:string/config_mainBuiltInDisplayCutout 2>&1 | tr -d '\r')"
  command_status=$?
  compact="$(printf '%s' "$output" | tr -d '[:space:]')"
  lowered="${compact,,}"
  if (( command_status != 0 )); then
    case "${output,,}" in
      *"bad resource"* | *"not found"* | *"does not exist"*) printf 'absent\n'; return 0 ;;
      *) return "$command_status" ;;
    esac
  fi
  case "$lowered" in
    "" | '""' | @null | null | none) printf 'absent\n' ;;
    *) printf 'present\n' ;;
  esac
}

display_list() {
  local value="$1"
  if [[ -z "$value" ]]; then printf '<none>'; else printf '%s' "${value//$'\n'/,}"; fi
}

capture_probe() {
  local label="$1"
  shift
  local command_status
  printf '%s\n' "--- ${label} ---"
  "$@"
  command_status=$?
  if (( command_status != 0 )); then
    printf 'probe_exit=%d\n' "$command_status"
    if (( capture_status == 0 )); then capture_status=$command_status; fi
  fi
}

capture_device_state() {
  local label="$1"
  local destination="$2"
  local capture_status=0
  {
    printf 'label=%s\n' "$label"
    printf 'captured_at_utc=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    capture_probe 'wm size' adb_shell wm size
    capture_probe 'wm density' adb_shell wm density
    capture_probe 'accelerometer_rotation' adb_shell settings get system accelerometer_rotation
    capture_probe 'user_rotation' adb_shell settings get system user_rotation
    capture_probe 'input rotation' adb_shell dumpsys input
    capture_probe 'overlay list' adb_shell cmd overlay list
    capture_probe 'navigation interaction resource' adb_shell cmd overlay lookup android android:integer/config_navBarInteractionMode
    capture_probe 'normalized cutout resource state' current_cutout_resource_state
    capture_probe 'window displays' adb_shell dumpsys window displays
    capture_probe 'window focus' adb_shell dumpsys window windows
  } > "$destination" 2>&1
  return "$capture_status"
}

write_state_gate() {
  local destination="$1" label="$2" expected_size="$3" expected_density="$4"
  local expected_rotation="$5" expected_accelerometer="$6" expected_user_rotation="$7"
  local expected_navigation_overlays="$8" expected_navigation_mode="$9"
  local expected_cutout_overlays="${10}" expected_cutout_state="${11}"
  local actual_size actual_density actual_rotation actual_accelerometer actual_user_rotation
  local actual_navigation_overlays actual_navigation_mode actual_cutout_overlays actual_cutout_state
  local probe_status gate_failed=0

  actual_size="$(current_effective_size)"; probe_status=$?
  if (( probe_status != 0 )); then actual_size="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_density="$(current_effective_density)"; probe_status=$?
  if (( probe_status != 0 )); then actual_density="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_rotation="$(current_surface_rotation)"; probe_status=$?
  if (( probe_status != 0 )); then actual_rotation="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_accelerometer="$(current_setting accelerometer_rotation)"; probe_status=$?
  if (( probe_status != 0 )); then actual_accelerometer="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_user_rotation="$(current_setting user_rotation)"; probe_status=$?
  if (( probe_status != 0 )); then actual_user_rotation="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_navigation_overlays="$(enabled_navigation_mode_overlays)"; probe_status=$?
  if (( probe_status != 0 )); then actual_navigation_overlays="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_navigation_mode="$(current_navigation_mode)"; probe_status=$?
  if (( probe_status != 0 )); then actual_navigation_mode="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_cutout_overlays="$(enabled_overlays_with_prefix "$cutout_overlay_prefix")"; probe_status=$?
  if (( probe_status != 0 )); then actual_cutout_overlays="<probe-error:${probe_status}>"; gate_failed=1; fi
  actual_cutout_state="$(current_cutout_resource_state)"; probe_status=$?
  if (( probe_status != 0 )); then actual_cutout_state="<probe-error:${probe_status}>"; gate_failed=1; fi

  [[ "$actual_size" == "$expected_size" ]] || gate_failed=1
  [[ "$actual_density" == "$expected_density" ]] || gate_failed=1
  [[ "$actual_rotation" == "$expected_rotation" ]] || gate_failed=1
  [[ "$actual_accelerometer" == "$expected_accelerometer" ]] || gate_failed=1
  [[ "$actual_user_rotation" == "$expected_user_rotation" ]] || gate_failed=1
  [[ "$actual_navigation_overlays" == "$expected_navigation_overlays" ]] || gate_failed=1
  [[ "$actual_navigation_mode" == "$expected_navigation_mode" ]] || gate_failed=1
  [[ "$actual_cutout_overlays" == "$expected_cutout_overlays" ]] || gate_failed=1
  [[ "$actual_cutout_state" == "$expected_cutout_state" ]] || gate_failed=1

  {
    printf 'label=%s\n' "$label"
    printf 'expected_wm_size=%s\nactual_wm_size=%s\n' "$expected_size" "$actual_size"
    printf 'expected_density=%s\nactual_density=%s\n' "$expected_density" "$actual_density"
    printf 'expected_surface_rotation=%s\nactual_surface_rotation=%s\n' "$expected_rotation" "$actual_rotation"
    printf 'expected_accelerometer_rotation=%s\nactual_accelerometer_rotation=%s\n' "$expected_accelerometer" "$actual_accelerometer"
    printf 'expected_user_rotation=%s\nactual_user_rotation=%s\n' "$expected_user_rotation" "$actual_user_rotation"
    printf 'expected_navigation_overlays=%s\nactual_navigation_overlays=%s\n' \
      "$(display_list "$expected_navigation_overlays")" "$(display_list "$actual_navigation_overlays")"
    printf 'expected_navigation_mode=%s\nactual_navigation_mode=%s\n' "$expected_navigation_mode" "$actual_navigation_mode"
    printf 'expected_cutout_overlays=%s\nactual_cutout_overlays=%s\n' \
      "$(display_list "$expected_cutout_overlays")" "$(display_list "$actual_cutout_overlays")"
    printf 'expected_cutout_resource=%s\nactual_cutout_resource=%s\n' "$expected_cutout_state" "$actual_cutout_state"
    if (( gate_failed == 0 )); then printf 'gate=PASS\n'; else printf 'gate=FAIL\n'; fi
  } > "$destination"
  return "$gate_failed"
}

wait_for_state_convergence() {
  local label="$1" state_path="$2" convergence_log="$3" expected_size="$4"
  local expected_density="$5" expected_rotation="$6" expected_accelerometer="$7"
  local expected_user_rotation="$8" expected_navigation_overlays="$9"
  local expected_navigation_mode="${10}" expected_cutout_overlays="${11}" expected_cutout_state="${12}"
  local attempt gate_status temporary_state="${state_path}.tmp"
  : > "$convergence_log"
  for (( attempt=1; attempt<=poll_attempts; attempt+=1 )); do
    write_state_gate "$temporary_state" "$label" "$expected_size" "$expected_density" \
      "$expected_rotation" "$expected_accelerometer" "$expected_user_rotation" \
      "$expected_navigation_overlays" "$expected_navigation_mode" \
      "$expected_cutout_overlays" "$expected_cutout_state"
    gate_status=$?
    { printf 'attempt=%d/%d\n' "$attempt" "$poll_attempts"; sed 's/^/  /' "$temporary_state"; } >> "$convergence_log"
    if (( gate_status == 0 )); then
      cp "$temporary_state" "$state_path" || return $state_capture_failure
      rm -f "$temporary_state"
      return 0
    fi
    if (( attempt < poll_attempts )); then sleep "$poll_interval_seconds"; fi
  done
  cp "$temporary_state" "$state_path" || return $state_capture_failure
  rm -f "$temporary_state"
  return "$convergence_failure"
}

disable_enabled_overlays() {
  local prefix="$1" overlays overlay command_status first_failure=0
  overlays="$(enabled_overlays_with_prefix "$prefix")" || return $?
  [[ -z "$overlays" ]] && return 0
  while IFS= read -r overlay; do
    [[ -z "$overlay" ]] && continue
    adb_shell cmd overlay disable "$overlay"
    command_status=$?
    if (( command_status != 0 && first_failure == 0 )); then first_failure=$command_status; fi
  done <<< "$overlays"
  return "$first_failure"
}

disable_enabled_navigation_mode_overlays() {
  local overlays overlay command_status first_failure=0
  overlays="$(enabled_navigation_mode_overlays)" || return $?
  [[ -z "$overlays" ]] && return 0
  while IFS= read -r overlay; do
    [[ -z "$overlay" ]] && continue
    adb_shell cmd overlay disable "$overlay"
    command_status=$?
    if (( command_status != 0 && first_failure == 0 )); then first_failure=$command_status; fi
  done <<< "$overlays"
  return "$first_failure"
}

load_profile_expectations() {
  local profile="$1" natural_width natural_height
  case "$profile" in
    small_gesture)
      profile_expected_wm_size="720x1280"; profile_expected_window_width="720"; profile_expected_window_height="1280"
      profile_expected_density="320"; profile_expected_rotation="0"
      profile_expected_navigation_overlay="com.android.internal.systemui.navbar.gestural"; profile_expected_navigation_mode="2"
      profile_expected_cutout_overlays=""; profile_expected_cutout_state="absent"
      ;;
    small_three_button)
      profile_expected_wm_size="720x1280"; profile_expected_window_width="720"; profile_expected_window_height="1280"
      profile_expected_density="320"; profile_expected_rotation="0"
      profile_expected_navigation_overlay="com.android.internal.systemui.navbar.threebutton"; profile_expected_navigation_mode="0"
      profile_expected_cutout_overlays=""; profile_expected_cutout_state="absent"
      ;;
    landscape_gesture)
      profile_expected_wm_size="720x1280"; profile_expected_window_width="1280"; profile_expected_window_height="720"
      profile_expected_density="320"; profile_expected_rotation="1"
      profile_expected_navigation_overlay="com.android.internal.systemui.navbar.gestural"; profile_expected_navigation_mode="2"
      profile_expected_cutout_overlays=""; profile_expected_cutout_state="absent"
      ;;
    cutout_three_button)
      profile_expected_wm_size="$initial_physical_size"
      natural_width="${initial_physical_size%x*}"; natural_height="${initial_physical_size#*x}"
      profile_expected_window_width="$natural_width"; profile_expected_window_height="$natural_height"
      profile_expected_density="$initial_physical_density"; profile_expected_rotation="0"
      profile_expected_navigation_overlay="com.android.internal.systemui.navbar.threebutton"; profile_expected_navigation_mode="0"
      profile_expected_cutout_overlays="com.android.internal.display.cutout.emulation.tall"; profile_expected_cutout_state="present"
      ;;
    *) printf 'Unknown current UI profile: %s\n' "$profile" >&2; return 64 ;;
  esac
}

run_mutation() {
  local description="$1"
  shift
  local command_status
  printf 'mutation=%s\n' "$description"
  "$@"
  command_status=$?
  if (( command_status != 0 && mutation_status == 0 )); then mutation_status=$command_status; fi
}

configure_profile() {
  local profile="$1" mutation_status=0 convergence_status
  load_profile_expectations "$profile" || return $?
  run_mutation 'disable active cutout overlays' disable_enabled_overlays "$cutout_overlay_prefix"
  case "$profile" in
    small_gesture | small_three_button | landscape_gesture)
      run_mutation 'set 720x1280 wm override' adb_shell wm size 720x1280
      run_mutation 'set 320 dpi override' adb_shell wm density 320
      ;;
    cutout_three_button)
      run_mutation 'reset wm size' adb_shell wm size reset
      run_mutation 'reset wm density' adb_shell wm density reset
      ;;
  esac
  run_mutation 'disable accelerometer rotation' adb_shell settings put system accelerometer_rotation 0
  run_mutation 'set user rotation' adb_shell settings put system user_rotation "$profile_expected_rotation"
  run_mutation 'enable requested navigation overlay' \
    adb_shell cmd overlay enable-exclusive --category "$profile_expected_navigation_overlay"
  if [[ "$profile_expected_cutout_state" == "present" ]]; then
    run_mutation 'enable requested cutout overlay' \
      adb_shell cmd overlay enable-exclusive --category "$profile_expected_cutout_overlays"
  fi
  run_mutation 'wait for adb after overlay changes' "$adb_bin" wait-for-device
  adb_shell am force-stop "$package_name" >/dev/null 2>&1 || true
  wait_for_state_convergence "$profile" \
    "$host_evidence_root/${profile}-preflight-state.txt" "$host_evidence_root/${profile}-convergence.log" \
    "$profile_expected_wm_size" "$profile_expected_density" "$profile_expected_rotation" 0 \
    "$profile_expected_rotation" "$profile_expected_navigation_overlay" "$profile_expected_navigation_mode" \
    "$profile_expected_cutout_overlays" "$profile_expected_cutout_state"
  convergence_status=$?
  if (( mutation_status != 0 )); then return "$mutation_status"; fi
  return "$convergence_status"
}

copy_gradle_results() {
  local profile="$1" result_root="app/build/outputs/androidTest-results"
  local destination="$host_evidence_root/${profile}-androidTest-results"
  rm -rf "$destination"
  mkdir -p "$destination" || return $missing_test_results_failure
  if [[ ! -d "$result_root" ]]; then
    printf 'No Android test results were produced for %s.\n' "$profile" > "$destination/NO_ANDROID_TEST_RESULTS.txt"
    return $missing_test_results_failure
  fi
  cp -a "$result_root/." "$destination/"
}

verify_pulled_evidence() {
  local profile="$1"
  local screenshot_path="$host_evidence_root/${profile}.png"
  local geometry_path="$host_evidence_root/${profile}.json"
  python3 - "$profile" "$package_name" "$screenshot_path" "$geometry_path" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

profile, package_name, screenshot_name, geometry_name = sys.argv[1:]
screenshot_path = Path(screenshot_name)
geometry = json.loads(Path(geometry_name).read_text(encoding="utf-8"))
actual_sha256 = hashlib.sha256(screenshot_path.read_bytes()).hexdigest()

if geometry.get("schema") != "openaria.echo.mobile.current-ui-evidence.v1":
    raise SystemExit("unexpected current UI evidence schema")
if geometry.get("profile") != profile:
    raise SystemExit("geometry profile does not match the requested profile")
if geometry.get("targetPackage") != package_name:
    raise SystemExit("geometry target package does not match the current APK")
if geometry.get("targetWindowFocused") is not True:
    raise SystemExit("geometry did not record a focused target window")
if geometry.get("screenshotPngSha256") != actual_sha256:
    raise SystemExit("pulled PNG does not match the Bitmap hash recorded by instrumentation")
PY
}

capture_profile_evidence() {
  local profile="$1"
  evidence_png_status=0; evidence_json_status=0; evidence_identity_status=0
  evidence_results_status=0; evidence_state_status=0
  "$adb_bin" pull "$device_evidence_root/${profile}.png" "$host_evidence_root/${profile}.png"
  evidence_png_status=$?
  "$adb_bin" pull "$device_evidence_root/${profile}.json" "$host_evidence_root/${profile}.json"
  evidence_json_status=$?
  if (( evidence_png_status == 0 && evidence_json_status == 0 )); then
    verify_pulled_evidence "$profile"
    evidence_identity_status=$?
    if (( evidence_identity_status != 0 )); then evidence_identity_status=$evidence_identity_failure; fi
  else
    evidence_identity_status=$evidence_identity_failure
  fi
  copy_gradle_results "$profile"; evidence_results_status=$?
  capture_device_state "$profile-final" "$host_evidence_root/${profile}-final-device-state.txt"
  evidence_state_status=$?
}

run_profile() {
  local profile="$1" configuration_status instrumentation_status primary_status=0
  local result_root="app/build/outputs/androidTest-results"
  printf 'profile=%s\n' "$profile"
  rm -rf "$result_root"
  configure_profile "$profile"; configuration_status=$?
  if (( configuration_status != 0 )); then
    primary_status=$configuration_status; instrumentation_status=125
    printf 'Instrumentation skipped because profile convergence/configuration failed with %d.\n' "$configuration_status" >&2
  else
    "$gradlew_bin" connectedDebugAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class="$test_class" \
      -Pandroid.testInstrumentationRunnerArguments.visualProfile="$profile" \
      -Pandroid.testInstrumentationRunnerArguments.expectedWindowWidthPx="$profile_expected_window_width" \
      -Pandroid.testInstrumentationRunnerArguments.expectedWindowHeightPx="$profile_expected_window_height" \
      -Pandroid.testInstrumentationRunnerArguments.expectedDensityDpi="$profile_expected_density" \
      -Pandroid.testInstrumentationRunnerArguments.expectedRotation="$profile_expected_rotation"
    instrumentation_status=$?
    if (( instrumentation_status != 0 )); then primary_status=$instrumentation_status; fi
  fi
  capture_profile_evidence "$profile"
  if (( primary_status == 0 && evidence_png_status != 0 )); then primary_status=$evidence_png_status; fi
  if (( primary_status == 0 && evidence_json_status != 0 )); then primary_status=$evidence_json_status; fi
  if (( primary_status == 0 && evidence_identity_status != 0 )); then primary_status=$evidence_identity_status; fi
  if (( primary_status == 0 && evidence_results_status != 0 )); then primary_status=$evidence_results_status; fi
  if (( primary_status == 0 && evidence_state_status != 0 )); then primary_status=$evidence_state_status; fi
  {
    printf 'profile=%s\nconfiguration_status=%d\ninstrumentation_status=%d\n' "$profile" "$configuration_status" "$instrumentation_status"
    printf 'png_pull_status=%d\njson_pull_status=%d\n' "$evidence_png_status" "$evidence_json_status"
    printf 'evidence_identity_status=%d\n' "$evidence_identity_status"
    printf 'android_test_results_status=%d\nfinal_device_state_status=%d\n' "$evidence_results_status" "$evidence_state_status"
    printf 'profile_exit_status=%d\n' "$primary_status"
  } > "$host_evidence_root/${profile}-result.env"
  return "$primary_status"
}

snapshot_initial_state() {
  local snapshot_status=0 probe_status
  initial_physical_size="$(current_physical_size)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_size_override="$(current_override_size)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_effective_size="$(current_effective_size)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_physical_density="$(current_physical_density)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_density_override="$(current_override_density)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_effective_density="$(current_effective_density)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_accelerometer_rotation="$(current_setting accelerometer_rotation)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_user_rotation="$(current_setting user_rotation)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_surface_rotation="$(current_surface_rotation)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_navigation_overlays="$(enabled_navigation_mode_overlays)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_navigation_mode="$(current_navigation_mode)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_cutout_overlays="$(enabled_overlays_with_prefix "$cutout_overlay_prefix")"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  initial_cutout_state="$(current_cutout_resource_state)"; probe_status=$?; if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  capture_device_state initial "$host_evidence_root/initial-device-state.txt"; probe_status=$?
  if (( probe_status != 0 && snapshot_status == 0 )); then snapshot_status=$probe_status; fi
  {
    printf 'physical_size=%s\nsize_override=%s\neffective_size=%s\n' "$initial_physical_size" "${initial_size_override:-<none>}" "$initial_effective_size"
    printf 'physical_density=%s\ndensity_override=%s\neffective_density=%s\n' "$initial_physical_density" "${initial_density_override:-<none>}" "$initial_effective_density"
    printf 'accelerometer_rotation=%s\nuser_rotation=%s\nsurface_rotation=%s\n' "$initial_accelerometer_rotation" "$initial_user_rotation" "$initial_surface_rotation"
    printf 'navigation_overlays=%s\nnavigation_mode=%s\n' "$(display_list "$initial_navigation_overlays")" "$initial_navigation_mode"
    printf 'cutout_overlays=%s\ncutout_resource=%s\nsnapshot_status=%d\n' "$(display_list "$initial_cutout_overlays")" "$initial_cutout_state" "$snapshot_status"
  } > "$host_evidence_root/initial-state-snapshot.env"
  return "$snapshot_status"
}

enable_overlay_snapshot() {
  local overlays="$1" overlay command_status first_failure=0 first=1
  [[ -z "$overlays" ]] && return 0
  while IFS= read -r overlay; do
    [[ -z "$overlay" ]] && continue
    if (( first == 1 )); then adb_shell cmd overlay enable-exclusive --category "$overlay"; first=0
    else adb_shell cmd overlay enable "$overlay"; fi
    command_status=$?
    if (( command_status != 0 && first_failure == 0 )); then first_failure=$command_status; fi
  done <<< "$overlays"
  return "$first_failure"
}

wait_for_surface_rotation() {
  local expected="$1" attempt actual
  for (( attempt=1; attempt<=poll_attempts; attempt+=1 )); do
    actual="$(current_surface_rotation)" || actual="<probe-error>"
    [[ "$actual" == "$expected" ]] && return 0
    if (( attempt < poll_attempts )); then sleep "$poll_interval_seconds"; fi
  done
  return "$convergence_failure"
}

restore_step() {
  local description="$1"
  shift
  local command_status
  printf 'restore_step=%s\n' "$description"
  "$@"; command_status=$?
  printf 'restore_step_status=%d\n' "$command_status"
  if (( command_status != 0 && restore_status == 0 )); then restore_status=$command_status; fi
}

restore_initial_state() {
  local restore_status=0 convergence_status capture_status
  if [[ -z "$initial_size_override" ]]; then restore_step 'reset wm size override' adb_shell wm size reset
  else restore_step 'restore wm size override' adb_shell wm size "$initial_size_override"; fi
  if [[ -z "$initial_density_override" ]]; then restore_step 'reset wm density override' adb_shell wm density reset
  else restore_step 'restore wm density override' adb_shell wm density "$initial_density_override"; fi
  restore_step 'disable current navigation overlays' disable_enabled_navigation_mode_overlays
  restore_step 'restore initial navigation overlays' enable_overlay_snapshot "$initial_navigation_overlays"
  restore_step 'disable current cutout overlays' disable_enabled_overlays "$cutout_overlay_prefix"
  restore_step 'restore initial cutout overlays' enable_overlay_snapshot "$initial_cutout_overlays"
  restore_step 'temporarily disable accelerometer rotation' adb_shell settings put system accelerometer_rotation 0
  restore_step 'restore initial surface rotation' adb_shell settings put system user_rotation "$initial_surface_rotation"
  restore_step 'wait for initial surface rotation' wait_for_surface_rotation "$initial_surface_rotation"
  if [[ "$initial_accelerometer_rotation" == "1" ]]; then
    restore_step 'restore accelerometer rotation' adb_shell settings put system accelerometer_rotation 1
    restore_step 'restore saved user rotation while sensor mode is active' adb_shell settings put system user_rotation "$initial_user_rotation"
  else
    restore_step 'restore saved user rotation' adb_shell settings put system user_rotation "$initial_user_rotation"
    restore_step 'keep accelerometer rotation disabled' adb_shell settings put system accelerometer_rotation 0
  fi
  restore_step 'wait for adb after restoration' "$adb_bin" wait-for-device
  wait_for_state_convergence restored "$host_evidence_root/restored-state-gate.txt" \
    "$host_evidence_root/restored-state-convergence.log" "$initial_effective_size" "$initial_effective_density" \
    "$initial_surface_rotation" "$initial_accelerometer_rotation" "$initial_user_rotation" \
    "$initial_navigation_overlays" "$initial_navigation_mode" "$initial_cutout_overlays" "$initial_cutout_state"
  convergence_status=$?
  printf 'restore_convergence_status=%d\n' "$convergence_status"
  if (( convergence_status != 0 && restore_status == 0 )); then restore_status=$convergence_status; fi
  capture_device_state restored "$host_evidence_root/restored-device-state.txt"; capture_status=$?
  printf 'restored_device_state_status=%d\n' "$capture_status"
  if (( capture_status != 0 && restore_status == 0 )); then restore_status=$capture_status; fi
  return "$restore_status"
}

cleanup_on_exit() {
  local original_status=$? cleanup_status=0
  trap - EXIT INT TERM
  if (( snapshot_complete == 1 )); then
    restore_initial_state > "$host_evidence_root/restore.log" 2>&1
    cleanup_status=$?
  fi
  {
    printf 'original_exit_status=%d\ncleanup_exit_status=%d\n' "$original_status" "$cleanup_status"
    if (( original_status != 0 )); then printf 'final_exit_status=%d\n' "$original_status"
    else printf 'final_exit_status=%d\n' "$cleanup_status"; fi
  } > "$host_evidence_root/exit-summary.env"
  if (( original_status != 0 )); then exit "$original_status"; fi
  exit "$cleanup_status"
}

write_checksums() {
  local files=() file
  while IFS= read -r -d '' file; do files+=("$file"); done \
    < <(find "$host_evidence_root" -maxdepth 1 -type f \( -name '*.png' -o -name '*.json' \) -print0 | LC_ALL=C sort -z)
  if (( ${#files[@]} == 0 )); then
    printf 'No current APK PNG/JSON evidence files were collected.\n' > "$host_evidence_root/NO_CURRENT_UI_EVIDENCE.txt"
    return $checksum_failure
  fi
  sha256sum "${files[@]}" > "$host_evidence_root/SHA256SUMS.txt"
}

clear_previous_host_evidence() {
  local profile command_status first_failure=0
  rm -f \
    "$host_evidence_root/SHA256SUMS.txt" \
    "$host_evidence_root/NO_CURRENT_UI_EVIDENCE.txt" \
    "$host_evidence_root/exit-summary.env"
  command_status=$?
  if (( command_status != 0 )); then first_failure=$command_status; fi
  for profile in "${profiles[@]}"; do
    rm -f \
      "$host_evidence_root/${profile}.png" \
      "$host_evidence_root/${profile}.json" \
      "$host_evidence_root/${profile}-result.env" \
      "$host_evidence_root/${profile}-preflight-state.txt" \
      "$host_evidence_root/${profile}-final-device-state.txt" \
      "$host_evidence_root/${profile}-convergence.log" \
      "$host_evidence_root/${profile}-run.log"
    command_status=$?
    if (( command_status != 0 && first_failure == 0 )); then first_failure=$command_status; fi
    rm -rf "$host_evidence_root/${profile}-androidTest-results"
    command_status=$?
    if (( command_status != 0 && first_failure == 0 )); then first_failure=$command_status; fi
  done
  return "$first_failure"
}

clear_previous_host_evidence || exit $missing_evidence_failure
"$adb_bin" wait-for-device > "$host_evidence_root/initialization.log" 2>&1
initialization_status=$?
if (( initialization_status != 0 )); then exit "$initialization_status"; fi
snapshot_initial_state >> "$host_evidence_root/initialization.log" 2>&1
initialization_status=$?
if (( initialization_status != 0 )); then exit "$initialization_status"; fi
snapshot_complete=1
trap cleanup_on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

adb_shell rm -rf "$device_evidence_root" >> "$host_evidence_root/initialization.log" 2>&1
initialization_status=$?
if (( initialization_status == 0 )); then
  adb_shell mkdir -p "$device_evidence_root" >> "$host_evidence_root/initialization.log" 2>&1
  initialization_status=$?
fi
if (( initialization_status != 0 )); then exit "$initialization_status"; fi

overall_status=0
for profile in "${profiles[@]}"; do
  run_profile "$profile" > "$host_evidence_root/${profile}-run.log" 2>&1
  profile_status=$?
  sed -n '1,240p' "$host_evidence_root/${profile}-run.log"
  if (( profile_status != 0 && overall_status == 0 )); then overall_status=$profile_status; fi
done
write_checksums; checksum_status=$?
if (( checksum_status != 0 && overall_status == 0 )); then overall_status=$checksum_status; fi
exit "$overall_status"
