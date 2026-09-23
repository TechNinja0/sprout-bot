## ADDED Requirements

### Requirement: 有界诊断心跳
机器人与家庭服务 SHALL 同步维护有限故障计数契约，进度队列溢出、上报被拒绝或响应异常均可上报；不携带原文、书名、录音或任意诊断字段。

#### Scenario: 进度异常后仍可确认配置
- **WHEN** 机器人心跳包含 `progress-overflow`、`progress-rejected` 或 `progress-response` 的有界计数
- **THEN** 服务接受心跳并保留这些字段，机器人继续配置获取与ACK，未知字段或越界数值仍被拒绝

### Requirement: 服务身份与登记
服务 SHALL 使用 TLS 和显式服务身份校验，管理员在本地初始化短时单次注册材料；无公开默认密码，凭据不被通用备份复制。

#### Scenario: 服务身份与登记的边界验证
- **WHEN** 注册材料过期、重复使用或证书不匹配
- **THEN** 拒绝登记，不能取得机器人或家长权限

### Requirement: 绑定与授权
家长绑定 SHALL 由机器人本地确认，机器人和家长持独立可撤销令牌；所有管理、素材、播放接口校验角色。

#### Scenario: 绑定与授权的边界验证
- **WHEN** 未配对家长访问机器人或撤销后的旧令牌访问
- **THEN** 拒绝访问，不能因同Wi-Fi或同昵称授权

### Requirement: 配置确认与冲突
配置 SHALL 携带 requestId、expectedConfigVersion，只有真实应用ACK才显示生效；离线/超时命令不重放。

#### Scenario: 配置确认与冲突的边界验证
- **WHEN** 并发更新或机器人失联
- **THEN** 返回冲突/离线，重连保持旧有效配置直到新请求

### Requirement: 角色与恢复
同一APK SHALL 保存身份和设置步骤；PIN保护本地管理及角色切换，恢复需绑定凭据或管理员恢复材料与本地确认。

#### Scenario: 角色与恢复的边界验证
- **WHEN** 冷启动、角色切换、撤销、忘记PIN
- **THEN** 恢复正确入口，切换前停止采集并撤销原安装凭据
