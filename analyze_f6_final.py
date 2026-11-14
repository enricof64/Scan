import json

d = json.load(open('/work/eval-f6-results4.json'))
s = d['summary']
recs = d['images']

def med(xs):
    xs = sorted(xs)
    n = len(xs)
    return xs[n // 2] if n % 2 else (xs[n // 2 - 1] + xs[n // 2]) / 2

print('== F6 final run summary ==')
for k in ('detected', 'detectionRate', 'iouMedian', 'iouAbove09', 'daDetected',
          'lfDetected', 'lfIouMedian', 'lfIouAbove09',
          'hyDetected', 'hyDetectionRate', 'hyIouMedian', 'hyIouP90', 'hyIouAbove09',
          'detectMsMedian', 'daMsMedian', 'lfMsMedian', 'hyMsMedian'):
    print(f'  {k:20s} {s.get(k)}')

canny_ms = [r['detectMs'] for r in recs if r.get('detectMs') is not None]
hy_ms = [r['hyMs'] for r in recs if r.get('hyMs') is not None]
print()
print(f'  cannyMs median = {med(canny_ms)}, hyMs median = {med(hy_ms)}, '
      f'delta = {med(hy_ms) - med(canny_ms)}')

# per-image rule checks
print()
print('== rule checks ==')
canny_rate = s['detected'] / s['total']
print(f'1) hyDet {s["hyDetectionRate"]} >= 0.95 and >= canny {canny_rate}: '
      f'{s["hyDetectionRate"] >= 0.95 and s["hyDetectionRate"] >= canny_rate}')
print(f'2) hyIouMedian {s["hyIouMedian"]:.4f} >= canny iouMedian {s["iouMedian"]:.4f}: '
      f'{s["hyIouMedian"] >= s["iouMedian"] - 1e-12}')
print(f'3) hyAbove09 {s["hyIouAbove09"]} >= canny {s["iouAbove09"]}: '
      f'{s["hyIouAbove09"] >= s["iouAbove09"]}')

worst = []
for r in recs:
    ci, hi = r.get('iou'), r.get('hyIou')
    if ci is None:
        continue
    diff = hi - ci
    worst.append((r['file'], ci, hi, diff))
worst.sort(key=lambda t: t[3])
regress = [t for t in worst if t[3] < -0.005]
print('4) per-image regressions > 0.05 vs Canny:')
bad = [t for t in worst if t[3] < -0.05]
for t in bad:
    print(f'   FAIL {t[0]}: {t[1]:.3f} -> {t[2]:.3f} ({t[3]:+.3f})')
if not bad:
    print('   none - PASS')
print('   all diffs <= -0.005:', regress if regress else 'none')
print('   biggest wins:', sorted(worst, key=lambda t: -t[3])[:4])

delta = med(hy_ms) - med(canny_ms)
print(f'5) hyMs {med(hy_ms)} <= cannyMs {med(canny_ms)} + 40 = {med(canny_ms) + 40}: {delta <= 40}')
print()
print('ALL PASS' if (s['hyDetectionRate'] >= 0.95 and s['hyDetectionRate'] >= canny_rate
                     and s['hyIouMedian'] >= s['iouMedian'] - 1e-12
                     and s['hyIouAbove09'] >= s['iouAbove09']
                     and not bad and delta <= 40) else 'SOME RULE FAILED')
