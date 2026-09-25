# 局域网官网与 APK 分发

项目自带两条安装/升级通道，全部运行在家庭局域网内，不需要公网服务器和应用商店：

| 通道 | 地址 | 用途 | 鉴权 |
| --- | --- | --- | --- |
| 下载官网（release site） | `http://<电脑局域网IP>:8767` | 首次安装、扫码下载、手动下载历史版本 | 无（只读公开 APK） |
| App 内升级 | 家庭 HTTPS 服务 `https://<电脑局域网IP>:8766` | 家长端启动自动检查、弹窗升级 | 家长设备凭据 + TLS 指纹 |

官网与家庭服务解耦：官网是纯 HTTP 的只读静态分发（手机浏览器打开自签名 HTTPS 会告警，因此官网用 HTTP 且不含任何敏感数据）；App 内升级始终走已配对的加密通道。

## 日常操作

### 发布新版本（构建即自动发布）

```bash
# 修改 android-app/app/build.gradle.kts 中的 versionCode / versionName 后：
bash scripts/release_app.sh --release "本次更新说明"   # 正式签名版（家庭使用）
bash scripts/release_app.sh "本次更新说明"             # debug 版（本机调试）
```

该脚本会构建对应变体（`--release` 读取 `runtime/android-signing/` 正式签名）并把 APK 归档到 `runtime/releases/`：
- 版本号来自 `build.gradle.kts`，**versionCode 必须递增**；重复发布同版本需 `--force`；
- 自动计算 SHA256、写入 `manifest.json`、保留最近 3 个安装包；
- **发布不需要重启官网服务**，网页与 App 检查即时生效。

单独归档一个已有 APK（例如 CI 构建产物）：

```bash
ai-service/.venv/bin/python scripts/release_site.py archive \
  --apk <路径>.apk --version-name 0.2.0 --version-code 2 --notes "说明"
```

### 启动下载官网

推荐交给 launchd 托管（执行一次即可）：关闭终端仍运行、异常退出自动重启、登录 macOS 后自动启动，日志在 `runtime/logs/org.familyrobot.release-site.log`。

```bash
bash scripts/release_site_service.sh install    # 安装并启动
bash scripts/release_site_service.sh status     # 查看状态与健康检查
bash scripts/release_site_service.sh restart    # 重启
bash scripts/release_site_service.sh uninstall  # 停止并移除托管
```

也可以前台手动运行（调试用）：

```bash
bash scripts/run_release_site.sh          # 前台运行，默认 0.0.0.0:8767
# 可用 RELEASE_BIND / RELEASE_PORT 覆盖
```

macOS 注意：托管配置直接以 Python 启动官网（不经 bash），因为系统隐私保护会拒绝 launchd 进程读取 `~/Documents` 下的 shell 脚本；若托管启动失败且日志含 `Operation not permitted`，按脚本提示在“系统设置 → 隐私与安全性 → 完全磁盘访问权限”中授权 Python 后重试。

手机相机扫描官网页面上的二维码，或浏览器直接访问 `http://<电脑局域网IP>:8767`，点击下载后按系统提示允许“安装未知应用”即可。查看电脑 IP 的方式见《家庭局域网启动指南》。

注意：App 内升级接口位于家庭 HTTPS 服务（8766）中，新增路由需要先重启家庭服务生效：

```bash
bash scripts/home_service.sh restart
```

## App 升级行为

- **家长端**：每次进入家长端自动静默检查一次；发现新版本弹窗提示（显示版本、大小、更新说明），可“暂不升级”；取消后随时在 设置 → 设备与服务 → 检查更新 手动检查并安装。
- **伙伴端（机器人身份）**：完全没有任何升级提示或弹窗，表情页不受打扰。机器人手机的升级由家长在官网下载同一安装包完成。
- 升级包通过 8766 加密通道流式下载并做 SHA256 完整性校验，随后调起系统安装器。

## 约束与注意

- **签名一致**：新 APK 必须与手机上已安装版本使用同一签名，否则系统会拒绝安装并提示签名不一致。debug 与 release 两个渠道签名不同：从开发版换正式版（或反之）必须先卸载重装（会清除本地离线内容与配对，需重新配对）。
- **正式签名保管**：release 签名材料在 `runtime/android-signing/`（release.jks、keystore.properties，权限 600、随代码仓库提交），建议同时离线备份；密钥全部丢失后已安装用户将再也无法收到升级包，只能卸载重装。不要用 debug 或他人签名发布正式版。
- `runtime/releases/` 在 `runtime/` 下，不进入 Git；备份策略与资源库相同。
- 官网端口 8767 只读公开 APK，不含设备凭据、资源库或对话数据；不要把它暴露到公网。
- `archive` 拒绝降级发布（versionCode 小于已发布版本）。
