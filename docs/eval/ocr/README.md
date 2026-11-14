# OCR evaluation (P1: searchable PDF)

On-device OCR quality data and the standalone reference that produced it.

## Contents

| File | What it is |
|---|---|
| `ocr_v5_ref.py` | Standalone PP-OCRv5 reference pipeline (Python, onnxruntime): the exact pre/post recipe the Kotlin engine in `app/src/main/java/io/github/enricof64/scan/ocr/` mirrors. |
| `ocr_run_corpus.py` | Runs the reference over the corpus and writes `ocr_v5_ground_truth.json`. |
| `ocr_v5_ground_truth.json` | Reference output per image: `{ image: { lines: [{text, box}], ... } }` — the ground truth for `OcrEvalBenchmark` (androidTest). |
| `ppocrv5_det.yml` / `ppocrv5_rec.yml` | Upstream inference configs used by the reference (thresholds, resize, dict). |

## Corpus

74 images: the F5 scan corpus (37) + `docs/eval/samples` (37, same set the
document/detector evals use). The F5 corpus lives on the owner's server
only, not in this repo (third-party images).

## On-device benchmark

1. Push the corpus + ground truth to the device:
   ```
   adb push <corpus-dir>/images  /data/local/tmp/openscan-ocr-eval/images
   adb push <corpus-dir>/ground_truth.json /data/local/tmp/openscan-ocr-eval/
   ```
2. Run the instrumented test (needs the app's androidTest APK):
   ```
   adb shell am instrument -w -e class io.github.enricof64.scan.OcrEvalBenchmark \
       io.github.enricof64.scan.test/androidx.test.runner.AndroidJUnitRunner
   ```
3. Pull the report: `adb pull /data/local/tmp/openscan-ocr-eval-out/results.json`.

Metric: greedy line pairing, character F1 ≥ 0.8 = line correct; report has
precision/recall/F1 + matched counts + median per-image ms.

## Regenerating the ground truth

`ocr_v5_ref.py` + the ONNX models (same as `app/src/main/assets/ocr/`) on
Python 3.12 with `onnxruntime`, `opencv-python-headless`, `pyclipper`.
Versions used: onnxruntime 1.32.0, opencv-headless 5.0.0.93, rapidocr-onnxruntime 1.4.4.
