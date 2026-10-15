# 0.6.8 / versionCode 21 — 验证（2026-10-15）

GitHub Actions [38661745689](https://github.com/xzq11112/rike-android/actions/runs/38661745689) 通过。APK 应用源码提交为 `9ba6d7edb1254f692b58d6bfeedb31ed83626ed5`，受测合并检出为 `80e1454908244c287d26891d6c075de86b790406`；之后只固定签名输入摘要并补验证说明，未修改应用及测试代码。

- Debug / Release：各 153 项测试，0 失败、0 错误、0 跳过。
- Release lint：0 错误、13 警告；Debug / Release APK 构建成功，正式包联网权限及非调试标记检查通过。
- Windows PowerShell 5.1：23 项合成签名检查通过，无生产密钥参与。
- 新增练习倍率、有效时长小数累计、备份删除恢复及长按保存打卡流程回归；启动提醒覆盖次数、跳过、手动重试、浏览器返回、锁定过期回调、编辑中延迟及小屏大字体日夜布局。
- 正式未签名 APK SHA-256：`943bbc7ab9d505a523ad36b6405587319e96a28355afd7dcbbf7f308c9596fdc`。本机签名脚本已固定该输入。

实际手机操作、浏览器交互和同签名覆盖安装仍需对应设备核对。生产签名与发布状态以最终 APK 核验回执和 GitHub Release 为准。

# 0.6.5 / versionCode 18 — 本轮验证（2026-10-07）

本轮 GitHub Actions [37592157236](https://github.com/xzq11112/rike-android/actions/runs/37592157236) 已通过。构建源码提交为 `485a98a56d462e30a62109c35afc3d6a2c8470b5`；之后只补充文档与签名输入哈希，应用及测试源码未变。

- Debug / Release：各 119 项测试，0 失败、0 错误、0 跳过（保留原 101 项，新增空备注 2 项、本机封装 9 项、可选密码流程 7 项）。
- Release lint：0 错误、13 警告；Debug 与 Release APK 构建成功，最终正式包检查确认无 INTERNET 权限、无 debuggable 标记。
- Windows PowerShell 5.1：本轮新 APK 上的 23 项合成签名检查通过，无生产签名私钥参与。
- Release 输入 SHA-256：`f311e3b770412803347f189abe6a743d5fe6c0161ac26436fb9d7a0d45b8135f`；签名更新脚本已锁定该值。
- 测试覆盖缺失/JSON null/实际文本备注、首写及封装提交失败、无密码持久化与重启、旧密码资料库保护、指纹与导出守卫、取消密码设置、密码及恢复密钥确认和迁移失败重试，记录与日记草稿保留。

本机密钥和指纹的实际系统行为、正式同签名覆盖安装仍需对应 Android 设备核对。

# 0.6.4 / versionCode 17 — 本轮验证（2026-10-06）

本地 JDK 17 / Gradle 8.11.1 / Android SDK 35 运行 testDebugUnitTest、testReleaseUnitTest、lintRelease、assembleRelease，均通过。

- Debug / Release：各 101 / 101 项测试，0 失败、0 错误、0 跳过。
- Release lint：0 错误、13 警告。
- Release 正式包 app.rike.offline，版本 0.6.4 / 17，最低 API 26；aapt 确认无 INTERNET、无 debuggable，zipalign 4 字节检查通过。
- Release 输入 SHA-256：`4802137325bebf5144f65b5097186de665096fcb54520bce6468ad5e17e47f29`。
- 新增回归：日期分组/次数/小计/完整备注/历史类型、最近七天边界和旧日期查询、日记原地展开/同页编辑/同日更新/直接删除、独立年/月选择及闰年与上下界。已有加密、草稿、失败写入、恢复及生命周期检查继续保留。
- 今日布局使用 Robolectric Native Graphics 实际字体渲染检查，360×744dp、默认字号、四个短名称类型与六个时长选项下，滚动量不超过 48dp，所有可见按钮至少 48dp；这是模拟测量，不代表所有手机、字体或选项数量都无需滚动。
- 浏览热力图和旧日期不改写已保存记录；近七天只限制默认显示，不删除旧资料。备份与导出只在设置。

本轮未做 Windows PowerShell、Android 真机或覆盖安装实测。Windows 合成签名回归现为 22 项，本轮未执行；历史 0.6.1 的 21 项检查通过不能替代当前版本的验收。脚本只更新版本、文件名及输入哈希，自动复用原本本机密钥和 alias，保护密码由官方工具读取、不额外保存；没有生成或取得生产签名密钥。

本次公开准备仅整理许可证、说明和仓库布局，未重新运行应用测试。上述结果来自对应的 0.6.4 构建；应用源码和测试源码与该构建一致。
