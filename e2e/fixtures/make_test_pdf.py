"""Generates a minimal, valid single-page PDF with extractable text, for E2E upload testing."""

text = (
    "Standard Auto Insurance Policy Coverage. This policy covers third party liability, "
    "collision damage, fire and theft. The maximum coverage limit is 50000 euros per claim. "
    "The deductible for collision damage is 300 euros."
)

content_stream = f"BT /F1 12 Tf 50 700 Td ({text}) Tj ET".encode("latin-1")

objects = []
objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
objects.append(
    b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
    b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"
)
objects.append(
    b"<< /Length " + str(len(content_stream)).encode() + b" >>\nstream\n" + content_stream + b"\nendstream"
)
objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

out = bytearray(b"%PDF-1.4\n")
offsets = []
for i, obj in enumerate(objects, start=1):
    offsets.append(len(out))
    out += str(i).encode() + b" 0 obj\n" + obj + b"\nendobj\n"

xref_pos = len(out)
out += b"xref\n0 " + str(len(objects) + 1).encode() + b"\n"
out += b"0000000000 65535 f \n"
for off in offsets:
    out += ("%010d 00000 n \n" % off).encode()
out += b"trailer\n<< /Size " + str(len(objects) + 1).encode() + b" /Root 1 0 R >>\n"
out += b"startxref\n" + str(xref_pos).encode() + b"\n%%EOF\n"

with open(r"D:\Javier\Proyectos\rag_springai_prueba\e2e_test_policy.pdf", "wb") as f:
    f.write(bytes(out))

print("wrote", len(out), "bytes")
