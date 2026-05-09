#!/usr/bin/env bash
#
# One-shot setup for the four GitHub secrets the release workflow needs:
#   SIGNING_KEY, KEY_ALIAS, KEY_STORE_PASSWORD, KEY_PASSWORD
#
# Generates a release keystore (if you don't already have one), base64-encodes
# it, and uploads everything via the gh CLI.
#
# Prerequisites:
#   - JDK 17+ on PATH (provides keytool)
#   - gh CLI authenticated to a user with repo-admin rights on the target
#
# Run from anywhere:
#   bash scripts/setup-signing.sh
#

set -euo pipefail

REPO="${REPO:-undisputedP/ShortsBlocker}"
KEYSTORE_PATH="${KEYSTORE_PATH:-$HOME/.android/shortsblocker-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-shortsblocker}"

command -v keytool >/dev/null 2>&1 || { echo >&2 "ERROR: keytool not on PATH. Install JDK 17."; exit 1; }
command -v gh >/dev/null 2>&1 || { echo >&2 "ERROR: gh CLI not on PATH. https://cli.github.com"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo >&2 "ERROR: not logged in. Run 'gh auth login'."; exit 1; }

if [[ -f "$KEYSTORE_PATH" ]]; then
    echo "[*] Reusing existing keystore at: $KEYSTORE_PATH"
    echo "    (Delete the file first if you want to generate a new one.)"
else
    echo "[*] Generating new release keystore at: $KEYSTORE_PATH"
    mkdir -p "$(dirname "$KEYSTORE_PATH")"
    keytool -genkeypair \
        -alias "$KEY_ALIAS" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10000 \
        -keystore "$KEYSTORE_PATH" \
        -storetype PKCS12
    cat <<EOF

*** IMPORTANT: back up $KEYSTORE_PATH to somewhere safe (password manager,
    encrypted drive, etc.). If you lose it, you cannot publish updates to the
    same Play Store listing — Google requires every update signed with the
    same key as the original.

EOF
fi

read -r -s -p "Keystore password (so we can set GitHub secrets): " KEYSTORE_PWD
echo
read -r -s -p "Key password (often the same as keystore): " KEY_PWD
echo

# -w 0 (GNU base64) keeps everything on one line; macOS base64 uses -i instead.
if base64 --help 2>&1 | grep -q -- '-w'; then
    KEYSTORE_B64=$(base64 -w 0 "$KEYSTORE_PATH")
else
    KEYSTORE_B64=$(base64 -i "$KEYSTORE_PATH" | tr -d '\n')
fi

echo "[*] Setting four secrets on $REPO ..."
printf '%s' "$KEYSTORE_B64" | gh secret set SIGNING_KEY -R "$REPO"
printf '%s' "$KEY_ALIAS"     | gh secret set KEY_ALIAS -R "$REPO"
printf '%s' "$KEYSTORE_PWD"  | gh secret set KEY_STORE_PASSWORD -R "$REPO"
printf '%s' "$KEY_PWD"       | gh secret set KEY_PASSWORD -R "$REPO"

echo
echo "[+] Done. Verify with:  gh secret list -R $REPO"
echo "[+] You can now tag a release (e.g. 'git tag v1.0.0 && git push origin v1.0.0')"
echo "    and the signed APK will be built and attached to a GitHub Release."
