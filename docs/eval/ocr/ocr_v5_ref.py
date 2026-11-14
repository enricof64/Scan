# PP-OCRv5 mobile reference (det + rec, no cls) — mirrors the Kotlin port.
import json, math, sys, time
import numpy as np
import cv2
import pyclipper
from shapely.geometry import Polygon
import onnxruntime as ort

DET_MEAN = np.array([0.485, 0.456, 0.406])
DET_STD = np.array([0.229, 0.224, 0.225])

det_sess = ort.InferenceSession('/work/.ocr-models/ppocrv5_det.onnx', providers=['CPUExecutionProvider'])
rec_sess = ort.InferenceSession('/work/.ocr-models/ppocrv5_rec.onnx', providers=['CPUExecutionProvider'])
CHARS = open('/work/.ocr-models/rec_char_dict.txt', encoding='utf-8').read().split('\n')
assert CHARS[-1] == ''
CHARS = CHARS[:-1]
DICT = ['blank'] + CHARS + [' ']
assert len(DICT) == 18385


def resize_det(img):
    h, w = img.shape[:2]
    if max(h, w) > 960:
        ratio = 960.0 / (h if h > w else w)
    else:
        ratio = 1.0
    rh = int(round(h * ratio / 32) * 32)
    rw = int(round(w * ratio / 32) * 32)
    if rh <= 0 or rw <= 0:
        return None
    return cv2.resize(img, (rw, rh))


def det_preprocess(img):
    r = resize_det(img)
    r = (r.astype(np.float32) / 255.0 - DET_MEAN) / DET_STD
    return r.transpose(2, 0, 1)[np.newaxis, :].astype(np.float32)


def get_mini_boxes(contour):
    bb = cv2.minAreaRect(contour)
    pts = sorted(list(cv2.boxPoints(bb)), key=lambda x: x[0])
    i1, i4 = (0, 1) if pts[1][1] > pts[0][1] else (1, 0)
    i2, i3 = (2, 3) if pts[3][1] > pts[2][1] else (3, 2)
    return np.array([pts[i1], pts[i2], pts[i3], pts[i4]]), min(bb[1])


def box_score_fast(pred, box):
    h, w = pred.shape[:2]
    box = box.copy()
    xmin = int(np.clip(np.floor(box[:, 0].min()), 0, w - 1))
    xmax = int(np.clip(np.ceil(box[:, 0].max()), 0, w - 1))
    ymin = int(np.clip(np.floor(box[:, 1].min()), 0, h - 1))
    ymax = int(np.clip(np.ceil(box[:, 1].max()), 0, h - 1))
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box[:, 0] -= xmin
    box[:, 1] -= ymin
    cv2.fillPoly(mask, box.reshape(1, -1, 2).astype(np.int32), 1)
    return float(cv2.mean(pred[ymin:ymax + 1, xmin:xmax + 1], mask)[0])


def unclip(box, ratio=1.5):
    poly = Polygon(box)
    d = poly.area * ratio / poly.length
    off = pyclipper.PyclipperOffset()
    off.AddPath(box, pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    return np.array(off.Execute(d)).reshape((-1, 1, 2))


def det_postprocess(pred, src_w, src_h):
    pred = pred[0, 0]
    mask = (pred > 0.3).astype(np.uint8)
    mask = cv2.dilate(mask, np.array([[1, 1], [1, 1]]))
    outs = cv2.findContours(mask * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    contours = outs[1] if len(outs) == 3 else outs[0]
    boxes, scores = [], []
    for contour in contours[:1000]:
        points, sside = get_mini_boxes(contour)
        if sside < 3:
            continue
        score = box_score_fast(pred, points.reshape(-1, 2))
        if score < 0.6:
            continue
        box, sside = get_mini_boxes(unclip(points))
        if sside < 5:
            continue
        box[:, 0] = np.clip(np.round(box[:, 0] / pred.shape[1] * src_w), 0, src_w)
        box[:, 1] = np.clip(np.round(box[:, 1] / pred.shape[0] * src_h), 0, src_h)
        boxes.append(box.astype(np.int32))
        scores.append(score)
    return boxes, scores


def order_points_clockwise(pts):
    xs = pts[np.argsort(pts[:, 0]), :]
    left = xs[:2, :][np.argsort(xs[:2, 1]), :]
    right = xs[2:, :][np.argsort(xs[2:, 1]), :]
    return np.array([left[0], right[0], right[1], left[1]], dtype=np.float32)


def rec_crop(img, points):
    w = int(max(np.linalg.norm(points[0] - points[1]), np.linalg.norm(points[2] - points[3])))
    h = int(max(np.linalg.norm(points[0] - points[3]), np.linalg.norm(points[1] - points[2])))
    pts_std = np.array([[0, 0], [w, 0], [w, h], [0, h]], dtype=np.float32)
    m = cv2.getPerspectiveTransform(points, pts_std)
    dst = cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    if dst.shape[0] * 1.0 / dst.shape[1] >= 1.5:
        dst = np.rot90(dst)
    return dst


def rec_resize_norm(img, max_wh_ratio):
    img_w = int(48 * max_wh_ratio)
    h, w = img.shape[:2]
    ratio = w / float(h)
    rw = img_w if math.ceil(48 * ratio) > img_w else int(math.ceil(48 * ratio))
    r = cv2.resize(img, (rw, 48)).astype(np.float32).transpose(2, 0, 1) / 255.0
    r = (r - 0.5) / 0.5
    pad = np.zeros((3, 48, img_w), dtype=np.float32)
    pad[:, :, :rw] = r
    return pad


def ctc_decode(idx, prob):
    sel = np.ones(len(idx), dtype=bool)
    sel[1:] = idx[1:] != idx[:-1]
    sel &= idx != 0
    if not sel.any():
        return '', 0.0
    text = ''.join(DICT[i] for i in idx[sel])
    return text, float(prob[sel].mean())


def rec_postprocess(preds, wh_ratios, max_wh):
    idx = preds.argmax(2)
    prob = preds.max(2)
    out = []
    for i in range(len(idx)):
        t, s = ctc_decode(idx[i], prob[i])
        out.append((t, s))
    return out


def sorted_boxes(boxes):
    sb = sorted(boxes, key=lambda x: (x[0][1], x[0][0]))
    for i in range(len(sb) - 1):
        for j in range(i, -1, -1):
            if abs(sb[j + 1][0][1] - sb[j][0][1]) < 10 and sb[j + 1][0][0] < sb[j][0][0]:
                sb[j], sb[j + 1] = sb[j + 1], sb[j]
            else:
                break
    return sb


def ocr(img):
    h0, w0 = img.shape[:2]
    ratio_h = ratio_w = 1.0
    if max(h0, w0) > 2000:
        s = 2000.0 / max(h0, w0)
        img = cv2.resize(img, (int(w0 * s), int(h0 * s)))
        ratio_h, ratio_w = s, s
    h, w = img.shape[:2]
    if min(h, w) < 30:
        s = 30.0 / min(h, w)
        img = cv2.resize(img, (int(w * s), int(h * s)))
        ratio_h, ratio_w = s * ratio_h, s * ratio_w
    pad_top = 0
    if h <= 30 or (w / h > 8):
        new_h = max(int(w / 8), 30) * 2
        pad_top = int(abs(new_h - h) / 2)
        img = cv2.copyMakeBorder(img, pad_top, pad_top, 0, 0, cv2.BORDER_REPLICATE)
    pred = det_sess.run(None, {'x': det_preprocess(img)})[0]
    boxes, scores = det_postprocess(pred, w, h)
    if not boxes:
        return []
    boxes = [order_points_clockwise(b) for b in boxes]
    lines = []
    crops = [rec_crop(img, b) for b in boxes]
    wh = [c.shape[1] / c.shape[0] for c in crops]
    order = np.argsort(wh)
    batch = 6
    res = [('', 0.0)] * len(crops)
    for beg in range(0, len(crops), batch):
        end = min(len(crops), beg + batch)
        max_wh = max(320 / 48, max(wh[i] for i in range(beg, end)))
        batch_imgs = np.concatenate([rec_resize_norm(crops[i], max_wh)[np.newaxis, :]
                                     for i in range(beg, end)]).astype(np.float32)
        preds = rec_sess.run(None, {'x': batch_imgs})[0]
        rr = rec_postprocess(preds, None, max_wh)
        for k, i in enumerate(range(beg, end)):
            res[order[beg + k]] = rr[k]
    for b, (t, s) in zip(boxes, res):
        if s >= 0.5:
            lines.append({'text': t, 'score': round(s, 3), 'box': b.tolist()})
    # map back to original image coords
    for L in lines:
        b = L['box']
        for i in range(4):
            b[i][0] = int(min(max(b[i][0] * ratio_w - 0, 0), w0 - 1))
            b[i][1] = int(min(max(b[i][1] * ratio_h - pad_top, 0), h0 - 1))
    return lines


if __name__ == '__main__':
    for path in sys.argv[1:]:
        img = cv2.imread(path)
        t0 = time.time()
        lines = ocr(img)
        dt = (time.time() - t0) * 1000
        print(f'== {path}: {len(lines)} lines in {dt:.0f} ms')
        for l in lines[:10]:
            print(f"   {l['score']:.2f}  {l['text']!r}")
