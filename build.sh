#!/usr/bin/env bash
# Build TG Backup APK using Buildozer inside Termux
# Run from inside a Ubuntu proot-distro, NOT bare Termux.
#
# Termux setup:
#   pkg install proot-distro
#   proot-distro install ubuntu
#   proot-distro login ubuntu
#   bash build.sh
#
set -e

echo "=== TG Backup — APK Builder ==="
echo ""

# ── 1. System dependencies ────────────────────────────────────────────────────
echo "[1/5] Installing system packages..."
apt-get update -q
apt-get install -y -q \
  python3 python3-pip python3-venv \
  git zip unzip curl wget \
  openjdk-17-jdk \
  libffi-dev libssl-dev \
  build-essential cmake autoconf libtool \
  pkg-config zlib1g-dev

# Set JAVA_HOME
export JAVA_HOME=$(readlink -f /usr/bin/java | sed 's:/bin/java::')
export PATH="$JAVA_HOME/bin:$PATH"

# ── 2. Python deps ────────────────────────────────────────────────────────────
echo "[2/5] Installing Python build tools..."
pip3 install --upgrade pip
pip3 install buildozer cython==3.0.10

# ── 3. Android SDK licence acceptance ─────────────────────────────────────────
echo "[3/5] Preparing Android SDK directories..."
mkdir -p ~/.android
touch ~/.android/repositories.cfg

# ── 4. Move to project dir ────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
echo "[4/5] Building from: $SCRIPT_DIR"

# ── 5. Build ──────────────────────────────────────────────────────────────────
echo "[5/5] Running buildozer (first build downloads ~2 GB, takes 20-40 min)..."
buildozer -v android debug

echo ""
echo "======================================="
echo " APK ready:"
ls -lh bin/*.apk 2>/dev/null || echo "  bin/*.apk  (check for errors above)"
echo "======================================="
echo " Install on device:"
echo "   adb install bin/tgbackup-1.0-arm64-v8a-debug.apk"
echo " Or copy the .apk file to your phone and open it."
