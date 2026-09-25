import io
import wave

import numpy as np
import pytest
from robot_service.speech_audio import finish_audio


def wav_bytes(samples, rate=24000, channels=1, width=2):
    output = io.BytesIO()
    with wave.open(output, "wb") as target:
        target.setnchannels(channels)
        target.setsampwidth(width)
        target.setframerate(rate)
        target.writeframes(np.asarray(samples, dtype="<i2").tobytes())
    return output.getvalue()


def read_wav(data):
    with wave.open(io.BytesIO(data), "rb") as source:
        assert source.getnchannels() == 1 and source.getsampwidth() == 2
        return np.frombuffer(
            source.readframes(source.getnframes()), dtype="<i2"
        ), source.getframerate()


@pytest.mark.parametrize("rate", [8000, 24000, 44100, 48000])
@pytest.mark.parametrize("speed", [0.7, 0.85, 1.0, 1.15, 1.3])
def test_atempo_changes_duration_preserves_frequency_and_sample_rate(rate, speed):
    tone = np.rint(6000 * np.sin(2 * np.pi * 440 * np.arange(rate * 2) / rate))
    original = wav_bytes(tone, rate)
    processed = finish_audio(original, speed)
    signal, output_rate = read_wav(processed)
    assert output_rate == rate
    assert len(signal) / rate == pytest.approx(2 / speed, abs=0.035)
    # 去掉算法边缘窗口，对稳定部分测主频。
    middle = signal[len(signal) // 4 : len(signal) * 3 // 4].astype(float)
    spectrum = abs(np.fft.rfft(middle * np.hanning(len(middle))))
    fundamental = np.fft.rfftfreq(len(middle), 1 / rate)[np.argmax(spectrum)]
    assert fundamental == pytest.approx(440, abs=2)
    assert processed == finish_audio(original, speed)


def test_long_zero_edges_trim_with_guard_and_preserve_weak_consonants():
    rate = 24000
    # 一计数弱音也保留，不能当作噪声/静音切掉。
    speech = np.concatenate([np.ones(2400), np.full(4800, 5000), np.ones(2400)])
    source = wav_bytes(np.concatenate([np.zeros(rate), speech, np.zeros(rate)]))
    actual, output_rate = read_wav(finish_audio(source))
    guard = round(rate * 0.12)
    assert output_rate == rate
    expected = np.pad(speech, (guard, guard))
    np.testing.assert_array_equal(actual != 0, expected != 0)
    # 整段 RMS 衰减不应让一计数的首尾辅音消失。
    np.testing.assert_array_equal(actual[guard : guard + 2400], 1)
    np.testing.assert_array_equal(actual[-guard - 2400 : -guard], 1)


def test_internal_pauses_and_short_edges_are_unchanged():
    samples = np.concatenate(
        [np.zeros(2400), np.ones(2400), np.zeros(24000), np.ones(2400), np.zeros(2400)]
    )
    source = wav_bytes(samples)
    assert finish_audio(source) == source


@pytest.mark.parametrize("speed", [0.7, 1, 1.3])
def test_silence_is_returned_byte_for_byte(speed):
    source = wav_bytes(np.zeros(24000))
    assert finish_audio(source, speed) is source


def test_peak_is_attenuated_but_quiet_audio_is_not_amplified():
    signal, _ = read_wav(finish_audio(wav_bytes([32767, -32768] * 2400)))
    assert max(abs(signal.astype(float))) <= np.ceil(32768 * 0.95)
    quiet = wav_bytes([1, -1, 10, -10] * 2400)
    assert finish_audio(quiet) == quiet


@pytest.mark.parametrize(("amplitude", "gain_db"), [(800, 3), (8000, -3)])
def test_rms_gain_is_bounded_and_ignores_long_silent_pauses(amplitude, gain_db):
    samples = np.tile([amplitude, -amplitude], 2400)
    # 内部停顿不应拉低参考 RMS，导致错误增益。
    source = wav_bytes(np.concatenate([samples, np.zeros(24000), samples]))
    actual, _ = read_wav(finish_audio(source))
    gain = abs(actual[0]) / amplitude
    assert 20 * np.log10(gain) == pytest.approx(gain_db, abs=0.015)
    np.testing.assert_array_equal(actual[4800:28800], 0)


def test_peak_ceiling_overrides_rms_gain_for_high_crest_factor():
    samples = np.full(24000, 1000)
    samples[::480] = 32767
    actual, _ = read_wav(finish_audio(wav_bytes(samples)))
    assert max(actual) / 32768 == pytest.approx(0.95, abs=1 / 32768)


def test_low_energy_background_is_neither_trimmed_nor_boosted():
    samples = np.tile([50, -50], 24000)
    source = wav_bytes(samples)
    assert finish_audio(source) == source


@pytest.mark.parametrize("speed", [0.7, 1.3])
@pytest.mark.parametrize("length", [1, 240, 1200])
def test_short_non_silent_clips_are_not_swallowed(length, speed):
    source = wav_bytes(np.full(length, 2000))
    actual, rate = read_wav(finish_audio(source, speed))
    assert rate == 24000 and len(actual) and np.any(actual)
    assert len(actual) >= round(length / speed)


@pytest.mark.parametrize(
    "speed", [0, 0.69, 1.31, float("nan"), float("inf"), True, "1", None]
)
def test_invalid_speed_rejected_even_for_silence(speed):
    with pytest.raises(ValueError):
        finish_audio(wav_bytes(np.zeros(100)), speed)


@pytest.mark.parametrize(
    "data",
    [
        b"",
        b"not-a-wave",
        None,
        bytearray(b"RIFF"),
        wav_bytes([]),
        wav_bytes([1] * 10, channels=2),
        wav_bytes([1] * 10, width=1),
        wav_bytes([1] * 10, rate=4000),
        wav_bytes([1] * 10)[:-1],
    ],
)
def test_invalid_audio_rejected(data):
    with pytest.raises(ValueError):
        finish_audio(data)
