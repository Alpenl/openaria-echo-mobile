#!/usr/bin/env bash
set -euo pipefail

record_image="${1:-dogfood-output/screenshots/issue-003-result.png}"
roll_image="${2:-dogfood-output/screenshots/issue-002-step-roll.png}"

red_dot_y() {
    local image_path="$1"
    local crop_x="$2"
    local crop_y=1450

    convert "$image_path" -crop "100x300+${crop_x}+${crop_y}" txt:- \
        | awk -F '[,:]' -v offset="$crop_y" '
            /#FF3B2D/ { sum += $2; count += 1 }
            END {
                if (count == 0) {
                    exit 2
                }
                printf "%.0f", (sum / count) + offset
            }
        '
}

record_y="$(red_dot_y "$record_image" 190)"
roll_y="$(red_dot_y "$roll_image" 390)"
delta=$(( roll_y - record_y ))
if (( delta < 0 )); then
    delta=$(( -delta ))
fi

printf 'RECORD active-dot y: %s px\n' "$record_y"
printf 'ROLL active-dot y:   %s px\n' "$roll_y"
printf 'Navigation shift:    %s px\n' "$delta"

if (( delta > 4 )); then
    printf 'FAIL: bottom navigation moves by more than 4 px between modes.\n'
    exit 1
fi

printf 'PASS: bottom navigation remains on a stable baseline.\n'
