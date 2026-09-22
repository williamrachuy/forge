#!/usr/bin/env bash
# Build a single self-contained Forge zip for friends (Windows first, also runs on Linux/macOS),
# and optionally publish it as a GitHub release.
#
#   tools/package_friends.sh              # build, then package into dist/
#   tools/package_friends.sh --no-build   # package the jar already in forge-gui-desktop/target
#   tools/package_friends.sh --out DIR    # write the zip somewhere other than dist/
#   tools/package_friends.sh --release    # build, package, publish a GitHub release on origin
#       --version V    release version (default: <forge version>-bb.<next N>, e.g. 2.0.15-bb.3)
#       --draft        create the release as a draft (review, then publish on GitHub)
#       --prerelease   mark the release as a pre-release
#
# Releases need the GitHub CLI (gh) logged in once with `gh auth login`, a clean working tree,
# and HEAD pushed to origin - friends compare BUILD.txt against a real commit.
#
# Layout mirrors the upstream installer bundle (forge-installer windows-linux profile):
# jar-with-dependencies + forge.exe/forge.cmd/forge.sh + res/ with cardsfolder packed as
# res/cardsfolder/cardsfolder.zip.
set -euo pipefail

# readlink -f: this script is also run through symlinks (e.g. ~/games/forge-testing).
SELF="$(readlink -f "${BASH_SOURCE[0]}")"
REPO="$(cd "$(dirname "$SELF")/.." && pwd)"
BUILD=1
OUT_DIR="$REPO/dist"
RELEASE=0
VERSION=""
DRAFT=0
PRERELEASE=0
TAG_PREFIX="battlebox-v"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --no-build) BUILD=0; shift ;;
        --out) OUT_DIR="$(realpath -m "$2")"; shift 2 ;;
        --release) RELEASE=1; shift ;;
        --version) VERSION="$2"; shift 2 ;;
        --draft) DRAFT=1; shift ;;
        --prerelease) PRERELEASE=1; shift ;;
        -h|--help) sed -n '2,19p' "$SELF" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

cd "$REPO"
TARGET="$REPO/forge-gui-desktop/target"

# GitHub repo of origin (git@github.com:owner/forge.git -> owner/forge). Passed to gh explicitly
# so it never picks the upstream remote.
origin_repo() {
    git remote get-url origin | sed -E 's#^(git@github\.com:|https://github\.com/)##; s#\.git$##'
}

# Release preconditions are checked before the (slow) build so a doomed run fails fast.
if [[ $RELEASE -eq 1 ]]; then
    command -v gh >/dev/null || { echo "error: GitHub CLI 'gh' not installed (sudo apt install gh, then gh auth login)" >&2; exit 1; }
    gh auth status >/dev/null 2>&1 || { echo "error: gh is not logged in - run: gh auth login" >&2; exit 1; }
    [[ $BUILD -eq 1 ]] || { echo "error: --release always builds; drop --no-build" >&2; exit 1; }
    if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
        echo "error: uncommitted changes - commit and push before releasing" >&2; exit 1
    fi
    git fetch -q origin
    if [[ -z "$(git branch -r --contains HEAD 2>/dev/null | grep '^ *origin/' || true)" ]]; then
        echo "error: HEAD $(git rev-parse --short HEAD) is not on origin - push first" >&2; exit 1
    fi
    GH_REPO="$(origin_repo)"
fi

if [[ $BUILD -eq 1 ]]; then
    echo "==> Building forge-gui-desktop (skipping tests)..."
    mvn -q -pl forge-gui-desktop -am -DskipTests package
fi

# forge.cmd is generated per build and names the exact jar it launches; trust it over globbing,
# since target/ can hold stale jars from older versions.
[[ -f "$TARGET/forge.cmd" ]] || { echo "error: $TARGET/forge.cmd missing - run without --no-build" >&2; exit 1; }
JAR_NAME="$(grep -o 'forge-gui-desktop-[^ ]*-jar-with-dependencies\.jar' "$TARGET/forge.cmd" | head -1)"
[[ -n "$JAR_NAME" && -f "$TARGET/$JAR_NAME" ]] || { echo "error: jar named in forge.cmd not found: '$JAR_NAME'" >&2; exit 1; }
for f in forge.exe forge.sh; do
    [[ -f "$TARGET/$f" ]] || { echo "error: $TARGET/$f missing" >&2; exit 1; }
done
# A --no-build run could package a jar older than the source; say so rather than ship it silently.
if [[ -n "$(find forge-*/src/main -newer "$TARGET/$JAR_NAME" -type f -print -quit 2>/dev/null)" ]]; then
    echo "warning: source files are newer than $JAR_NAME - rebuild (drop --no-build) to include them" >&2
fi

COMMIT="$(git rev-parse --short HEAD)"
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
DIRTY=""
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    DIRTY="-dirty"
    echo "warning: working tree has uncommitted changes; they are in this build (tagged ${COMMIT}${DIRTY})" >&2
fi

if [[ $RELEASE -eq 1 ]]; then
    if [[ -z "$VERSION" ]]; then
        # <forge version>-bb.<N>, N = one past the highest existing release tag for that forge version.
        BASE="$(sed -E 's/^forge-gui-desktop-(.*)-jar-with-dependencies\.jar$/\1/; s/-SNAPSHOT$//' <<<"$JAR_NAME")"
        LAST="$(git ls-remote --tags origin "refs/tags/${TAG_PREFIX}${BASE}-bb.*" \
                | sed -nE "s#.*refs/tags/${TAG_PREFIX}${BASE//./\\.}-bb\.([0-9]+)\$#\1#p" \
                | sort -n | tail -1)"
        VERSION="${BASE}-bb.$(( ${LAST:-0} + 1 ))"
    fi
    TAG="${TAG_PREFIX}${VERSION}"
    if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
        echo "error: tag $TAG already exists on origin - pick another --version" >&2; exit 1
    fi
    NAME="Forge-Battlebox-${VERSION}"
else
    NAME="Forge-Battlebox-$(date +%Y%m%d)-${COMMIT}${DIRTY}"
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
PKG="$STAGE/$NAME"
mkdir -p "$PKG"

echo "==> Staging $NAME..."
cp "$TARGET/$JAR_NAME" "$TARGET/forge.exe" "$TARGET/forge.cmd" "$TARGET/forge.sh" "$PKG/"
[[ -f "$TARGET/forge.command" ]] && cp "$TARGET/forge.command" "$PKG/"
cp forge-gui/LICENSE.txt "$PKG/" 2>/dev/null || true

# res/ without the ~30k loose card scripts; Forge reads them from cardsfolder.zip.
rsync -a --exclude 'cardsfolder/' forge-gui/res/ "$PKG/res/"

# Battlebox decks for anyone who wants to host (clients don't need them - the host supplies the deck).
DECK_SRC="${FORGE_BATTLEBOX_DECKS:-$HOME/.forge/decks/battlebox}"
if compgen -G "$DECK_SRC/*.dck" > /dev/null; then
    mkdir -p "$PKG/battlebox-decks"
    cp "$DECK_SRC"/*.dck "$PKG/battlebox-decks/"
else
    echo "note: no .dck files in $DECK_SRC - package will not include Battlebox decks" >&2
fi

JAVA_LINE="$(java -version 2>&1 | head -1 || echo unknown)"
cat > "$PKG/BUILD.txt" <<BUILD_TXT
Forge Battlebox build
  version: ${VERSION:-unreleased}
  commit:  ${COMMIT}${DIRTY} (${BRANCH})
  built:   $(date -u +%Y-%m-%dT%H:%MZ)
  jar:     ${JAR_NAME}
  java:    ${JAVA_LINE}

Everyone in a network game must run a package with the same version/commit as the host.
BUILD_TXT

cat > "$PKG/README.txt" <<'README_TXT'
FORGE (BATTLEBOX BUILD) - SETUP
===============================

1. Install Java 17 or newer, 64-bit (Java 21 recommended).
   Easiest: Eclipse Temurin 21 JRE from https://adoptium.net
   During install, enable "Set JAVA_HOME" and "Add to PATH".

2. Unzip this whole folder somewhere you own, e.g. C:\Games\Forge
   (not inside Program Files - Forge writes files next to itself).

3. Start Forge with forge.exe (or forge.cmd if forge.exe won't start).
   Windows SmartScreen may warn because the exe is unsigned:
   click "More info" -> "Run anyway".
   First launch takes a while (it unpacks card data). Card images
   download on demand the first time they are shown.

4. Join a game: Online Multiplayer -> Join a Game, then enter the
   address the host gives you, e.g. 203.0.113.7:36743
   (or a Tailscale 100.x.x.x address if you're using Tailscale).
   You do NOT need a deck for Battlebox - the host supplies it.

Hosting instead? The host must allow incoming TCP port 36743
(router port forward / UPnP, or use Tailscale). To host Battlebox,
copy the files in battlebox-decks\ into
  %APPDATA%\Forge\decks\battlebox\
(create the folder if needed; on Linux: ~/.forge/decks/battlebox/).

IMPORTANT: everyone must run the same build as the host. Compare
BUILD.txt - the version and commit lines must match. An official
Forge download will NOT work with this game.

Linux/macOS: run ./forge.sh (needs Java 17+ as well).
README_TXT

echo "==> Zipping (cardsfolder.zip + outer archive)..."
mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/$NAME.zip"
rm -f "$ZIP"
python3 - "$REPO/forge-gui/res/cardsfolder" "$PKG/res/cardsfolder/cardsfolder.zip" "$STAGE" "$NAME" "$ZIP" <<'PY'
import os, sys, zipfile

cards_src, cards_zip, stage, name, out = sys.argv[1:]

# Inner card archive: entries relative to cardsfolder/, like the installer's <zip basedir=...>.
os.makedirs(os.path.dirname(cards_zip), exist_ok=True)
with zipfile.ZipFile(cards_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
    for root, _, files in os.walk(cards_src):
        for f in sorted(files):
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, cards_src))

# Outer archive: one top-level folder so unzipping never sprays files into Downloads.
# The inner zip is already compressed, so store it rather than deflate it twice.
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    base = os.path.join(stage, name)
    for root, dirs, files in os.walk(base):
        dirs.sort()
        for f in sorted(files):
            p = os.path.join(root, f)
            arc = os.path.relpath(p, stage)
            info = zipfile.ZipInfo.from_file(p, arc)
            info.compress_type = zipfile.ZIP_STORED if f.endswith(".zip") else zipfile.ZIP_DEFLATED
            if f.endswith((".sh", ".command")):
                info.external_attr = (0o100755 << 16)   # keep launch scripts executable on unzip
            with open(p, "rb") as fh:
                z.writestr(info, fh.read())
PY

# Stable name for anything that links to "the current package" (e.g. ~/games/forge-testing).
ln -sfn "$NAME.zip" "$OUT_DIR/Forge-Battlebox-latest.zip"

SIZE="$(du -h "$ZIP" | cut -f1)"
echo "==> Done: $ZIP ($SIZE)"
echo "    commit ${COMMIT}${DIRTY} - host and all friends need this same package."

if [[ $RELEASE -eq 1 ]]; then
    NOTES="$STAGE/release-notes.md"
    cat > "$NOTES" <<RELEASE_NOTES
Forge with the Battlebox variant (Type 1/2, Monarch, Commanders, Planechase) and network play.

**Install:** download \`$NAME.zip\`, unzip it somewhere you own (not Program Files), install
Java 17+ 64-bit (Temurin 21: https://adoptium.net), then run \`forge.exe\` (or \`forge.cmd\`).
Full steps are in \`README.txt\` inside the zip.

**Everyone in a game must run this same release as the host.** An official Forge build, or a
different release of this one, will not connect properly.

Built from \`$(git rev-parse HEAD)\` on \`${BRANCH}\`.
RELEASE_NOTES
    FLAGS=()
    [[ $DRAFT -eq 1 ]] && FLAGS+=(--draft)
    [[ $PRERELEASE -eq 1 ]] && FLAGS+=(--prerelease)
    echo "==> Creating GitHub release $TAG on $GH_REPO..."
    gh release create "$TAG" "$ZIP" \
        --repo "$GH_REPO" \
        --target "$(git rev-parse HEAD)" \
        --title "Forge Battlebox $VERSION" \
        --notes-file "$NOTES" \
        "${FLAGS[@]}"
    echo "==> Released: https://github.com/$GH_REPO/releases/tag/$TAG"
fi
