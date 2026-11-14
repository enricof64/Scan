# OpenScan — Plans

Working plans for follow-up work. Each plan states its acceptance
criteria **before** implementation, following the F5/F6 convention
(criterion checked on the Fold 8 corpus, same 64 images, same benchmark
command — see [EVALUATION.md](EVALUATION.md)).

## Plan A — flip the F6 hybrid to the production default (2026-11-14)

**Context.** The tuned F6 hybrid passes every quality rule of the F6
pre-declared check with zero per-image regressions and 3 wins
(cc_12 0.226→0.890, syn_photo_page_22 0.105→0.929,
syn_photo_page_23 0.079→0.937), but fails the latency rule by 8 ms
(`hyMsMedian` 165 vs 157 budget). The cost anatomy, measured on-device:
the support pass is ~14 ms; the budget is broken by **24 of 64 corpus
images (37%) being "doubtful"** (Canny perimeter support < 0.80, mostly
full-bleed CC photos whose border edges are weak) and each paying the
DocAligner call (~+80 ms). The latency rule must not be renegotiated.

**Acceptance criteria (checked before flipping, on the corpus).**

1. The five F6 pre-declared rules of [EVALUATION.md](EVALUATION.md) all
   pass on the same 64-image corpus.
2. No new per-image regression vs the *tuned hybrid* run (run 3): every
   image keeps at least the IoU the hybrid already gave it.
3. DocAligner-consult fraction ≤ 25% of the corpus (was 37.5%).
4. Corpus extension: ~20–30 additional real captures (desk backgrounds,
   mixed lighting, partial frames) are added to the benchmark set and the
   check is re-run on the enlarged set before the default is flipped —
   this also closes the F5 follow-up "a second, larger real-world
   corpus would sharpen the call". The 64-image set alone is adversarial
   for full-bleed captures; a real user scans pages on desks, where the
   doubtful fraction is expected to be lower.

**Lever 1 (do first): flat-background gate — cut the doubtful fraction,
zero model cost.**

- Observation: the 3 hybrid wins all come from captures whose *background*
  outside the document is flat/low-texture (no Canny edges), while most
  of the doubtful full-bleed CC photos have edge-rich surroundings where
  DocAligner lost or tied (cc_08, cc_09, cc_23, cc_27, cc_29…).
- Mechanism: in `hybridDetect`, the support pass already builds the
  Canny edge union at work size. Add one `Core.countNonZero` over the
  region **outside a 12 px band around the Canny quad's perimeter** →
  `bgEdgeFrac` (a few ms, no new pass). If `bgEdgeFrac > BG_EDGE_GATE`
  (candidate 0.01, to be calibrated on the 24 doubtful images in a one-off
  instrumented run: flat backgrounds should cluster low, desk/full-bleed
  high), skip the learned detector and keep Canny (new branch
  `canny-bg-rich`).
- Pre-condition (calibration run, one device pass logging `bgEdgeFrac`
  per doubtful image): verify the 3 wins have low outside-band density
  and the non-winning doubtful images have high density. If the
  separation is not clean, this lever is dropped and Lever 2 stands alone.
- Expected corpus effect: 24 doubtful → ~11 consulted;
  `hyMsMedian` ≈ 117 + 14 + 11/64·80 ≈ 145 ≤ 157.
- Tests: the decision is a pure function
  `shouldConsultLearned(cannySupport, bgEdgeFrac)` — unit-tested, plus a
  threshold-sanity test in the existing style.

**Lever 2 (only if Lever 1 leaves rule 5 failing): cheaper second
opinion.**

- B1 — INT8-quantize the DocAligner ONNX model (static quantization;
  onnxruntime Android already supports quantized models). Target:
  inference ≤ 30 ms median (was ~53). Risk: support values drift a few
  points — full re-benchmark, re-check the 0.90 learned floor against the
  new per-image `hyLearnedSup` distribution before trusting it.
  Effort: 1–2 sessions.
- B2 — asynchronous second opinion (show Canny immediately, run DocAligner
  in the background, swap the quad only if floor + margin pass):
  **rejected** as a default — the quad visibly jumps after the user has
  already cropped; keep it only as a future "refine corners" affordance.

**Sequence.**

1. Calibration run: log `bgEdgeFrac` on the 24 doubtful images (one
   instrumented device run, ~2 min).
2. Implement Lever 1 + unit tests.
3. Full benchmark on the 64-image corpus: check rules 1–3.
4. Extend the corpus (criterion 4) and re-run.
5. If all criteria pass: `detectQuad(src)` default becomes HYBRID,
   `FEATURES.md`/`EVALUATION.md` updated, PR. If not: Lever 2-B1, back to 3.

**Effort.** Lever 1 ≈ half a day including device runs; Lever 2-B1 ≈ 1 day.

**Not doing.** No loosening of the pre-declared latency rule; no B2; no
change to the toggle semantics (it keeps meaning "use the learned
detector", whatever strategy wins the rules).
