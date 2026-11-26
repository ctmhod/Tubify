import sys, re, xml.etree.ElementTree as ET
# usage: tap.py ui.xml "text"  -> prints "x y" center of first node whose text equals/starts with arg
f, want = sys.argv[1], sys.argv[2]
t = ET.parse(f)
for n in t.iter('node'):
    tx = n.get('text') or ''
    if tx == want or tx.startswith(want):
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds'))
        a = list(map(int, m.groups()))
        print((a[0]+a[2])//2, (a[1]+a[3])//2)
        sys.exit(0)
sys.exit(1)
