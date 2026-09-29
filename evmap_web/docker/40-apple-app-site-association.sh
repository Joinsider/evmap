#!/bin/sh
# Writes the apple-app-site-association file the iOS app's associated domain needs for its HTTPS
# sign-in callback (ADR 0018). webcredentials is the service ASWebAuthenticationSession checks.
set -eu
mkdir -p /tmp/evmap
if [ -n "${APPLE_TEAM_ID:-}" ]; then
  printf '{"webcredentials":{"apps":["%s.%s"]}}\n' "$APPLE_TEAM_ID" "${IOS_BUNDLE_ID:-de.joinside.EVMap}" \
    > /tmp/evmap/apple-app-site-association
  echo "apple-app-site-association written for ${APPLE_TEAM_ID}.${IOS_BUNDLE_ID:-de.joinside.EVMap}"
else
  rm -f /tmp/evmap/apple-app-site-association
  echo "APPLE_TEAM_ID not set: no apple-app-site-association, the iOS app cannot use web sign-in"
fi
