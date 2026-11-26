"""Synthesises the dice-roll sound effects in app/src/main/res/raw.

Each sound is a few dice clattering onto a table: a dense burst of hits, then
bounces that get quieter and further apart. Everything is generated, so there
are no third-party audio files in the app.

Usage: python scripts/make-dice-sounds.py
Needs numpy.
"""
import wave
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "raw"
RATE = 22050
LENGTH = 1.25  # seconds; matches the roll animation


def hit(rng, strength, bright):
    """One impact: a click of filtered noise plus a few damped resonances."""
    n = int(RATE * 0.09)
    t = np.arange(n) / RATE
    out = np.zeros(n)
    # Plastic/wood body resonances.
    for _ in range(4):
        f = rng.uniform(1800, 5200) * bright
        decay = rng.uniform(0.006, 0.022)
        out += rng.uniform(0.4, 1.0) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.3)) * np.exp(-t / decay)
    # Table thump.
    out += 0.9 * np.sin(2 * np.pi * rng.uniform(140, 240) * t) * np.exp(-t / 0.018)
    # Sharp attack click.
    click = rng.normal(0, 1, n) * np.exp(-t / 0.0015)
    out += 1.4 * click
    return strength * out / np.max(np.abs(out))


def die_track(rng, total):
    """Hit times for one die: tumbling burst, then decaying bounces."""
    events = []
    t = rng.uniform(0.0, 0.06)
    # Tumbling: quick irregular hits.
    while t < 0.45:
        events.append((t, rng.uniform(0.5, 1.0)))
        t += rng.uniform(0.025, 0.07)
    # Bounces: gaps shrink and strength fades as the die settles.
    gap, s = rng.uniform(0.12, 0.17), 0.8
    while t < total - 0.1 and s > 0.06:
        events.append((t, s))
        t += gap
        gap *= rng.uniform(0.62, 0.75)
        s *= rng.uniform(0.5, 0.65)
        if gap < 0.012:
            break
    return events


def make(seed, dice=3):
    rng = np.random.default_rng(seed)
    n = int(RATE * LENGTH)
    buf = np.zeros(n + RATE)
    for _ in range(dice):
        bright = rng.uniform(0.85, 1.2)
        for t, s in die_track(rng, LENGTH):
            h = hit(rng, s, bright)
            i = int(t * RATE)
            buf[i:i + len(h)] += h
    buf = buf[:n]
    # Gentle fade-out so the end never clicks.
    fade = int(RATE * 0.08)
    buf[-fade:] *= np.linspace(1, 0, fade)
    buf = buf / np.max(np.abs(buf)) * 0.7
    return (buf * 32767).astype("<i2")


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for i, seed in enumerate((11, 23, 47), start=1):
        path = OUT_DIR / f"dice_roll_{i}.wav"
        with wave.open(str(path), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(make(seed).tobytes())
        print(f"wrote {path}")


if __name__ == "__main__":
    main()
