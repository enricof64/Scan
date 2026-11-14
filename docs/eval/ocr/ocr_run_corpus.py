import glob, json, sys
sys.path.insert(0, '/work')
import ocr_v5_ref as ref

paths = sorted(glob.glob('/work/.f5-corpus/images/*')) + sorted(glob.glob('/work/docs/eval/samples/*'))
out = {}
for p in paths:
    name = p.split('/')[-1]
    import cv2, time
    img = cv2.imread(p)
    t0 = time.time()
    lines = ref.ocr(img)
    out[name] = {'ms': round((time.time() - t0) * 1000), 'lines': lines}
    print(f'{name}: {len(lines)} lines')
with open('/work/ocr_v5_ground_truth.json', 'w', encoding='utf-8') as f:
    json.dump(out, f, ensure_ascii=False, indent=1)
print('saved', len(out), 'images')
