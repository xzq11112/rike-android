# 0.6.7 / versionCode 20 — 手动检查更新的联网边界（2026-10-08）

此节取代下方历史版本中“无 INTERNET 权限”的描述。v0.6.7 增加 INTERNET，仅用户在设置 → 维护 → 版本与更新点击“检查更新”时，以匿名 HTTPS GET 读取固定 GitHub 官方仓库的 latest release 元数据。GitHub 会接收普通网络连接信息（如 IP）；请求不包含资料、密码、诊断、设备标识或已安装版本。无自动检查、后台服务、云端同步或遥测。

UpdateChecker 不持有 Context、资料库或会话。连接超时 8 秒、读取超时 10 秒，响应上限 512 KiB，不跟随重定向；草稿、预发布及无法识别的版本拒绝比较。APK 链接仅接受官方仓库中与版本对应的已上传正式文件，其他链接不打开；缺少 APK 时仍可由用户打开固定最新发布页。下载通过浏览器完成，不自行下载、请求安装权限或执行文件。

检查使用独立线程，不阻塞资料写入。锁屏、切后台或销毁会取消请求并丢弃过期回调；公开检查结果只保存在当前解锁会话内存。版本判断依据发布标签，实际安装仍由 Android 校验包名、版本与签名。加密格式、密钥、备份、FLAG_SECURE 和后台立即清屏不变。

# 0.6.5 / versionCode 18 — 可选密码的本机边界（2026-10-07）

新建资料库可暂不设置密码。DeviceVault 使用不要求用户认证的 Android Keystore AES-GCM 密钥封装资料密钥，文件保存在 noBackupFilesDir；不存储空密码或明文资料。此模式允许使用手机的人直接进入应用，本机密钥不可随备份转移，卸载或清除数据会失去它。

设备封装将 RIKEV001 头部的 SHA-256 同时作为绑定标识与 GCM AAD，普通保存保持绑定。设置密码时生成新的资料密钥、密码封装与恢复密钥，正式版先离线保存、再隐藏原码完整核对，随后通过原串行 VaultWriter 原子重加密所有资料。取消及提交失败保留原本机密文和封装；成功后删除本机封装，即使清理失败，旧封装也不能匹配新头部。首次无密码建库先提交设备封装，再提交资料密文，避免生成无法再次打开的资料库。

无密码模式在界面和实际操作入口均禁止指纹及加密备份导出。完成密码与恢复密钥设置后才开放；已有密码资料库不会自动加入本机免认证封装。既有 Argon2id 参数、恢复机制、RIKEV001 格式、离线权限及后台清屏继续保留。

# 0.6.1 / versionCode 14 — 第二轮审查修复

依据 `rike-0.6.0-second-audit-sol-handoff.md` 修改。统一进程内资料库所有权与后台队列，恢复提交期间禁止取消；今日日期按提交时本地日期计算，旧草稿先确认。新增草稿找回/放弃、练习编辑、明确的草稿重试和 80% / 90% 容量提示。容量异常在写入前中止，原密文保留，不自动清理历史或隐藏资料。

指纹重新设置使用候选密钥及原子封装，取消/失败保留旧配置；读取旧 60-byte 封装。正式恢复密钥改为先离线保存、再隐藏原码全文确认，未降低确认强度。Windows 导入候选密钥通过签署和身份校验后才设为默认，支持修正/补充 alias，回执准备完成后才交付 APK。

Argon2id / AES-GCM、RIKEV001、旧备份、未知字段、旧梦境和可恢复删除保持兼容；不加入联网权限、剪贴板放开或后台宽限。本轮本地 Debug / Release 各 94 项回归通过（原 76 项＋新增 18 项），Release lint 0 错误 / 10 警告，两种 APK 构建成功；证据与哈希见 VALIDATION.md。Windows PowerShell 5.1 的 21 项合成签名检查已在本轮 CI 通过；硬件指纹、手机输入法及正式同签名覆盖仍待对应设备验收。

# 0.6.0 / versionCode 13 — 交互改进的隐私边界（2026-10-05）

未修改 VaultCrypto、VaultStore、VaultWriter 或 BiometricVault 的加密、硬件密钥与原子写入机制；Argon2id / AES-GCM、RIKEV001、offlineVersion=1、schema 3 继续兼容。Records 仅补充顺序提交和数量摘要，不清空、迁移或丢弃未知字段、旧梦境与已有删除归档。

明确区分主页面返回与子页面返回。主页面返回和手动锁定均清屏并关闭 UI 会话；子页面只返回上层，草稿仍通过原串行 writer 加密。API 33+ 用系统 OnBackInvokedDispatcher 调用相同分层返回逻辑；键盘与厂商系统返回的实际顺序仍须设备验证。切后台立即锁定，没有增加解锁宽限期。手动点锁会抑制本次自动生物识别，用户主动开始或下次重新进入才再发起系统认证。

锁定额外清除旧 TextView 的可见文本、保存提示、控件等待引用和当前导出 URI。generation 继续拒绝旧认证/写入回调；已接受写入仍由独立 worker 会话完成并关闭。清除视图文本不等于可证明擦除 Java 堆、输入法或已经攻破的系统内存。

成功回执含日期/分钟时只出现在 FLAG_SECURE 保护的应用内，不发往 Toast。诊断接口仍只能接收固定枚举，最多 32 项、保留 30 分钟、仅内存；新增 ORDER_COMMIT_FAILED、BACKUP_VERIFY_FAILED、RESTORE_WRITE_FAILED，不包含异常文本、私人内容、密码、文件路径、精确时间或设备标识。详细开发过程与验证写在仓库文档，不增加个人行为日志。

备份仅输出密文。导出 URI 只留在已解锁内存，直接验证仍须输入备份口令且不修改资料库。恢复预览仅在安全对话框内显示数量，空设备在明确确认前不创建资料；取消关闭候选会话。恢复到已有资料库仍是替换，需要用户核对双方数量，失败保留原密文。

正文选择柄与“全选”在应用内可用；不新增跨应用复制/粘贴/分享菜单。Android 输入法与系统本身仍不由本应用控制。Release 恢复密钥完整确认、可恢复删除、旧备份口令和后台立即锁定保持原规则，不因 UX 改进削弱保护。

体验包改为 `app.rike.offline.preview.ux`，旧测试包不卸载不迁移，仍是可调试临时签名验收包。正式版 `app.rike.offline` 不变，长期私钥继续由发布者本机离线保管，不进入 GitHub、CI、Library 或 AI。CI 额外保留的 apksigner.jar 是公开 SDK 验签工具，不含签名私钥；本轮没有使用它生成长期密钥。

# 0.5.1 / versionCode 12 — 自动认证与统计（2026-10-04）

主密码输入停顿 550 ms 后在原串行后台执行器尝试解密，不缓存密码、密码长度或独立验证摘要，不降低 Argon2id 参数。输入保持可编辑，单个验证在途；每次输入和认证模式变更提高修订号，锁定与后台切换提高 generation。过期的正确结果也会被丢弃并关闭会话，明文字节与密码字符数组清除。中间输入验证失败不清空密码、不写资料、不弹错误提示；存储或解析异常使用既有固定诊断码，未加入任意消息或私人数据日志。

已启用硬件保护认证密钥时，锁屏页在前台自动调用系统 BiometricPrompt；不在后台监听指纹。取消/失败后不自动重弹，可改用主密码或主动重试；系统密码不作为数据密钥的替代。Android 29+ 的免确认只是系统提示，系统仍决定支持方式。指纹认证与主密码验证互斥，生命周期校验、CryptoObject、强生物识别及硬件密钥要求保留。真机硬件成功验收仍需设备，模拟环境只验证自动入口和失败回退。

统计选择与黄色映射仅用于显示，不改变原始分钟或备份；按固定分钟锚点插值，不从个人数据计算最大值。年月独立选择无数据写入。设置仅为解锁方式与备份；版本帮助、删除归档及本地诊断位于备份页，既有诊断隐私要求不变。

建库说明密文备份需要口令、无服务器找回、指纹不能代替换机备份密码；明确 Debug 无恢复密钥入口、Release 需提前保存恢复密钥，以及旧备份仍使用其旧口令。旧密码（包括 Unicode）、RIKEV001、offlineVersion=1、schema 3、隐藏梦境兼容及无 INTERNET 权限均保留。正式签名私钥仍由用户离线保管。

# 0.5.0 / versionCode 11 — 四个版块与今日选项管理（2026-10-04）

主要导航依次为今日、记录、感悟、统计。热力图从今日移至独立统计页，保留当月、全年、展开各月与其他年份；今日已记移除，记录页承担查询。日夜切换及锁定置于右上角，使用原生太阳/月亮、锁的线条图标，更多菜单保留加密备份与隐私设置。界面沿用暖白、墨色、绿色和留白；没有图片服务、字体下载或联网资源。

今日长按类型或时长进入各自的编辑模式，项目右上角出现 ×，点项目可修改，完成编辑可退出。类型可上下移动，顺序写入资料及导出；时长无移动按钮，增加/修改后始终按分钟数升序。普通选择模式同分钟选项仍合并显示，编辑模式展示各原始选项，避免旧导入重复项无法管理。删除、改名和改时长不改历史打卡里的名称与分钟数；删除选中项会清掉过期草稿引用，重复时长剩余项仍可保持选择。设置页不再提供类型/时长管理。

记录和感悟默认展示本机日期含今天在内的最近七个自然日，保留每页 25 条。点击选择日期打开带练习/感悟标记的月份日历，分别按对应资料着色；可翻月或输入年月跳转并选择任意日，也能回到最近七天。日期索引、筛选与排序在后台建立，不每次扫描全库。按首次打卡/保存时间倒序，展示本机时区 YYYY-MM-DD HH:mm；感悟默认先显示列表，通过写今日感悟/编辑进入正文。仍保持一天一篇感悟及未保存草稿，保存后返回列表。

新增打卡和感悟在后台提交时记录 createdAt；模型强制保留感悟原有 createdAt，修改只更新 updatedAt，不改变首次保存顺序。旧备份缺失或无效时间显示“时间未记录”，不以导入或修改时间伪造首次保存。支持既有 ISO、带时区、旧本地日期时间和数字毫秒格式。内部 journal/journals 键继续不变，以保留旧 JSON、隐藏梦境、草稿、删除留痕与操作历史的兼容性。

新增选项管理、时间排序/七天边界与着色日历界面测试，调整原有流程测试以遵循新版导航；测试仅用虚构资料。后台加密保存、失败保留旧密文、无联网权限和密码自主长度继续保留。真机视觉、中文输入法、帧率和稳定签名覆盖安装仍待验收，本轮 CI 与安装包哈希另记 VALIDATION.md。

# 0.4.1 / versionCode 10 — 主密码长度自主选择（2026-10-04）

按用户要求，建立资料库与更换主密码均取消最短位数和字符组合限制，任何非空主密码都可使用，包括短数字、短英文与中文；保留原有 1024 个 Java 字符的异常输入上限，不截断或自动修改密码。界面不再提示“6 位数字或至少 12 字符”，键盘切换按钮改称“使用文字键盘”，继续默认数字键盘。新建及换密码处保留短密码容易被猜解的简短建议，不阻止保存。

已有六位数字和旧长密码、资料库与离线恢复密钥继续兼容。取消长度校验不会自动更改已有密码，必须由用户主动更换；旧备份仍用旧密码。Argon2id、AES-GCM、RIKEV001、离线数据与无联网权限保持不变。短主密码保护能力较低，不因算法不变而具有与随机长口令相同的强度。中文输入法真机表现尚未验证。

调整既有密码策略断言，补充短英文、中文含空格及单汉字换密码的真实加密/解密、资料保留和恢复密钥往返验证；只使用虚构资料。本轮 CI 结果另记于 VALIDATION.md，不套用 0.4.0 的结果。

# 0.4.0 / versionCode 9 — 全局响应修复（2026-10-04）

0.3.1 只处理选项保存与主题切换，不足以解决全局卡顿。新增单个串行 VaultWriter：UI 仅提交小草稿对象，停输 800 ms 合并保存，连续输入最长 2.5 s 发起一次；全库复制、加密、原子写入在同一后台执行器完成。打卡、日记保存、选项修改、删除/恢复、导入替换和换密码同样后台提交，只有落盘成功才发布 committed snapshot；失败保留旧密文和内存、恢复待存草稿并可继续编辑重试。导航不会丢失尚在队列中的草稿。

锁定先清掉 UI 与 UI 会话；后台专用密钥副本仅用于完成已接受的写入及最终草稿 flush，队列结束即关闭并清除 worker 模型。解锁/导出排在该队列之后，旧回调不能打开已锁定界面。普通后台切换会安排补存；系统强制终止或存储故障仍可能让未完成草稿留在上次成功写入状态，不能保证强杀时待存内容无损。

每个热力图由一个自绘 View 表示，保留逐日点击、键盘与系统虚拟无障碍节点；不是成百个 TextView。主题仍只重绘颜色。日期统计/记录排序在正常解锁和提交时后台建立索引，草稿保存复用索引；跨日后台重建。记录/日记/已删除区每页 25 条，正文预览 600 字符，可打开完整内容，所有原始数据均保留。选项列表仍全部显示，超多自定义选项和单篇超长正文在部分手机仍可能较重。

文件解析/导入导出、诊断文件输出、生物识别 Cipher 准备及解密放到后台。persist 增加主线程禁止保护。增加固定 SAVE_SLOW / PAGE_BUILD_SLOW 码（>=250 ms，仅粗略标记，不记录实际耗时/日期/内容/名称/路径/密码），只在内存保留，不自动发送。没有降弱 Argon2id/AES-GCM，没有联网权限，没有修改 RIKEV001 / offlineVersion=1。

新增 GlobalResponseTest 与 VaultWriterTest，覆盖 3,000 条虚构记录的控件上限、输入合并、后台补存/清屏、禁止 UI vault 写入、虚拟无障碍日期点击、失败回滚和有序提交、替换及重包密钥兼容。既有流程测试改为等待真实后台提交，不删去原断言。本地没有 Android SDK，CI 结果与真机性能需分别记录。临时 Debug 签名仍可能无法覆盖旧测试 APK，绝不可卸载含资料的旧版。

# Privacy and security design — preview v0.1.0

## 0.3.0 / versionCode 7 — 2026-10-03

安卓原生版对齐当前网页的核心功能，优先可用性，视觉精修延后。此节覆盖下方旧版中“至少 16 字符”“仍提供记梦”“网站仍存云端数据”的说明：当前网页已独立停用云端业务存储；本 APP 从始至终无 INTERNET 权限。

- 主密码支持六位 ASCII 数字（保留前导零）或至少 12 字符长密码；默认数字密码键盘，可主动切换字母键盘。旧长密码、旧 .rike 加密资料库和恢复密钥继续可读；不改 Argon2id、AES-GCM、RIKEV001/offlineVersion=1，也不清空数据库。数字键盘只改变输入提示，不过滤或截断密码；六位数字较易被离线猜解。
- 今日页顺序为打卡、今日已记、热力图。默认展示本机当月和今年全年，全年连续 19 列逐日方格，不按月份分组；支持其他年份与展开十二个月。只派生实际分钟数，同网页阈值 1/15/30/60/120，无加权；点日格查看日期和实际时长。
- 移除记梦导航、编辑页面，隐藏梦境删除记录和操作历史。v1/v2/v3 旧 JSON、已有梦境、梦境草稿与历史继续读取并保留在加密全量备份中；其他记录、练习项目和常用时长照常恢复，不新增存储迁移。
- 新增密码前导零/换密/旧密钥恢复、闰年与逐日顺序/不加权、真实 UI 键盘切换/年份与展开/旧梦隐藏但保留测试。保留原有加密、草稿、写入失败、生命周期锁定、备份和诊断测试。仅使用虚构数据。
- 包名仍为正式 app.rike.offline、测试 app.rike.offline.preview。versionCode 由 6 增为 7；正式更新必须同包名、同长期签名、覆盖安装。新增本机签名脚本 sign-local.sh；不上传或生成正式私钥。CI 测试包仍为临时开发签名，不能承诺覆盖此前测试包。不要通过卸载含资料的旧应用解决签名冲突。
- APP 没有联网权限，网页改动不会自动影响 APP，也不自动检查更新。GitHub 构建产生未签名 Release，发布者用自己本机的固定密钥签名后分发，使用者只需覆盖安装；无需自己编译或签名。签名、真机覆盖安装、硬件指纹与视觉验收仍需真实设备完成，不能由 CI 代替。
- 从网页迁移请在网页“兼容导出”取得完整明文 JSON，使用 APP 内“导入旧网页 JSON”本机导入空资料库，然后导出并验证 .rike 加密备份。网页的加密 JSON 备份与原生 .rike 不是同一格式，不能直接互导。真实文件和密码不交给 AI。


## 0.2.4 保存失败保护补充

草稿读取和写入候选状态均使用深拷贝，成功写入密文后才替换已提交内存状态。测试使用 VaultStore 子类模拟写入 IOException，确认旧内存/密文不变、可重试，不向诊断输出异常携带的路径。未改变原子文件机制或加密格式；这不保证存储介质损坏时可恢复，也不保证从未成功保存的输入在退出后保留。今日页新增删除入口仍使用确认、加密墓碑和操作留痕，不改变私人数据保留规则。

## 0.2.3 诊断与兼容补充（优先于历史概述）

Diagnostics 仅接收编译时固定枚举，不接收字符串、异常、URI 或业务对象。Activity 内存最多保留最近 30 分钟的 32 条错误码；读取/追加时淘汰过期项，Activity 销毁时清空。没有诊断磁盘文件、网络传输、logcat、精确时间线或设备标识。内存并不承诺可证明擦除，系统或进程已被攻破不在保护能力内。

报告附加的元数据仅为应用版本、versionCode 和 debug/release 类型。用户解锁后可查看、清空，预览实际文本后再次确认导出；系统文件选择返回强制锁定，重新解锁才写入报告。导出是可读文本，外部文件提供者可能上传它，用户需选择可信本地位置。为导出保留的已确认报告快照仅在内存，取消、完成或页面销毁即释放；导出文件不受内存 TTL 控制。不会附带资料库或操作历史。

格式仍为 RIKEV001 / offlineVersion=1 / schemaVersion=3。数据版本必须是支持的整数；未知、分数、字符串或溢出版本拒绝读取，不写回文件。旧格式兼容测试不等于实际 APK 更新测试，正式签名与真机覆盖安装仍须独立验收。

This is a native Android application with encrypted local storage. It has not undergone an independent security audit. Automated tests do not establish absolute security.

## Threat model

The design has no cloud database, remote analytics, remote fonts, WebView or online AI requests. Version 0.6.7 requests USE_BIOMETRIC and INTERNET; the only HTTP client is the explicitly invoked, anonymous GitHub release checker described above. Private content is encrypted at rest, and backups contain ciphertext. Version 0.6.6 has no INTERNET permission.

It does not protect against a compromised/rooted OS, a malicious keyboard/accessibility service, coercion, an unlocked phone observed by another person, external camera capture, malicious software updates, or a weak/known master password. An OS file provider selected by the user can run in another process; EXTRA_LOCAL_ONLY is requested but is not a guarantee against a malicious provider. Export only to trusted offline storage. Do not enable cloud keyboard learning, cloud clipboard, phone migration utilities, or vendor backup for private data.

FLAG_SECURE is applied to both the Activity and all Dialog windows before they are displayed; the Activity is excluded from recents. These controls depend on Android and device behavior. allowBackup=false, cloud/device-transfer exclusion rules, and noBackupFilesDir are all used. Verify actual behavior on the owner's phone.

## Binary format RIKEV001

All integers described below are unsigned in intent and encoded big-endian using Java ByteBuffer for payload length. Only nonnegative lengths up to 16 MiB are accepted.

| Offset | Bytes | Meaning |
| --- | ---: | --- |
| 0 | 8 | ASCII RIKEV001 |
| 8 | 16 | Random Argon2 salt |
| 24 | 60 | Master-password-wrapped data key |
| 84 | 60 | Recovery-key-wrapped data key |
| 144 | remainder | Authenticated encrypted padded UTF-8 JSON |

A seal consists of a fresh random 12-byte nonce followed by AES-256-GCM ciphertext and its 16-byte authentication tag. SecureRandom generates data keys, recovery keys, salts and nonces.

- Data encryption key: random 32 bytes.
- Password key: Argon2id v1.3, memory=65536 KiB, iterations=3, parallelism=1, output=32 bytes. Fixed v1 settings prevent attacker-selected KDF workloads. Password length is bounded at 1024 Java characters; new passwords must be at least 16, which alone does not guarantee entropy.
- Password wrap AAD: ASCII password concatenated with salt.
- Recovery wrap key: independently random 32 bytes, shown as 64 hex characters only during local setup.
- Recovery wrap AAD: ASCII recovery.
- Payload AAD: complete 144-byte header, authenticating magic, salt and both wrapped keys.
- Before sealing payload: 4-byte actual JSON length, JSON, random padding to a 4096-byte boundary.
- File maximum: 16 MiB + 8192 bytes; JSON maximum: 16 MiB. Metadata including file size, timestamps and existence is not hidden completely.

Bouncy Castle implements Argon2id; the Android/JCA provider implements AES-GCM. No custom block cipher or hash implementation. The versioned envelope and key lifecycle still need independent review. Future cryptographic changes must introduce a new format version and preserve a tested read path; never silently reinterpret RIKEV001.

Password changes rewrap the existing data key and retain the existing recovery wrap. They DO NOT revoke a stolen data key or recovery code, and old backups remain decryptable with their old password. For a compromised recovery/data key, create a new empty vault with fresh keys and explicitly restore data into it (the in-app restore re-encrypts under the destination keys). Restoring from the initial lock screen instead retains the backup's original keys.

## Plaintext model and lifecycle

The payload retains v3 web-export arrays:
practiceTypes, durationPresets, checkIns, journals, dreams, deletedRecords, auditLog.
It adds offlineVersion=1, deviceId, revision, and drafts. Check-ins contain practiceTypeName snapshots. Journal dates are unique; dreams have independent IDs even on the same date. Tombstones and audit snapshots are private data.

No SQL tables exist in the native app. Each edit clones the JSON model, then encrypts and atomically replaces the on-disk vault; the in-memory committed model changes only after successful persistence. AtomicFile temporary/backup files contain ciphertext only. Android SharedPreferences stores only the light/dark theme. Form saved-state/autofill/personalized-learning requests are disabled.

onPause clears the session and private UI, dismisses secure dialogs, clears mutable key arrays and invalidates outstanding crypto callbacks with a generation counter. KDF operations run off the UI thread; small writes are synchronous to preserve drafts before backgrounding. Framework/system IME and Java Strings can have copies that cannot be reliably zeroed by application code. Do not claim guaranteed RAM erasure.

There is no external deletion propagation, cloud recovery, password reset or short PIN. Optional biometric unlocking uses a per-operation authenticated hardware Keystore key (see v0.2.0 below). A person possessing either the strong password or recovery code and a vault copy can decrypt it. Debug v0.2.1+ does not show or accept recovery codes; it is for synthetic data only. Release retains recovery codes. Losing all usable local credentials means permanent loss.

File-picker handoff retains only the URI and operation in process memory. Unlock first, then continue. If Android kills the process, the user may have to reselect the file. Exports always use encrypted bytes; legacy plaintext import is available only into an empty vault, validates a separate copy, previews counts and atomically writes after confirmation. Restore also requires successful authentication/validation and explicit confirmation before replacement. Verification never changes the current vault.

Deletion creates a tombstone and audit event; restore rejects conflicting IDs/journal dates. This is owner-visible history, not a tamper-proof append-only service. Full backup replacement can restore an older history. There is no trusted external monotonic counter to prevent rollback by an attacker already able to replace local ciphertext.

## Release boundary

Debug APK uses a development key and is debuggable: synthetic testing only. Production release APK is unsigned until the owner signs it locally. Do not upload production signing keys, master passwords, recovery keys, databases or real JSON to GitHub, CI, Library or AI conversations.

Before private deployment: review source/dependencies, verify the final merged manifest and signing certificate, run tests, and perform real-device lifecycle/backup checks. Future updates are security-sensitive even without INTERNET: a malicious update could expose data through UI or external Intents. Keep the signing key offline and review changes.

The current implementation is deliberately small and native but uses a hand-written UI and full JSON snapshots. Very large vaults may pause during writes or exhaust mobile memory; enforce the bounds and back up before limits are approached. No plaintext search index or crash-reporting service is used.

## Official references

- Android backup behavior and noBackupFilesDir: https://developer.android.com/identity/data/autobackup
- Secure window limitations: https://developer.android.com/security/fraud-prevention/activities
- Owner-controlled app signing: https://developer.android.com/studio/publish/app-signing
- Argon2BytesGenerator API: https://downloads.bouncycastle.org/java/docs/bcprov-jdk18on-javadoc/org/bouncycastle/crypto/generators/Argon2BytesGenerator.html


## 0.2.0 预览：宋式界面与本机生物识别
暖纸、青瓷、疏朗排版，保留夜间模式。Android 9+ 使用系统 BiometricPrompt + CryptoObject；仅硬件保护的 Android Keystore AES-GCM 每次认证密钥允许封装资料库数据密钥。Android 8 使用主密码。增加 USE_BIOMETRIC 权限，仍无 INTERNET 权限。所有系统登记的强生物识别可能被接受，不保证限定某一根手指。
先用主密码解锁，在设置启用。后台取消认证并关闭会话，旧回调不能解锁；新增生物识别使密钥失效。更换主密码同时停用生物识别，需要重新启用。封装文件在 noBackupFilesDir，不进入导出备份；备份格式仍 RIKEV001，原密码及恢复码继续有效。不保存主密码。
新增密码学测试覆盖正确/错误数据密钥与锁定会话。硬件验证、指纹变更失效和视觉实机验收仍需手机完成。该版本仍为测试版，未经过独立安全审查。不同构建环境的临时 debug 签名可能不兼容，不能要求用户直接卸载含数据的旧包；先做加密备份并验证恢复。
