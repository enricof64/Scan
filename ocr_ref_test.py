import json, sys, time
from rapidocr_onnxruntime import RapidOCR

sys.path.insert(0, '/work')
ocr = RapidOCR()  # default = PP-OCRv5 mobile det+rec onnx
for path in sys.argv[1:]:
    t0 = time.time()
    result, elapse = ocr(path)
    dt = (time.time() - t0) * 1000
    lines = []
    if result:
        for box, text, score in result:
            lines.append({'text': text, 'score': round(float(score), 3),
                          'box': [[round(x, 1), round(y, 1)] for x, y in box]})
    print(f'== {path}: {len(lines)} lines in {dt:.0f} ms')
    for l in lines[:8]:
        print(f"   {l['score']:.2f}  {l['text']!r}  {l['box']}")
