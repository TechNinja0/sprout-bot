# 家庭本地服务

Python3.12、FastAPI、SQLite WAL，绑定受信TLS的设备角色。只调用本机模型，默认不开启任何云端文本/图像/音频通道。它不替代Android即时停止和使用时段执行。

```sh
# 在仓库根目录
bash scripts/bootstrap_service.sh
ai-service/.venv/bin/python scripts/install_models.py
# Apple Silicon 默认 TTS（其他平台使用原 Kokoro 并设置 ROBOT_TTS_BACKEND=kokoro）
bash scripts/bootstrap_tts.sh
python3 scripts/install_tts.py
ai-service/.venv/bin/robot-service --data runtime init --host <家庭电脑局域网地址>
ai-service/.venv/bin/robot-service --data runtime serve --bind 0.0.0.0
```

本地LLM/VLM独立使用Ollama127.0.0.1:11435，安装与固定模型校验见[维护手册](../docs/engineering/工程维护手册.md)。首次服务材料仅两分钟有效，手机无需购买域名。

默认 TTS 为本地 Qwen3-TTS 1.7B CustomVoice（MLX 8bit），支持预置音色、五种语气和自定义描述。独立安装、试听、切换 Kokoro 及新增引擎接口见[本地语音合成](../docs/engineering/本地语音合成.md)。

| 模块 | 责任 |
| --- | --- |
| `auth.py` / `store.py` | 登记、配对/撤销、短时命令、角色权限与事务 |
| `library.py` | 草稿、固定发布版本、识书检索、原文清单和进度 |
| `audio_preparation.py` | 发布音频持久队列、重启恢复、按需与后台合成去重、进度及重试 |
| `imports.py` / `extract.py` | 家长原稿导入、页级OCR、任务重试取消 |
| `intelligence.py` / `model_worker.py` | 本地ASR/TTS/LLM/VLM、明确意图、超时、临时上下文 |
| `tts.py` / `tts_worker.py` / `tts_qwen.py` / `tts_kokoro.py` | 可替换的本地语音接口、单实例独立环境、音色与语气能力 |
| `games.py` | 主动开始、三题/两分钟以内的本地短游戏，不保存成绩 |
| `mlx_asr.py` | 可选Apple Silicon单窗口ASR，完整30秒输入与前置静音过滤 |
| `preload.py` / `worker_channel.py` | 可选本地预热、模型进程驻留、硬超时销毁 |
| `management.py` | 最小记忆审核、播放清单、使用摘要、备份墓碑 |
| `keywords.py` | 昵称与端侧有限口令词表，离线图书别名 |
| `cli.py` | TLS初始化、恢复、备份和服务启动 |

`ai-service/.venv/bin/python -m pytest ai-service/tests -q`使用隔离临时库；`scripts/model_smoke.py`另行调用真实模型与原创资料。真实家庭资料、目标儿童与长时测试单列，见[自测记录](../docs/engineering/自测记录.md)。

图书发布后默认自动准备朗读音频，家长可在资源详情查看进度和重试；参见[图书音频准备与连贯播放](../docs/engineering/图书音频准备与连贯播放.md)。
