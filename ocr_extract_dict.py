import re, ast

yml = open('/work/.ocr-models/ppocrv5_rec.yml', encoding='utf-8').read()
lines = yml.splitlines()
start = next(i for i, l in enumerate(lines) if l.strip() == 'character_dict:')
chars = []
for ln in lines[start + 1:]:
    if re.match(r'^  - ', ln):
        raw = ln[4:]
        try:
            c = ast.literal_eval(raw)
        except Exception:
            c = raw.strip("'")
        chars.append(c)
    elif ln.strip() == '':
        continue
    else:
        break
print('dict size:', len(chars))
print('first 6:', [repr(c) for c in chars[:6]])
print('last 4:', [repr(c) for c in chars[-4:]])
dup = len(chars) - len(set(chars))
print('duplicates:', dup)
assert ' ' not in chars and '\u3000' in chars
with open('/work/.ocr-models/rec_char_dict.txt', 'w', encoding='utf-8') as f:
    f.write('\n'.join(chars) + '\n')
print('written rec_char_dict.txt, lines:', len(chars))
