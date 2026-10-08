"""Deterministic synthesized dice roll draft. Standard library only."""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 44100
LENGTH = 1.35
rng = random.Random(20261008)
samples = [0.0] * int(RATE * LENGTH)
# Short plastic clicks plus softer wooden tabletop resonance. Contacts slow down.
contacts = [(0.03, 0.9), (0.09, 0.75), (0.17, 0.82), (0.26, 0.67),
            (0.37, 0.59), (0.49, 0.48), (0.62, 0.39), (0.77, 0.31),
            (0.94, 0.23), (1.12, 0.14)]
for start, strength in contacts:
    offset = int(start * RATE)
    pitch = rng.uniform(0.88, 1.12)
    prev = 0.0
    for i in range(int(0.15 * RATE)):
        if offset + i >= len(samples):
            break
        t = i / RATE
        noise = rng.uniform(-1, 1)
        filtered = noise - 0.7 * prev
        prev = noise
        click = 0.38 * filtered * math.exp(-t / 0.0035)
        body = (0.29 * math.sin(2 * math.pi * 740 * pitch * t)
                + 0.15 * math.sin(2 * math.pi * 1320 * pitch * t)) * math.exp(-t / 0.014)
        wood = 0.13 * math.sin(2 * math.pi * 205 * pitch * t) * math.exp(-t / 0.026)
        attack = min(1.0, t / 0.0004)
        samples[offset + i] += strength * attack * (click + body + wood)
# Very quiet irregular surface rattle between impacts.
for i in range(int(0.04 * RATE), int(1.1 * RATE)):
    t = i / RATE
    samples[i] += rng.uniform(-1, 1) * 0.012 * math.exp(-t / 0.3)
peak = max(abs(x) for x in samples)
gain = 0.75 / peak
dest = Path(__file__).parent / '骰子投掷-滚动落定-v1.wav'
with wave.open(str(dest), 'wb') as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(RATE)
    out.writeframes(b''.join(struct.pack('<h', round(x * gain * 32767)) for x in samples))
with wave.open(str(dest), 'rb') as check:
    assert check.getnframes() == len(samples)
    assert check.getframerate() == RATE
print(f'{dest}\n{LENGTH:.2f}s, mono PCM16, {RATE}Hz, peak -2.5dBFS')
