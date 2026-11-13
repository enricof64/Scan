#!/usr/bin/env sh
# F5 detector verdict — run on the host with adb, Fold 8 connected.
#
# Pushes the corpus, runs the on-device benchmark (Canny v19 + DocAligner +
# combined learned-first strategy) and pulls the results. See the F3 and F5
# sections of docs/EVALUATION.md.
#
# Usage:
#   sh scripts/run-f5-benchmark.sh <corpus-dir>
#
# <corpus-dir> must contain:
#   images/     JPEG photos (include the plan's 20-30 fresh real-world shots)
#   index.json  {"images":[{"file":"name.jpg","doc":"group"}, ...]}
#               ("doc" optional; "gt_quad" optional — needed only for IoU)
#
# Build the APKs first:
#   ./gradlew assembleFossDebug assembleFossDebugAndroidTest
set -eu

CORPUS="${1:?usage: $0 <corpus-dir with images/ + index.json>}"
PKG=io.github.enricof64.scan

adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
adb install -r app/build/outputs/apk/androidTest/foss/debug/app-foss-debug-androidTest.apk

adb shell mkdir -p /data/local/tmp/openscan-eval/images
adb push "$CORPUS"/images/. /data/local/tmp/openscan-eval/images/
adb push "$CORPUS/index.json" /data/local/tmp/openscan-eval/

adb shell am instrument -w -e class io.github.enricof64.scan.DocumentEvalBenchmark \
  "$PKG.test/androidx.test.runner.AndroidJUnitRunner"

adb pull "$PKG/files/eval" ./eval-f5

echo
echo "Done. Verdict fields in ./eval-f5/results.json:"
echo "  per strategy: detectionRate / iouMedian / iouP90 / iouAbove09 / *MsMedian"
echo "                (plain = Canny v19, da* = DocAligner, lf* = learned-first + fallback)"
echo "  summary: targets + meets / daMeets / lfMeets"
echo "  overlays: lf_*.jpg = combined-strategy corners (visual check)"
