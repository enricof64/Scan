import onnxruntime as ort

for name in ('det', 'rec'):
    s = ort.InferenceSession(f'/work/.ocr-models/ppocrv5_{name}.onnx', providers=['CPUExecutionProvider'])
    print(f'== {name}')
    for i in s.get_inputs():
        print('  in :', i.name, i.shape, i.type)
    for o in s.get_outputs():
        print('  out:', o.name, o.shape, o.type)
