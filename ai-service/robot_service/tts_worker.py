"""独立 TTS 进程：一个模型实例服务全部合成请求，不与 ASR 依赖混装。"""

import base64
import contextlib
import json
import sys
from pathlib import Path

from .tts import create_provider, make_request


def main():
    provider = None
    for line in sys.stdin:
        try:
            task = json.loads(line)
            request = make_request(
                task["text"], task.get("voice", {}), task.get("story", False)
            )
            # 第三方模型可能向 stdout 打印加载消息；协议 stdout 只允许一行 JSON。
            with contextlib.redirect_stdout(sys.stderr):
                if provider is None:
                    provider = create_provider(Path(task["root"]))
                audio = provider.synthesize(request)
            result = {
                "audio": base64.b64encode(audio.wav).decode(),
                "durationMs": audio.duration_ms,
            }
        except Exception as exc:
            result = {"error": type(exc).__name__}
        sys.stdout.write(json.dumps(result, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
