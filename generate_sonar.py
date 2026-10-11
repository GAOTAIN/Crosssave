"""离线生成五秒声纳白噪音；缺少可选依赖时只用标准库生成 WAV。"""

import argparse
import cmath
import importlib.util
import math
from pathlib import Path
import random
import struct
import wave

SAMPLE_RATE = 48000
DURATION = 5
SAMPLE_COUNT = SAMPLE_RATE * DURATION
NOISE_LOW = 180
NOISE_HIGH = 1800
NOISE_GAIN = 0.075
PING_FREQUENCY = 440
PING_GAIN = 0.22
PING_DURATION = 0.32
ATTACK_SECONDS = 0.04
DECAY_SECONDS = 0.13


def noise_response(frequency):
    # 缓慢收起两端频带，重点削弱一千赫兹以上的嘶声，避免尖锐的高频边缘。
    if frequency < NOISE_LOW or frequency > NOISE_HIGH:
        return 0.0
    if frequency < 280:
        return 0.5 - 0.5 * math.cos(math.pi * (frequency - NOISE_LOW) / (280 - NOISE_LOW))
    if frequency > 1000:
        return 0.5 + 0.5 * math.cos(math.pi * (frequency - 1000) / (NOISE_HIGH - 1000))
    return 1.0


def fft(values, inverse=False):
    # 原地基二快速傅里叶变换，只依赖标准库；输入长度必须是二的整数次幂。
    length = len(values)
    index = 0
    for position in range(1, length):
        bit = length >> 1
        while index & bit:
            index ^= bit
            bit >>= 1
        index ^= bit
        if position < index:
            values[position], values[index] = values[index], values[position]
    size = 2
    direction = 1 if inverse else -1
    while size <= length:
        half = size // 2
        step = cmath.exp(direction * 2j * math.pi / size)
        factors = [1 + 0j]
        for _ in range(1, half):
            factors.append(factors[-1] * step)
        for start in range(0, length, size):
            for offset in range(half):
                left = start + offset
                right = left + half
                even = values[left]
                odd = values[right] * factors[offset]
                values[left] = even + odd
                values[right] = even - odd
        size *= 2
    if inverse:
        for position in range(length):
            values[position] /= length


def standard_library_noise():
    # 固定种子便于复现；保留较低频的白噪音，并平滑削减高频及其共轭频带。
    generator = random.Random(2604)
    length = 1 << (SAMPLE_COUNT - 1).bit_length()
    values = [complex(generator.gauss(0, 1)) for _ in range(SAMPLE_COUNT)]
    values.extend([0j] * (length - SAMPLE_COUNT))
    fft(values)
    for index in range(length):
        frequency = min(index, length - index) * SAMPLE_RATE / length
        values[index] *= noise_response(frequency)
    fft(values, inverse=True)
    return [values[index].real for index in range(SAMPLE_COUNT)]


def mix_pings(noise):
    result = [sample * NOISE_GAIN for sample in noise]
    ping_length = round(PING_DURATION * SAMPLE_RATE)
    attack_length = round(ATTACK_SECONDS * SAMPLE_RATE)
    for second in range(DURATION):
        start = second * SAMPLE_RATE
        for index in range(ping_length):
            time = index / SAMPLE_RATE
            attack = 0.5 - 0.5 * math.cos(math.pi * min(1, index / attack_length))
            release = 0.5 * (1 + math.cos(math.pi * index / ping_length))
            envelope = attack * math.exp(-time / DECAY_SECONDS) * release
            result[start + index] += PING_GAIN * envelope * math.sin(2 * math.pi * PING_FREQUENCY * time)
    # 首尾短淡入淡出减少循环接缝的爆音，不改变总时长与每秒一次的节奏。
    fade_length = round(0.02 * SAMPLE_RATE)
    for index in range(fade_length):
        gain = 0.5 - 0.5 * math.cos(math.pi * index / fade_length)
        result[index] *= gain
        result[-1 - index] *= gain
    peak = max(abs(value) for value in result)
    gain = min(1.0, 0.85 / peak) if peak else 1.0
    return [value * gain for value in result]


def write_wav(path, samples):
    with wave.open(str(path), 'wb') as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(b''.join(struct.pack('<h', round(sample * 32767)) for sample in samples))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--wav-only', action='store_true', help='强制使用标准库生成 WAV')
    arguments = parser.parse_args()
    # 脚本放在项目根目录，输出位置由脚本自身决定，不依赖机器的绝对路径。
    root = Path(__file__).resolve().parent
    sound_directory = root / 'src/main/resources/assets/crosssave/sounds'
    sound_directory.mkdir(parents=True, exist_ok=True)
    packages = ('numpy', 'scipy', 'soundfile')
    available = all(importlib.util.find_spec(package) is not None for package in packages)
    if available and not arguments.wav_only:
        try:
            import numpy as np
            from scipy import fft as scipy_fft
            import soundfile as sf
            noise = np.random.default_rng(2604).normal(size=SAMPLE_COUNT)
            spectrum = scipy_fft.rfft(noise)
            frequencies = scipy_fft.rfftfreq(SAMPLE_COUNT, 1 / SAMPLE_RATE)
            spectrum *= np.asarray([noise_response(float(frequency)) for frequency in frequencies])
            samples = mix_pings(scipy_fft.irfft(spectrum, n=SAMPLE_COUNT))
            target = sound_directory / 'sonar.ogg'
            sf.write(str(target), samples, SAMPLE_RATE, format='OGG', subtype='VORBIS')
            print(f'已生成 OGG：{target}')
            return
        except (ImportError, OSError, RuntimeError):
            print('可选音频依赖无法使用，改用标准库生成 WAV，不安装任何软件包。')
    else:
        print('可选依赖不完整或指定仅生成 WAV，使用标准库离线生成。')
    samples = mix_pings(standard_library_noise())
    target = root / 'sonar.wav'
    write_wav(target, samples)
    print(f'已生成 WAV：{target}，单声道，48000 Hz，5 秒。')
    print('请转换为 Vorbis OGG，保存到 src/main/resources/assets/crosssave/sounds/sonar.ogg，然后重新构建。')


if __name__ == '__main__':
    main()
