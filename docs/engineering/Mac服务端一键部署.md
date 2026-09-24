# Mac 服务端一键部署

适用于 Apple Silicon Mac 的原生 arm64 终端，仅部署服务端。不会安装 Android SDK、JDK、Android Studio，不下载手机端 KWS/AAR，也不构建 APK。先将完整项目源码放到新 Mac 的固定目录；后续不要随意移动目录，否则虚拟环境和自启动配置中的路径需要重新处理。

## 一条命令安装

在项目根目录执行：

```bash
bash scripts/setup_mac.sh
```

脚本会检查已有环境，只安装或修复缺失、不符合锁定版本、校验失败的部分。默认执行：

1. 检查 macOS/arm64、Apple Command Line Tools 和原生 Python 3.12；缺少 Python 时按需安装 Homebrew 与 `python@3.12`。
2. 创建或修复 `ai-service/.venv`，安装基础服务及 Apple Silicon 锁定依赖，检查实际包版本、`pip check` 和原生模块能否加载。
3. 创建或修复独立 `ai-service/.venv-tts`，检查 Qwen TTS 依赖。
4. 编译 Apple Vision OCR，并用内存空白图片检查实际执行结果。
5. 安装并校验 MLX Whisper large-v3-turbo 8bit、CPU faster-whisper small、Qwen3-TTS 1.7B CustomVoice 8bit、备用 Kokoro，以及项目专用 Ollama。CPU/Kokoro 用于已有服务支持的备用后端；历史对比模型不安装。服务端 Silero VAD 使用 `faster-whisper` 包内资源，不需要手机端 ONNX 下载。
6. 用临时的本机 Ollama 下载 `qwen3.5:2b`、`qwen3.5:4b`，校验固定清单和全部模型层，然后退出这个临时进程。
7. 仅在全新数据目录初始化家庭身份、数据库、证书和恢复材料；已有身份保持不变。
8. 配置当前用户的两个 LaunchAgents，启动 Ollama 和家庭 HTTPS 服务，等待 `/health` 显示模型预热 `ready`，最后显示手机连接地址。

首次 Command Line Tools 安装会弹出系统安装窗口，脚本在此退出；完成系统安装后重复同一命令继续。Homebrew 安装器可能要求管理员密码。不要 `sudo` 运行整个脚本。安装阶段需要能够访问 Python 包源、GitHub、Hugging Face、Ollama 模型仓库；安装完成后的模型推理只使用本地文件。

项目锁定 MLX 环境的已有实测平台为 macOS 26.2、M4/32GB；脚本以锁定依赖的真实安装、加载和预热结果判断成功，不承诺任意旧版 macOS 或小内存机器兼容。下载模型和保存下载归档需要较多磁盘空间，运行时书库、音频还会继续占用空间。脚本会显示当前可用空间，模型安装器另做下载/解包空间检查。

## 重复执行与只检查

```bash
# 自动检查和补装；已经符合要求时跳过安装，不重启正常运行的服务
bash scripts/setup_mac.sh

# 全面只读检查：还要求已配置自启动、HTTPS 可访问和模型预热成功
bash scripts/setup_mac.sh --check

# 只检查静态环境、模型和家庭身份，允许服务未启动
bash scripts/setup_mac.sh --check --no-start

# 安装环境、模型并初始化，但不配置登录自启动、不常驻运行或验证实际推理
bash scripts/setup_mac.sh --no-start
```

`--check` 不下载、不安装、不创建虚拟环境或身份文件、不启动/重启服务，也不写安装状态；若缺少 Python 3.12，入口会报告缺失并退出，不能继续 Python 层检查。静态检查会读取全部模型权重计算哈希，可能耗时数分钟。OCR 检查实际运行一次内存空白图识别；不会读取家庭照片或录音。

正常安装时，已安装的匹配模型不会重复下载。普通模型下载器使用 URL 独立缓存名，避免不同模型的 `config.json`、`model.bin` 相互覆盖，并兼容旧版缓存。Ollama/Kokoro 归档缓存是解包文件的完整性校验依据，请保留 `.artifacts/model-downloads`；归档缓存缺失时会报告无法校验，并在安装模式重新下载。Qwen TTS 支持断点续传和逐文件校验；其他模型下载中断后重复执行，可能重新下载未完成文件，但会跳过已校验的安装内容。

退出码 `0` 表示全部所选检查通过；`1` 表示存在缺失、安装失败或服务未就绪；`2` 表示参数或平台不支持。`--no-start` 成功只代表静态检查通过，不能当作模型推理验证。脚本不会用“进程已启动”代替模型预热成功。

## 指定连接地址与端口

```bash
bash scripts/setup_mac.sh --host 192.168.1.20 --port 8766
```

省略 `--host` 时通过默认网络接口检测局域网 IPv4 地址；VPN 或多网卡导致检测失败时请显式传入手机能访问的地址。端口首次默认 `8766`，之后沿用 `.artifacts/mac-setup/config.json` 保存的值；`11435` 保留给只监听本机的 Ollama。新安装的默认后端为 `mlx` ASR 与 `qwen3-mlx` TTS。

默认等待模型预热 420 秒，可用 `--timeout 600` 调整。下载没有包含在预热超时内。如果 Hugging Face 连接失败且已选择可信镜像，可以通过 `--tts-endpoint https://镜像地址` 只调整 Qwen TTS 下载入口，固定 revision 和校验值保持不变；其他模型仍使用锁文件中的来源。

脚本不自动修改 macOS 防火墙、路由器或公网端口映射；首次局域网访问遇到系统提示时允许服务通信。手机与 Mac 必须局域网互通，电脑应保持开机，笔记本不要合盖。自启动属于当前用户登录后的 LaunchAgents，不是开机未登录的系统服务。

## 失败处理与已有部署

- 服务正在运行而依赖或模型需要修改时，脚本停止，避免覆盖进程正在使用的文件。先执行 `bash scripts/home_service.sh stop`；若是前台服务，在原终端停止，再重跑安装。只读检查不要求停服。
- 已存在不同内容的同名 LaunchAgent 或端口被其他进程占用时，不自动接管、不终止进程。先确认配置归属；如要改用本脚本，停止原服务并备份、移走旧 plist，再运行。也可以用 `--no-start` 仅准备环境。
- 同一项目只允许一份安装脚本同时运行。锁文件位于 `.artifacts/mac-setup/install.lock`，进程结束后锁自动释放，不需要手工删除锁文件。
- 来自另一台机器或使用错误 Python 版本的虚拟环境会被移到 `ai-service/.venv-backup-*`，随后重建；确认部署正常后可自行删除旧环境备份。已有环境中的包版本不符时按锁文件修复。
- 部分家庭身份文件丢失、证书和私钥不配对时停止，不重新生成身份掩盖问题。迁移旧家庭时，先按《工程维护手册》停服备份并迁移完整运行身份，不能用普通书库 ZIP 替代身份迁移。
- 上游 Ollama 模型标签内容变更时，即使下载成功也会因清单不匹配停止，不会自动接受新权重或修改项目锁文件。

临时 Ollama 下载日志：`.artifacts/mac-setup/ollama-install.log`。常驻服务日志：`runtime/logs/org.familyrobot.ollama.log`、`runtime/logs/org.familyrobot.service.log`。安装失败不会删除已下载文件或家庭数据；修复网络、磁盘、权限等具体原因后重复原命令即可。

部署完成后，用 `bash scripts/home_service.sh start`、`stop`、`restart`、`status` 管理服务。手机连接、备份和身份恢复操作，见[工程维护手册](工程维护手册.md)。
