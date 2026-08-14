#!/usr/bin/env python3
"""Reproduces FakeEmbeddingModelAdapter's tokenisation to show what it does to Spanish text.

The adapter tokenises with the regex `[a-z0-9]+` after `toLowerCase()`. That character class
excludes accented vowels and 'ñ', so every accented Spanish word is SPLIT into fragments rather
than kept whole. This script prints the effect, because it is the concrete reason several Spanish
evaluation cases retrieve nothing.
"""

from __future__ import annotations

import re

# Exactly the adapter's pattern: no accents, no 'ñ'.
ADAPTER_PATTERN = re.compile(r"[a-z0-9]+")

SAMPLES = [
    "colisión",
    "daños propios",
    "póliza",
    "indemnización",
    "vehículo eléctrico",
    "granizo",           # sin acento: sobrevive intacta
    "estacionado",       # sin acento: sobrevive intacta
    "identifican",
    "identificado",
]

print("Tokenizacion real de FakeEmbeddingModelAdapter (patron [a-z0-9]+)\n")
print(f"{'texto':<24} -> tokens")
for sample in SAMPLES:
    tokens = ADAPTER_PATTERN.findall(sample.lower())
    marker = "  <-- FRAGMENTADA" if any(len(t) <= 2 for t in tokens) or len(tokens) > len(sample.split()) else ""
    print(f"{sample:<24} -> {tokens}{marker}")

print("\nConsecuencias para el corpus espanol:")
print("  1. Toda palabra acentuada se parte en fragmentos ('colisión' -> 'colisi' + 'n').")
print("     Los fragmentos cortos colisionan con muchisimos otros terminos al hacer hashing,")
print("     asi que aportan ruido en lugar de senal.")
print("  2. No hay lematizacion: 'identifican' e 'identificado' son tokens distintos y")
print("     no comparten ningun bucket, pese a ser la misma idea.")
print("\nAmbos efectos reducen la similitud coseno entre una pregunta en espanol y el fragmento")
print("que la responde. Es la causa concreta de que FT-249 y FT-254 no recuperen nada.")
print("\nNO afecta a un modelo de embeddings real, que trabaja con subpalabras y captura")
print("semantica: esta limitacion es del sustituto offline, no del diseno del sistema.")
