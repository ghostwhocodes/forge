#!/usr/bin/env bash
# Sourced by command scripts; inherits caller's shell options.

json_escape() {
  local s=$1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/\\n}
  s=${s//$'\r'/\\r}
  s=${s//$'\t'/\\t}
  printf '%s' "$s"
}

json_escape_file() {
  local path=$1
  local content
  content=$(cat "$path")
  json_escape "$content"
}
