"""单进程复用已加载语音模型；超时/断流销毁进程，下一次重新加载。"""

import asyncio
import json
import os
import sys
from pathlib import Path


class UnstableSpeechError(RuntimeError):
    code = "tts_unstable_pace"
    retryable = True


class SpeechWorker:
    def __init__(self, module="robot_service.model_worker", python=None):
        self.process = None
        self.module = module
        self.python = python or sys.executable

    async def close(self):
        process, self.process = self.process, None
        if process is not None and process.returncode is None:
            process.kill()
            await process.wait()

    async def run(self, task, timeout):
        if self.process is None or self.process.returncode is not None:
            self.process = await asyncio.create_subprocess_exec(
                self.python,
                "-m",
                self.module,
                "--persistent",
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.DEVNULL,
                limit=16 * 1024 * 1024,
                env={
                    **os.environ,
                    "PYTHONPATH": str(Path(__file__).resolve().parents[1]),
                    "HF_HUB_OFFLINE": "1",
                    "TRANSFORMERS_OFFLINE": "1",
                    "HF_HUB_DISABLE_TELEMETRY": "1",
                },
            )

        async def exchange():
            self.process.stdin.write(
                (json.dumps(task, ensure_ascii=False) + "\n").encode()
            )
            await self.process.stdin.drain()
            line = await self.process.stdout.readline()
            if not line:
                raise RuntimeError("speech worker closed")
            result = json.loads(line)
            if "error" in result:
                if (
                    result.get("code") == "tts_unstable_pace"
                    and result["error"] == "SynthesisRetryableError"
                ):
                    raise UnstableSpeechError("语音节奏异常")
                raise RuntimeError("speech model failure")
            return result

        try:
            return await asyncio.wait_for(exchange(), timeout)
        except UnstableSpeechError:
            # 推理已正常结束并消费响应，保留模型；重新生成版本才能获得新候选。
            raise
        except BaseException:
            await self.close()
            raise
