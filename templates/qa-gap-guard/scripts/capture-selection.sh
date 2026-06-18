#!/usr/bin/env bash
set -euo pipefail

json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/\\r}
  s=${s//$'\t'/\\t}
  printf '%s' "$s"
}

trim() {
  local s=$1
  s=${s#"${s%%[![:space:]]*}"}
  s=${s%"${s##*[![:space:]]}"}
  printf '%s' "$s"
}

ids_json='[]'
if [ "${FORGE_DECISION_SELECTED_GAP_IDS:-none}" != 'none' ]; then
  IFS=',' read -r -a ids <<< "${FORGE_DECISION_SELECTED_GAP_IDS}"
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

printf '{\n  "gap_selection_status": "%s",\n  "selected_gap_ids": %s,\n  "selection_notes": "%s"\n}\n' \
  "$(json_escape "${FORGE_DECISION_GAP_SELECTION_STATUS}")" \
  "$ids_json" \
  "$(json_escape "${FORGE_DECISION_SELECTION_NOTES}")" \
  > "$FORGE_OUTPUT_SELECTED_QA_GAPS"
