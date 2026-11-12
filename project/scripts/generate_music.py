"""Generate six original, deterministic instrumental mini-tracks. Standard library only.

No external recordings, samples, melodies or network services are used.
"""
from array import array
import json
import math
from pathlib import Path
import random
import sys
import wave

RATE = 22050
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/src/main/assets/music"
OUT.mkdir(parents=True, exist_ok=True)
PI2 = 2 * math.pi
TRACKS = [
    ("neon", 96, [60, 67, 64, 72, 67, 76, 64, 71], [0, -3, 5, 7], .65),
    ("cloud", 80, [62, 69, 65, 72, 69, 77, 65, 74], [0, 3, -2, 5], .25),
    ("picnic", 108, [67, 71, 74, 79, 76, 74, 71, 69], [0, 5, 7, 0], .48),
    ("wave", 88, [65, 72, 69, 77, 74, 72, 69, 67], [0, -2, 5, 3], .32),
    ("sunset", 104, [64, 71, 74, 76, 79, 76, 74, 71], [0, 5, -2, 3], .8),
    ("moon", 72, [60, 67, 72, 76, 74, 67, 64, 72], [0, 5, -3, 7], .12),
]


def add_note(buffer, start, length, midi, volume, decay, kind="pluck"):
    frequency = 440 * 2 ** ((midi - 69) / 12)
    first = int(start * RATE)
    count = min(int(length * RATE), len(buffer) - first)
    for n in range(max(0, count)):
        t = n / RATE
        attack = min(1, t / .012)
        release = min(1, (length - t) / .08)
        envelope = attack * release * math.exp(-decay * t)
        wave_value = math.sin(PI2 * frequency * t)
        if kind == "pluck":
            wave_value += .24 * math.sin(PI2 * frequency * 2 * t) + .08 * math.sin(PI2 * frequency * 3 * t)
        elif kind == "pad":
            wave_value = .6 * wave_value + .4 * math.sin(PI2 * frequency * 1.003 * t)
        buffer[first + n] += volume * envelope * wave_value


def percussion(buffer, start, kind, amount, rng):
    length = .23 if kind == "kick" else .12
    first = int(start * RATE)
    for n in range(min(int(length * RATE), len(buffer) - first)):
        t = n / RATE
        if kind == "kick":
            value = math.sin(PI2 * (48 * t + 8 * (1 - math.exp(-28 * t)))) * math.exp(-24 * t)
        else:
            value = rng.uniform(-1, 1) * math.exp((-40 if kind == "snare" else -95) * t)
        buffer[first+n] += amount * value


manifest = []
for number, (name, bpm, melody, progression, energy) in enumerate(TRACKS):
    beat = 60 / bpm
    duration = beat * 32 + .8  # Eight four-beat bars, with a release tail.
    samples = array("f", [0]) * int(duration * RATE)
    rng = random.Random(1010 + number)
    for bar in range(8):
        shift = progression[(bar // 2) % 4]
        root = melody[0] + shift
        for interval in (0, 4 if number % 2 == 0 else 3, 7, 11):
            add_note(samples, bar * beat * 4, beat * 4, root - 12 + interval, .035, .55, "pad")
        for step in range(8):
            start = (bar * 4 + step * .5) * beat
            midi = melody[(step + bar % 3) % 8] + shift
            add_note(samples, start, beat * .95, midi, .13 if step % 2 == 0 else .09, 5.5)
            if bar >= 2:
                add_note(samples, start + beat * .28, beat * .8, midi, .025, 6)
        for tick in range(4):
            start = (bar * 4 + tick) * beat
            add_note(samples, start, beat * .8, root - 24 + (7 if tick == 2 else 0), .13, 3.7, "bass")
            if tick % 2 == 0:
                percussion(samples, start, "kick", .13 * energy, rng)
            else:
                percussion(samples, start, "snare", .07 * energy, rng)
            percussion(samples, start + beat * .5, "hat", .05 * energy, rng)
    peak = max(abs(sample) for sample in samples) or 1
    pcm = array("h")
    for n, value in enumerate(samples):
        t = n / RATE
        fade = min(1, t / .35, (duration - t) / .8)
        pcm.append(int(max(-1, min(1, value / peak * .78 * fade)) * 32767))
    if sys.byteorder != "little":
        pcm.byteswap()
    with wave.open(str(OUT / f"{name}.wav"), "wb") as output:
        output.setparams((1, 2, RATE, len(pcm), "NONE", "not compressed"))
        output.writeframes(pcm.tobytes())
    manifest.append({"id": name, "bpm": bpm, "seconds": round(len(pcm)/RATE, 3), "frames": len(pcm)})
    print(f"Generated {name}: {len(pcm)/RATE:.1f}s", flush=True)
(OUT / "manifest.json").write_text(json.dumps({"version": 1, "sampleRate": RATE, "tracks": manifest}, indent=2) + "\n", encoding="utf-8")
