# 家庭机器人协议 v1

HTTPS JSON API，统一 `/v1`。完整字段由服务端 Pydantic 与 `/openapi.json` 提供。手机只信任经线下连接材料固定的 SHA256 证书，绝不关闭所有证书验证。角色使用独立 Bearer 令牌。错误以 HTTP 401/403/409/413/422/503 区分身份、权限、版本冲突、超限、格式与能力缺失。

## 初始化与绑定

管理员本地生成两分钟连接材料 → `/register` 机器人登记 → 机器人 `/pairing` → 家长 `/pairing/claim` → 机器人 `/pairing/{id}/decision` 本地确认 → 家长携带自行随机生成的长期令牌 `/complete`。服务只存令牌哈希；pending 不具备权限。重放注册/配对码拒绝。

## 配置与实际状态

`/heartbeat`（机器人每3秒）与 `/devices` 区分服务可达/机器人在线。15秒无心跳离线。家长提交 `/robots/{id}/config` 的 requestId、expectedVersion、完整 config。10秒有效命令，机器人 `/commands` 拉取、原子保存后 `/ack`。只有 ACK 显示 applied。过期命令不能重连重放；冲突刷新后生成新请求。

## 内容

`/resources` 创建和列表；草稿 GET/PUT 包含 expectedVersion；素材 `/assets` 上传；`/jobs` 进度/重试；`/publish` 事务发布并去重。`/books/lookup` 只返回已发布候选；`/manifest` 绑定不可变 revisionId。进度携带 revisionId、segmentId、offsetMs、递增 seq；实际音频播出后回报，不按已发送音频推断。

进度上传不属于播放完成的前置条件。Android 在身份隔离的加密存储中合并每项最新待同步位置，单一发送者逐项上传，单次网络调用最多5秒；传输失败保留待重试，收到确认后仅移除相同 seq，防止旧确认覆盖新位置。队列最多128个资源，超出保留最近项目并记录 `progress-overflow` 诊断；资源文件及当前本地续播位置不受影响。删除/撤回/版本不合法等永久拒绝移除对应旧回报并记录 `progress-rejected`，不能绕过发布版本检查。

`/catalog/revocations` 优先同步；`/unlist` 立即阻止读取，离线显示 pending_sync。原稿素材仅家长访问。机器人不接受任意网络地址、模型生成文件路径或管理操作。

## 敏感数据

会话音频/图像只在内存用于请求，不进入普通日志和备份。资源上传为家长主动导入的持久素材，与环境采集严格区分。离线下载内容只能依据最后授权；不能承诺离线即时撤回。导出不包含手机令牌/私钥；恢复素材为待审核草稿。


资源撤回同步：`GET /v1/catalog/revocations` 每项含 `id`、`created`、`activeRevision` 和 `revokedRevisionIds`。`activeRevision` 为空表示资源整体不可用；非空时，只撤回明确列出的版本，不能将“不是最新版”视为已撤销。历史下架墓碑需要保留，让离线设备补同步；普通重新发布允许当前未撤销的旧快照读完。旧服务缺少精确列表时，客户端保守沿用旧撤回语义。服务和App应配套升级，新服务无法修正旧客户端自行比较最新版的行为。

## 图书页级维护与阅读范围

素材上传新增可选 `targetPageId`，用于以单张新图片替换指定页；`POST /resources/{rid}/pages/{pageId}/extract` 带 `expectedVersion` 和 `rotation`（0/90/180/270），重识别保存的单页原稿。成功只更新目标页、取消其校对确认；草稿版本变化返回冲突或将迟到作业标为stale。失败保留原正文，任务可重试同一张新照片。`GET /jobs/{jobId}` 用于单项轮询。

页面质量字段为 `sourceRotation`、`extractionError`、可空的 `confidence`、`qualityWarnings`（BLANK/DUPLICATE/LOW_CONFIDENCE/OCR_FAILED）。不自动删重复页、补缺页或发布。PDF子进程逐页输出，整批超时仍可保留已完成页与剩余页的失败位置。数据库schema2新增`jobs.options`保存单页来源和旋转参数；升级时须停旧服务。

`/books/lookup` 的可读候选仍仅来自发布快照；新增 `NOT_READABLE` 与原因 `UNLISTED/UNPUBLISHED/NO_TEXT/NOT_READY`，不返回草稿正文和草稿候选ID。ISBN、明确版次优先于泛书名，包括不可读版本，防止误选另一版。`/books/recognize` 对无文字/损坏图片返回 `RETAKE`，OCR服务故障返回 `UNAVAILABLE/OCR_UNAVAILABLE`；`candidateCount` 是完整候选数量，`candidates` 最多5条，客户端口头说明最多两条。

节选 `manifest.scopeNotice` 为独立的 `{id,text}`，完整资源为null；该音频通过 `/resources/{rid}/audio/scope-notice?revisionId=...` 获取。说明不在正文segments中，不接受为阅读进度。离线完整性覆盖正文/原录音及说明的全部哈希；缺说明的旧节选缓存需更新。候选ID/版本只在当前会话短时保留，播放前再验证，不能把旧位置套给新版本。

## 脱敏诊断与记忆撤回

`GET /v1/diagnostics` 按绑定范围提供白名单版本/状态/失败计数/模型可用性及生成时间；心跳 `self_check` 表示本机明确启动的诊断，不等于儿童会话。模型可用性与物理自检结果分别呈现，报告不含凭据、自由文本状态、地址或内容。

`GET /v1/memory-actions` 仅家长可读，返回最近20条无正文撤回事件。`/turns` 的相关项遗忘与 `/memories/forget` 的明确全量删除分开；无法确定相关项时置 `suspended` 并清空上下文，家长审核后才恢复。旧备份恢复不自动批准，删除墓碑仍优先。
