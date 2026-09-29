#!/usr/bin/env bash
set -euo pipefail

# build-universal-installer.sh
#
# Wraps two ALREADY-BUILT, single-arch DoubleClips.app bundles into one .pkg
# installer that installs the correct one for whichever Mac it's run on.
#
# jpackage cannot produce a merged/universal app image itself - it bakes the
# *build machine's* architecture into whatever it emits (see JDK-8266179:
# the produced .pkg's hostArchitectures attribute comes straight from
# System.getProperty("os.arch") of the JVM that ran jpackage), and Oracle's
# own investigation into a single universal jpackage output (JDK-8266259)
# never shipped. So the two inputs to this script have to come from two
# separate jpackage runs:
#   - one with an aarch64 JDK, run natively on Apple silicon hardware
#     (jpackage can only emit for the arch it's actually running on - there's
#     no way to get an arm64 build machine to emit an x86_64 result "cross",
#     and vice versa without Rosetta)
#   - one with an x86_64 JDK, run either on an Intel Mac, or on Apple silicon
#     hardware under Rosetta (Rosetta can run an x86_64 JDK's jpackage; it
#     cannot go the other direction)
# In CI that's a build matrix (e.g. a macos-13 Intel runner + a
# macos-14/15 Apple silicon runner), not something expressible in
# build.gradle alone.
#
# This does NOT produce a true universal Mach-O binary. Both payloads are
# always embedded in the .pkg (no download-size saving over shipping two
# files) - what you get is one link, one double-click installer, that lays
# down the right app either way. Selection happens via a plain postinstall
# shell script (/bin/sh is already universal on every Mac - nothing to lipo
# here), checking `uname -m` at install time, rather than a distribution.xml
# JS `selected=` predicate - that mechanism is documented for *gating which
# architectures a package is even offered on* (hostArchitectures), not
# confirmed here for choosing between two different payloads, so a plain
# shell postinstall was the safer, verifiable choice.
#
# NOT verified end-to-end on real hardware from this environment (no macOS
# tooling available where this was written - no pkgbuild/productbuild to
# actually run). Test on both a real Intel Mac and a real Apple silicon Mac
# before shipping this to users. In particular, double-check that the
# installer runs pkg-refs in the listed order (payloads before the
# selector) - distribution packages generally install components in the
# order listed under a <choice>, but this wasn't something this script
# could confirm by actually running it.

INTEL_APP=""
ARM_APP=""
VERSION="1.0.0"
OUT_DIR="./build/universal-installer"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --intel-app) INTEL_APP="$2"; shift 2 ;;
    --arm-app) ARM_APP="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$INTEL_APP" || -z "$ARM_APP" ]]; then
  echo "Usage: $0 --intel-app <path/to/x86_64/DoubleClips.app> --arm-app <path/to/arm64/DoubleClips.app> [--version X.Y.Z] [--out-dir DIR]" >&2
  exit 1
fi
if [[ ! -d "$INTEL_APP" || ! -d "$ARM_APP" ]]; then
  echo "Both --intel-app and --arm-app must be existing .app directories." >&2
  exit 1
fi

command -v pkgbuild >/dev/null 2>&1 || { echo "pkgbuild not found - this must be run on macOS." >&2; exit 1; }
command -v productbuild >/dev/null 2>&1 || { echo "productbuild not found - this must be run on macOS." >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT_DIR"

BUNDLE_ID="com.vanvatcorporation.doubleclips"

# --- Component 1: Intel payload, staged to a private support path ---
INTEL_ROOT="$WORK/intel-root/Library/Application Support/DoubleClips/payload-x86_64"
mkdir -p "$INTEL_ROOT"
cp -R "$INTEL_APP" "$INTEL_ROOT/DoubleClips.app"

pkgbuild \
  --root "$WORK/intel-root" \
  --identifier "${BUNDLE_ID}.payload-x86_64" \
  --version "$VERSION" \
  --install-location "/" \
  "$WORK/intel-component.pkg"

# --- Component 2: Apple silicon payload, same staging pattern ---
ARM_ROOT="$WORK/arm-root/Library/Application Support/DoubleClips/payload-arm64"
mkdir -p "$ARM_ROOT"
cp -R "$ARM_APP" "$ARM_ROOT/DoubleClips.app"

pkgbuild \
  --root "$WORK/arm-root" \
  --identifier "${BUNDLE_ID}.payload-arm64" \
  --version "$VERSION" \
  --install-location "/" \
  "$WORK/arm-component.pkg"

# --- Component 3: no payload of its own - just the arch-selector script ---
SCRIPTS_DIR="$WORK/selector-scripts"
mkdir -p "$SCRIPTS_DIR"
cat > "$SCRIPTS_DIR/postinstall" <<'EOF'
#!/bin/sh
set -e

BASE="/Library/Application Support/DoubleClips"
DEST="/Applications/DoubleClips.app"

ARCH="$(uname -m)"
if [ "$ARCH" = "arm64" ]; then
  SRC="$BASE/payload-arm64/DoubleClips.app"
else
  SRC="$BASE/payload-x86_64/DoubleClips.app"
fi

if [ ! -d "$SRC" ]; then
  echo "Expected payload not found at $SRC - installer components may have run out of order." >&2
  exit 1
fi

rm -rf "$DEST"
ditto "$SRC" "$DEST"
rm -rf "$BASE"

exit 0
EOF
chmod +x "$SCRIPTS_DIR/postinstall"

pkgbuild \
  --nopayload \
  --scripts "$SCRIPTS_DIR" \
  --identifier "${BUNDLE_ID}.selector" \
  --version "$VERSION" \
  "$WORK/selector-component.pkg"

# --- Combine all three into one distribution package ---
# hostArchitectures="x86_64,arm64" tells macOS's own installer to offer this
# package on either arch without prompting for Rosetta - same attribute
# jpackage itself sets automatically for a single-arch build (JDK-8266179),
# set here explicitly since we're driving productbuild directly.
cat > "$WORK/distribution.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="1">
    <title>DoubleClips</title>
    <options customize="never" require-scripts="true" hostArchitectures="x86_64,arm64"/>
    <choices-outline>
        <line choice="choiceMain"/>
    </choices-outline>
    <choice id="choiceMain" title="DoubleClips" visible="false">
        <pkg-ref id="${BUNDLE_ID}.payload-x86_64"/>
        <pkg-ref id="${BUNDLE_ID}.payload-arm64"/>
        <pkg-ref id="${BUNDLE_ID}.selector"/>
    </choice>
    <pkg-ref id="${BUNDLE_ID}.payload-x86_64" version="$VERSION" onConclusion="none">intel-component.pkg</pkg-ref>
    <pkg-ref id="${BUNDLE_ID}.payload-arm64" version="$VERSION" onConclusion="none">arm-component.pkg</pkg-ref>
    <pkg-ref id="${BUNDLE_ID}.selector" version="$VERSION" onConclusion="none">selector-component.pkg</pkg-ref>
</installer-gui-script>
EOF

productbuild \
  --distribution "$WORK/distribution.xml" \
  --package-path "$WORK" \
  "$OUT_DIR/DoubleClips-Universal-$VERSION.pkg"

echo "Wrote $OUT_DIR/DoubleClips-Universal-$VERSION.pkg"
