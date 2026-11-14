import json
from collections import Counter

d = json.load(open('/work/eval-f6-results4.json'))
recs = d['images']
print('branches:', Counter(r.get('hyBranch') for r in recs))
print('summary ms:', {k: v for k, v in d['summary'].items() if 'Ms' in k})
print()

rows = []
for r in recs:
    rows.append((r['file'], r.get('hyBranch'), r.get('hyCannySup'),
                 r.get('hyLearnedSup'), r.get('iou'), r.get('hyIou'),
                 r.get('detectMs'), r.get('hyMs')))
rows.sort(key=lambda t: (t[1] or '', t[0]))

print(f"{'image':30s} {'branch':13s} {'cSup':>6s} {'lSup':>6s} {'cIoU':>6s} {'hyIoU':>6s} {'dMs':>4s} {'hMs':>5s}")
for f, br, cs, ls, ci, hi, cm, hm in rows:
    print(f"{f:30s} {str(br):13s} "
          f"{('' if cs is None else f'{cs:.3f}'):>6s} {('' if ls is None else f'{ls:.3f}'):>6s} "
          f"{('' if ci is None else f'{ci:.3f}'):>6s} {('' if hi is None else f'{hi:.3f}'):>6s} "
          f"{str(cm or ''):>4s} {str(hm or ''):>5s}")

print()
print('learned-win detail:')
for f, br, cs, ls, ci, hi, cm, hm in rows:
    if br == 'learned-win':
        print(f'  {f:30s} cSup={cs} lSup={ls} cIoU={ci} hyIoU={hi}')
print()
print('canny-keep where learnedSup exists:')
for f, br, cs, ls, ci, hi, cm, hm in rows:
    if br == 'canny-keep' and ls is not None:
        print(f'  {f:30s} cSup={cs} lSup={ls} cIoU={ci}')
