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
# 0.6.5 / versionCode 18 — 本轮验证（2026-10-07）

新增空备注、本机密钥封装及可选密码流程回归。完整 Debug / Release 测试、Release lint、APK 构建与 Windows 合成签名检查待本轮 CI 执行后记录；历史版本的通过结果不作为本轮通过证据。

本机密钥和指纹的实际系统行为、正式同签名覆盖安装仍需对应 Android 设备核对。
