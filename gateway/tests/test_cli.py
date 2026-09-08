import io
import wave

from hermes_gateway.cli import main, read_audio
from hermes_gateway.stt import FakeTranscriber


def test_read_audio_accepts_wav_and_raw(tmp_path):
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(8000)
        w.writeframes(b"\x01\x00" * 800)
    wav = tmp_path / "a.wav"
    wav.write_bytes(buf.getvalue())
    pcm, rate = read_audio(wav)
    assert rate == 8000 and len(pcm) == 1600
    raw = tmp_path / "b.pcm"
    raw.write_bytes(b"\x00" * 3200)
    assert read_audio(raw) == (b"\x00" * 3200, 16000)


async def test_fake_transcriber_reports_length():
    assert await FakeTranscriber().transcribe(b"\x00" * 32000, 16000, "en-GB") == "fake transcript of 1000 ms at 16000 Hz in en-GB"


def test_token_commands(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("HERMES_GATEWAY_HOME", str(tmp_path))
    assert main(["token", "add", "phone"]) == 0
    token = capsys.readouterr().out.strip().splitlines()[-1]
    assert token.startswith("hg1_")
    assert main(["token", "list"]) == 0
    listing = capsys.readouterr().out
    assert "phone" in listing and "active" in listing
    device_id = listing.split()[0]
    assert main(["token", "revoke", device_id]) == 0
    assert main(["token", "list"]) == 0
    assert "revoked" in capsys.readouterr().out
    assert main(["version"]) == 0
