#!/bin/sh
# Writes the site operator's details for the Impressum and the privacy policy (ADR 0024) from the
# container's environment, so the address never enters the repository or the image. The web app reads
# them from /site-operator.json; without OPERATOR_NAME the file is absent (404) and both pages say the
# details are missing.
set -eu
mkdir -p /tmp/evmap
target=/tmp/evmap/site-operator.json

# JSON string: backslashes and quotes escaped, control characters dropped.
json() {
  printf '"%s"' "$(printf '%s' "$1" | tr -d '\000-\037' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g')"
}

if [ -n "${OPERATOR_NAME:-}" ]; then
  {
    printf '{"name":%s' "$(json "$OPERATOR_NAME")"
    printf ',"street":%s' "$(json "${OPERATOR_STREET:-}")"
    printf ',"city":%s' "$(json "${OPERATOR_CITY:-}")"
    printf ',"country":%s' "$(json "${OPERATOR_COUNTRY:-Deutschland}")"
    printf ',"email":%s' "$(json "${OPERATOR_EMAIL:-}")"
    if [ -n "${OPERATOR_PHONE:-}" ]; then
      printf ',"phone":%s' "$(json "$OPERATOR_PHONE")"
    fi
    printf '}\n'
  } > "$target"
  echo "site-operator.json written"
  if [ -z "${OPERATOR_STREET:-}" ] || [ -z "${OPERATOR_CITY:-}" ] || [ -z "${OPERATOR_EMAIL:-}" ]; then
    echo "WARNING: OPERATOR_STREET, OPERATOR_CITY and OPERATOR_EMAIL are required for a complete Impressum"
  fi
else
  rm -f "$target"
  echo "WARNING: OPERATOR_NAME not set: no site-operator.json, the Impressum shows no operator details"
fi
