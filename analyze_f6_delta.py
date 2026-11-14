import json

d = json.load(open('/work/eval-f6-results4.json'))
recs = d['images']

rows = []
for r in recs:
    delta = (r.get('hyMs') or 0) - (r.get('detectMs') or 0)
    rows.append((delta, r['file'], r.get('hyBranch'), r.get('detectMs'), r.get('hyMs')))
rows.sort()

print(f"{'delta':>5s} {'image':30s} {'branch':13s} {'cMs':>5s} {'hMs':>5s}")
for delta, f, br, cm, hm in rows:
    print(f"{delta:5d} {f:30s} {str(br):13s} {str(cm or ''):>5s} {str(hm or ''):>5s}")

deltas = [t[0] for t in rows]
deltas.sort()
n = len(deltas)
print()
print('median delta:', deltas[n // 2] if n % 2 else (deltas[n//2-1]+deltas[n//2])/2)
fast = [t[0] for t in rows if t[2] == 'canny-fast']
fast.sort()
print('canny-fast only, n =', len(fast), 'median:', fast[len(fast)//2], 'max:', fast[-1])
keep = [t[0] for t in rows if t[2] == 'canny-keep']
keep.sort()
print('canny-keep only, n =', len(keep), 'median:', keep[len(keep)//2])
win = [t[0] for t in rows if t[2] == 'learned-win']
win.sort()
print('learned-win only, n =', len(win), 'median:', win[len(win)//2])
