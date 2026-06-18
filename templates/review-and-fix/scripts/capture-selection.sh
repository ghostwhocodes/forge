#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
. "$script_dir/lib.sh"

trim() {
  local s=$1
  s=${s#"${s%%[![:space:]]*}"}
  s=${s%"${s##*[![:space:]]}"}
  printf '%s' "$s"
}

ids_json='[]'
if [ "${FORGE_DECISION_SELECTED_FINDING_IDS:-none}" != 'none' ]; then
  IFS=',' read -r -a ids <<< "${FORGE_DECISION_SELECTED_FINDING_IDS}"
  ids_json='['
  first=1
  for id in "${ids[@]}"; do
    trimmed=$(trim "$id")
    escaped=$(json_escape "$trimmed")
    if [ $first -eq 1 ]; then
      first=0
    else
      ids_json+=','
    fi
    ids_json+="\"$escaped\""
  done
  ids_json+=']'
fi

printf '{\n  "finding_selection_status": "%s",\n  "selected_finding_ids": %s,\n  "selection_notes": "%s"\n}\n' \
  "$(json_escape "${FORGE_DECISION_FINDING_SELECTION_STATUS}")" \
  "$ids_json" \
  "$(json_escape "${FORGE_DECISION_SELECTION_NOTES}")" \
  > "$FORGE_OUTPUT_CURATED_FINDINGS"
