import csv, sys

cats = sys.argv[1].split(",")
out = sys.argv[2]

with open("docs/testing/evaluation_dataset.csv", encoding="utf-8") as f:
    rows = list(csv.DictReader(f))

with open(out, "w", encoding="utf-8") as w:
    for r in rows:
        if r["category"] in cats:
            w.write("%s | %s | EXP: %s | GR: %s | DOC: %s | CAT: %s | DIF: %s\n" % (
                r["id"], r["question"], r["expected_answer"], r["expected_grounding"],
                r["expected_document"], r["category"], r["difficulty"]))
