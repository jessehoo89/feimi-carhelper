# 飞谧车机助手（FeiMi CarHelper）

> ⚠️ 本项目为**独立第三方开源工具**，与浙江吉利控股集团、领克汽车、极氪汽车、亿咖通科技（ECARX）、魅族科技均无任何关联，亦未获其授权或认可。仅供**自有车辆**的互操作与学习研究使用。

飞谧车机助手是一个面向 **吉利 / 领克（亿咖通 ECARX 平台）Android 车机** 的开源小工具，由手机端与车机端两个 App 组成：手机连上车机热点后，通过 ADB 把 APK 装到指定屏幕空间、管理各屏幕空间的应用授权；车机端提供「音乐接入媒体中心」（把音乐 App 的播放状态、封面与歌词翻译给车机媒体中心，目前仅适配[落雪音乐 LX Music](https://github.com/lyswhut/lx-music-mobile)）、「万物全屏」悬浮球与「手机上传安装」。已在 **领克 900** 实机验证；其他领克 / 吉利 / 极氪车机可能适用。

| 组件 | 包名 | 说明 |
|---|---|---|
| 手机端 App | `com.feimi.phone` | 连接车机、装机与装后配置、空间授权管理、运行日志 |
| 车机端 APK | `com.feimi.car` | 音乐接入媒体中心、万物全屏悬浮球、手机上传安装、控制台 UI |

车机端 APK 已内置在手机端里，**手机只需安装一个 App**，装完点一下「一键安装车机端 App」即可自动完成授权与配置。

> **音乐接入的前提**：车机上要先装好落雪音乐（`cn.toside.music.mobile`）。本项目**不打包、不修改**落雪音乐，安装包请从其官方仓库获取：[lyswhut/lx-music-mobile releases](https://github.com/lyswhut/lx-music-mobile/releases)。

## 功能一览

### 手机端

- **一键连接车机**：自动检测热点 → 发现车机 → 连接 ADB → 识别可用屏幕空间；任一步失败都会给出具体原因
- **连接保持**：ADB 密钥本地保存，授权一次长期有效；连接中断后会自动重连，无需手动操作
- **安装与配置车机端**：一键把内置的车机端 App 装到指定屏幕空间，并自动完成装后配置——悬浮窗授权、安装未知应用授权、通知使用权，随后在车机上打开控制台。悬浮球不会自动启用，要全屏时在车机端控制台打开开关
- **安装第三方应用到车机**：选择本地 APK，安装到指定屏幕空间（主驾/中控、副驾等），显示传输进度与安装结果
- **应用授权空间管理**：按屏幕空间查看已装应用，支持批量授权、取消授权、彻底卸载；可诊断应用在各空间的安装状态，并刷新车机桌面让应用立即出现
- **运行日志**：一键复制日志，便于反馈问题
- **首次启动风险告知**：确认后方可使用

界面为主页 + 三个独立功能页（安装与配置车机端、应用授权空间管理、运行日志），各功能页内显示执行状态。

### 车机端

- **音乐接入媒体中心（默认开启）**：把音乐 App 注册为媒体中心的音源，播放卡片显示歌名、封面与简体歌词，方向盘中间键可暂停 / 续播；退出音乐 App 后再点播放，会自动拉起 App 并接续播放。该能力依赖**通知使用权**读取播放状态，手机端装后配置会自动授予，若固件拦截则按手机端日志提示手动勾选；整篇歌词取自落雪的「显示完整蓝牙歌词」通道，需在落雪「设置 → 播放」打开该开关，助手本身不联网查词
- **控制台 UI**：两栏卡片界面，可开关音乐接入与悬浮球、查看接入自检状态与最近事件、切换目标 App、重写音源条目、查看运行日志
- **万物全屏（默认关闭）**：常驻悬浮球，在任意应用界面点一下即把当前页面铺满全屏，再点一次还原；开关状态写入偏好，开机按偏好恢复
- **手机上传安装（实验）**：车机端提供本地网页服务，手机连同一热点后用浏览器打开网址即可上传 APK，车机走系统安装流程；也可只保存文件
- 屏幕上没有可全屏的页面（例如停在桌面）时会给出提示，不受支持的应用会自动回滚并提示

> **适配范围**：音源代理只针对 **[落雪音乐（LX Music，`cn.toside.music.mobile`）](https://github.com/lyswhut/lx-music-mobile)** 做过适配与实测，安装包见 [releases](https://github.com/lyswhut/lx-music-mobile/releases)。其他音乐 App 的播放状态、歌名 / 封面 / 歌词与按键行为**不保证可用**。

## 连不上车机时先试这几步

1. **重启车机**（不是重启 App）。车机 adbd 会卡死：表现为手机端提交公钥后长时间没有回应，或连上几秒后报 `Software caused connection abort`，此时车机屏也不弹授权框。实测重启车机后同一版 App 立刻恢复，不用重装、不用重新授权。
2. **首次连接要授权**：车机屏弹出「允许调试」时点「允许」（建议勾选「始终允许」）。一直不弹框的话，先唤醒车机屏，并确认车停在司机空间（user 0）—— 授权界面属于 user 0，活跃空间不是它时可能看不到。
3. **反复弹框**：说明密钥没存住（卸载重装或清过应用数据），重新授权一次即可。

手机端日志里的「认证轨迹」一行会显示本次握手走到了哪一步：出现「凭已存密钥签名通过」= 免授权直连；出现「已发公钥」后紧接超时，多半是车机 adbd 卡住。

## 编译指南

**不需要 Gradle**，纯命令行流水线：`aapt2 → javac → d8 → zipalign → apksigner`。

环境要求：JDK 17+、`zip`、`curl`。**除此之外没有任何第三方库或预编译二进制依赖** —— 车机端对媒体中心的所有交互都由仓库内源码直接用 Android 公开 API 实现，`clone` 下来即可编译。

```bash
# 1) 准备 Android SDK 组件（约 90MB，只需一次）
mkdir -p build/sdk && cd build/sdk
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r34-linux.zip
curl -LO https://mirrors.cloud.tencent.com/AndroidSDK/platform-34-ext7_r02.zip
unzip -q build-tools_r34-linux.zip && mv android-14 build-tools
unzip -q platform-34-ext7_r02.zip && mv android-34 platform-34
cd ../..

# 2) 编译（首次会自动生成调试签名）
./build/build.sh all
# 交付产物只有一个：build/out/feimi-phone.apk
```

**交付约定：只交付手机端一个 APK。** 车机端 APK 是构建手机端所必需的中间产物，落在 `build/stage/feimi-car.apk`，由 `build.sh` 自动打进 `phone/assets/`，**不会出现在 `build/out/`**；`build.sh` 收尾会清掉 `build/out/` 里除 `feimi-phone.apk` 之外的一切，并校验手机端确实内置了车机端 APK，否则报错退出。

| 命令 | 作用 | 产物 | 是否交付 |
|---|---|---|---|
| `./build/build.sh all` | 车机端 → 打进手机端 → 出交付包 | `build/out/feimi-phone.apk` | ✅ 唯一交付物 |
| `./build/build.sh phone` | 只重编手机端（复用 `build/stage` 里已有的车机端） | `build/out/feimi-phone.apk` | ✅ |
| `./build/build.sh car` | 单独编车机端，仅供调试（如直接 `adb install` 排查） | `build/stage/feimi-car.apk` | ❌ 不对外 |

`build/sdk`、`build/out`、`build/stage`、`build/work`、`build/keystore` 均不入库。签名用 `build/keystore/debug.keystore`（首次编译自动生成，**后续升级请沿用同一份**，建议自行备份）。

### 用 GitHub Actions 出包（不想装本地环境）

1. Fork 本仓库（公开 fork 即可，不需要任何 secret）。
2. `Actions → build-apk → Run workflow`。
3. 跑完在本次 run 页面下载 Artifacts 里的 `feimi-phone-apk`，内含 `feimi-phone.apk` 与 `sha256.txt`。

若希望多次构建签名一致、可覆盖升级，可在 `Settings → Secrets and variables → Actions` 添加可选 secret `KEYSTORE_B64`（= `base64 -w0 build/keystore/debug.keystore`）；不配则每次构建自动生成新签名，**装新包前必须先卸载旧包**。

## 项目声明

- 本项目为独立第三方开源工具，与吉利、领克、极氪、亿咖通（ECARX）、魅族等厂商**均无关联**，未获其授权或认可
- 仅限**自有车辆**使用；请在车辆停稳、挂 P 挡时操作，行车中操作或观看第三方应用内容有安全风险
- 本工具**不修改车机系统组件、不关闭安装校验器、不破解任何校验**；音源代理向媒体中心注册音源，歌词默认直接读取落雪音乐输出的 MediaSession 数据，**不产生外部网络请求**；仅当显式开启 `carproxy_lyric_online` 兼容开关且落雪未给出完整歌词时，才会用**歌名 / 歌手**向第三方歌词服务（QQ 音乐 / LRCLIB）查询，不上传车机标识、位置或个人数据
- 第三方应用的兼容性与装车后果由使用者自行评估承担；厂家可能以此为由拒绝相关保修
- 已在领克 900 实机验证；其他车型可能适用，但不作保证
- 音乐接入目前仅在**[落雪音乐](https://github.com/lyswhut/lx-music-mobile)**上完成适配与实测，其他音乐 App 不保证可用；落雪音乐为第三方开源项目，与本项目无关联
- **从旧版升级**：若你装的是旧仓库 `carhelper` 的 1.2.x 版本，包名与签名都与 2.0.x 一致，可直接覆盖安装；更早的 `com.carhelper.phone` / `com.carhelper.fullscreen` 则不是同一个应用。请在手机上卸载旧版手机端、在车机上彻底卸载旧版车机端，再用新版重新安装；首次连接会重新弹一次「允许调试」授权框
- 许可：**[GPL-3.0](LICENSE)**。你可以自由使用、修改、分发本项目，但**分发修改后的版本（无论源码还是二进制）必须同样以 GPL-3.0 开源**，并保留版权声明与许可文本。本项目**仅提供源代码**，不提供预编译安装包；需要成品可用 GitHub Actions 自行构建（见上文）

详细的风险说明见 [DISCLAIMER.md](DISCLAIMER.md)。
