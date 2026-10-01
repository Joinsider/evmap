#!/bin/sh
# Writes the apple-app-site-association file the iOS app's associated domain needs: webcredentials for its
# HTTPS sign-in callback (ADR 0018, ASWebAuthenticationSession checks that service) and applinks for the
# route share link https://evmap.joinside.de/route (ADR 0017), which opens the app when it is installed.
set -eu
mkdir -p /tmp/evmap
if [ -n "${APPLE_TEAM_ID:-}" ]; then
  app="${APPLE_TEAM_ID}.${IOS_BUNDLE_ID:-de.joinside.EVMap}"
  printf '{"webcredentials":{"apps":["%s"]},"applinks":{"details":[{"appIDs":["%s"],"components":[{"/":"/route"}]}]}}\n' "$app" "$app" \
    > /tmp/evmap/apple-app-site-association
  echo "apple-app-site-association written for ${app}"
else
  rm -f /tmp/evmap/apple-app-site-association
  echo "APPLE_TEAM_ID not set: no apple-app-site-association, the iOS app cannot use web sign-in or open route links"
fi
