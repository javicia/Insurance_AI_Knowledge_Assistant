import pathlib, collections

t = pathlib.Path("docs/testing/FUNCTIONAL_TEST_MATRIX_ES.md").read_text(encoding="utf-8")
section = ""
per = collections.OrderedDict()
for l in t.splitlines():
    if l.startswith("## "):
        section = l[3:].strip()
    if l.startswith("|") and l.count("|") - 1 == 10 and not l.startswith("| ID") and "---" not in l.split("|")[1]:
        cells = [x.strip() for x in l.strip().strip("|").split("|")]
        per.setdefault(section, collections.Counter())[cells[8]] += 1
for k, v in per.items():
    print(k, "->", sum(v.values()), dict(v))
