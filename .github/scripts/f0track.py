#!/usr/bin/env python3
"""Pitch contours of utterances (stdlib only).

Usage: f0track.py DIR   (reads DIR/*.wav, else DIR/*.s16 at 11025 Hz)
Prints per-file peak/rms/frames plus the F0 contour (Hz, 50 ms steps)
and the last-500 ms contour shape, which is where ! and . differ.
"""
import array
import math
import os
import sys
import wave

FALLBACK_RATE = 11025


def read_s16(path):
    with open(path, "rb") as f:
        raw = f.read()
    a = array.array("h")
    a.frombytes(raw)
    if sys.byteorder == "big":
        a.byteswap()
    return a, FALLBACK_RATE


def f0_autocorr(frame, rate):
    n = len(frame)
    if n < 4:
        return 0.0
    mean = sum(frame) / n
    x = [v - mean for v in frame]
    energy = sum(v * v for v in x)
    if energy <= 0:
        return 0.0
    # 50..500 Hz -> lag rate/500..rate/50
    best_lag, best_val = 0, 0.0
    lo = max(2, rate // 500)
    max_lag = min(n - 1, rate // 50)
    for lag in range(lo, max_lag + 1):
        s = 0.0
        for i in range(n - lag):
            s += x[i] * x[i + lag]
        v = s / energy
        if v > best_val:
            best_val, best_lag = v, lag
    if best_val < 0.3:
        return 0.0
    return rate / best_lag


def contour(a, rate, step_ms=50, win_ms=40):
    step = rate * step_ms // 1000
    win = rate * win_ms // 1000
    out = []
    i = 0
    while i + win <= len(a):
        out.append(f0_autocorr(a[i:i + win], rate))
        i += step
    return out


def read_wav(path):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        n = w.getnframes()
        sw = w.getsampwidth()
        ch = w.getnchannels()
        raw = w.readframes(n)
    a = array.array("h")
    a.frombytes(raw[: n * ch * sw])
    if sys.byteorder == "big":
        a.byteswap()
    if ch == 2:
        a = array.array("h", a[0::2])
    if sw != 2:
        raise ValueError("only 16-bit supported")
    return a, rate


def main(d):
    wavs = sorted(f for f in os.listdir(d) if f.endswith(".wav"))
    files = wavs or sorted(f for f in os.listdir(d) if f.endswith(".s16"))
    for name in files:
        p = os.path.join(d, name)
        if name.endswith(".wav"):
            a, rate = read_wav(p)
        else:
            a, rate = read_s16(p)
            wpath = p[:-4] + ".wav"
            with wave.open(wpath, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(rate)
                w.writeframes(a.tobytes())
        n = len(a)
        peak = max(abs(x) for x in a)
        rms = int(math.sqrt(sum(x * x for x in a) / n))
        c = contour(a, rate)
        tail = c[-10:] if len(c) >= 10 else c
        nz = [v for v in tail if v > 0]
        trend = ""
        if len(nz) >= 3:
            trend = "rise" if nz[-1] > nz[0] * 1.1 else ("fall" if nz[-1] < nz[0] * 0.9 else "flat")
        print("%s @%dHz: peak=%d rms=%d frames=%d dur=%.2fs" % (name, rate, peak, rms, n, n / rate))
        print("  f0full=" + ",".join("%d" % v for v in c))
        print("  f0tail=" + ",".join("%d" % v for v in tail) + " trend=" + trend)


main(sys.argv[1])
