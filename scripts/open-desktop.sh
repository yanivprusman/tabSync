#!/usr/bin/env bash
# Open the tabSync desktop page in its own Chrome window, signed in.
#
# The window opens through a short-lived sign-in link (scripts/make-link.mjs),
# so there is no code to type. Chrome is launched with the exact command line of
# the everyday browser's own launcher: if this were the first Chrome of the
# session, launching it with different flags would be what that whole session
# then ran with. (Same as adsSwitch's open-desktop.sh.)
set -euo pipefail

link="$(node /opt/dev/tabSync/scripts/make-link.mjs 5)"
launcher="$HOME/.local/share/applications/google-chrome.desktop"
line="$(grep -m1 '^Exec=' "$launcher")" || { echo "open-desktop: no Exec line in $launcher" >&2; exit 1; }
read -ra chrome <<< "${line#Exec=}"
args=()
for word in "${chrome[@]}"; do
    [[ "$word" == "%U" ]] || args+=("$word")
done
exec /opt/automateLinux/utilities/runInGuiSession.sh "${args[@]}" "--app=$link"
