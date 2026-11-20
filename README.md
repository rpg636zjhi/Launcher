<p align="center">
  <img src="https://avatars.githubusercontent.com/u/358580527?v=4" width="112" alt="xyc Launcher">
</p>

<h1 align="center">xyc Launcher</h1>

<p align="center">
  <a href="https://github.com/xyc-launcher/Launcher/releases/latest"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BD%BD-164%20KB-00A2E8?style=flat-square" alt="下载"></a>
  <a href="https://qun.qq.com/universal-share/share?ac=1&amp;authKey=VkmMgK6LsJL9zqtKh%2FujomMHt%2Bv%2FgR7Y6AdWhyBhJjAigxcMZLsQb2v3AZAxPkPi&amp;busi_data=eyJncm91cENvZGUiOiIxMTA5NzgyMDY0IiwidG9rZW4iOiI2UHBDQWl0Y2szYmtTTkFFeVduRW1EbzZFTnIyODNvZjZNM240ZFRnWGhPRm5QZll1OTFPZStSSFduOFd1SEhHIiwidWluIjoiMzgxMzg5ODQwMSJ9&amp;data=Wi_V6vYnQoUSBEQEnv2hJhbftiJF_Gp89-4q1E8_QfxkvWen8eVZCAFJzi2UCjmotBZmhSKPKGiN52jKEyu7kw&amp;svctype=4&amp;tempid=h5_group_info"><img src="https://img.shields.io/badge/QQ%E7%BE%A4-1109782064-12B7F5?style=flat-square&logo=tencentqq&logoColor=white" alt="QQ 群"></a>
  <a href="https://space.bilibili.com/168686520"><img src="https://img.shields.io/badge/Bilibili-%E8%87%AD%E8%87%AD%E7%9A%84%E7%B4%AB%E5%A4%96%E7%BA%BF%E7%81%AF-FB7299?style=flat-square&logo=bilibili&logoColor=white" alt="Bilibili"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green?style=flat-square" alt="License: MIT"></a>
</p>

<p align="center"><b>164 KB，一秒下完</b> —— GitHub 再慢也拦不住，双击即用，Java 自动配</p>

一个极简的《我的世界》启动器：安装包只有 **0.16 MB**（不到 HMCL 的 1/60），不捆 Java、不捆依赖，第一次打开自己去配需要的运行时。

## 下载

| 版本 | 体积 | 下载 |
|---|---|---|
| 64 位（推荐） | 164 KB | [xyc.Launcher.exe](https://github.com/xyc-launcher/Launcher/releases/download/Launcher/xyc.Launcher.exe) |
| 32 位 | 164 KB | [xyc.Launcher.32bit.exe](https://github.com/xyc-launcher/Launcher/releases/download/Launcher/xyc.Launcher.32bit.exe) |


- **系统**：Windows + .NET Framework 4.x（Win10 / Win11 自带，无需另装）
- **Java**：不用手动装。启动器会依次在 `JAVA_HOME`、注册表、`.minecraft\runtime`、
  `PATH` 里找，找不到就自动下载对应版本并解压
- **配置**：双击即开，设置写在 exe 同目录的 `config.json`

### 校验下载文件

两个文件都是 167,504 字节，下载后可以核对哈希，确认没被动过：

| 文件 | SHA-256 |
|---|---|
| `xyc.Launcher.exe` | `d99df7554b72bd4ca47348cf27258f7332c2b2de1460c70b9852425eb1271b5a` |
| `xyc.Launcher.32bit.exe` | `041495d141d444f1369d4a8b4d9a3b5a638afb61e1548a511e5b61a1c3b07a23` |

Windows 上核对（命令行或 PowerShell 任选）：

```bat
certutil -hashfile xyc.Launcher.exe SHA256
```

```powershell
Get-FileHash xyc.Launcher.exe -Algorithm SHA256
```

跟上面的值一致就是原版，不一致别运行。

### 关于签名和版权

exe 带 Authenticode 数字签名（SHA-256 / RSA，含 DigiCert 时间戳），证书 Subject 是 `_xycwww_`，有效期到 2036 年。文件属性里能看到版本信息：`xyc Launcher 1.0.0.0`、`© _xycwww_`。

这张证书是**自签名**的，没有经过 CA 签发，所以 Windows 第一次运行会弹「未知发布者」。这不是文件有问题，只是没有第三方为身份背书——要消掉这个提示只能买代码签名证书（OV/EV，一年几百到几千）。

### 发布包拆解

Release 里的 exe 是「.NET 外壳 + 内嵌 jar」两层结构，拆出来的东西都在 [unpacked/](unpacked/) 里：

- `xyc_embedded.jar`：从 exe 原样取出的 jar，125,473 字节，44 个 class
- `classes/`：同一个 jar 展开后的内容，可以直接反编译看
- `shell/pe-info.txt`：PE 结构、版本资源、外壳方法名和字符串常量
- `shell/xyc.Launcher.cer`：签名证书（DER）
- `extract.py`：复现脚本，拿原版 exe 自己跑一遍就能得到上面这些

两个 exe（64 位 / 32 位）里的 jar 逐字节相同，只有外壳不一样。


## 功能

- **P2P 局域网分发**
  - 同一局域网内的设备自动互相发现：UDP 广播，默认 **48901** 端口，30 秒没有心跳就当对方离线；
    端口被占时用环境变量 `XYC_P2P_PORT` 换一个。
  - 谁已经下过某个文件，别人就直接从他那拉，不走公网。传输走局域网 HTTP，
    端口由系统临时分配、随广播告知对方。
  - 同一个文件最多同时向 **5** 台设备并发拉取，谁先给完算谁的。
  - 落盘前按 sha1 核对，先写临时文件、校验通过再原子替换，对不上直接丢掉重下，不会留半截文件。
  - 只对外提供游戏目录里的文件，路径越界一律拒绝。
  - 不需要这功能就把 `config.json` 里的 `p2pEnabled` 改成 `false`。
  - 第一次开会被 Windows 防火墙拦一下，选「专用网络」允许，否则同网段看不见对方。
- **账号**
  - 离线模式：输入任意名字，生成离线账号（单机/局域网/部分服务器）。
  - 微软正版登录：设备码全链路（OAuth → Xbox Live → XSTS → Minecraft → 玩家档案），
    自动保存 refreshToken，令牌过期自动刷新。
  - 皮肤站外置登录：填写 Yggdrasil API（默认 LittleSkin）+ 账号密码即可；
    启动时自动下载并注入 authlib-injector，支持 LittleSkin、Blessing Skin 等兼容站点。
- **游戏版本下载**
  - 「刷新版本列表」拉取全部版本（release / snapshot / old_beta / old_alpha）。
  - 启动时自动下载：版本 JSON、客户端 jar、全部库文件（含 native 解压）、资源文件。
  - 下载默认走 BMCLAPI 国内镜像，官方源→镜像逐文件回退；断点续传 + 8 轮重试 + 403 指数退避，
    客户端大文件慢网也能下完；最终按 sha1 校验，不符自动重下。
- **mod 加载器下载**（「Mod 加载器」分页）
  - 支持 **Fabric / Quilt / Forge / NeoForge**：选择加载器版本后启动时自动下载加载器库
    并生成启动 profile；Forge / NeoForge 会自动下载官方 installer 并静默安装。
- **整合包下载**（「整合包」分页）
  - 搜索 Modrinth 整合包（.mrpack），下载并解析索引，自动把 mods 与覆盖文件部署到
    `.minecraft\instances\<包名>\` 独立实例目录，按整合包要求自动装对应 MC 版本与加载器。
- **Java 自动检测与自动下载**
  - 启动时读取版本 JSON 的 javaVersion 要求；本机有可用的 Java 则直接用，
    否则自动下载微软 OpenJDK（aka.ms 官方直链，国内可达、支持断点续传）并解压。
  - 「Java」下拉默认「自动：按游戏版本要求（推荐）」——按版本 JSON 的 javaVersion 选最合适的运行时，
    没有就下载；也可选「自动：使用最新版」「固定 Java 17」「固定 Java 21」。
- **启动参数完整支持**
  - 完整解析版本 JSON 的 `arguments.jvm`（`--enable-native-access`、`--add-exports`、
    `-Djava.library.path`、`-cp` 等 Java 25 必需项）与 `arguments.game`；
  - 按规则（os/arch/features）精确启用参数：自定义分辨率自动传入宽高，
    quick play / demo 等未启用功能参数不会被误传，杜绝「Only one quick play option」类崩溃。
- **文件完整性校验**
  - 「校验文件完整性」按钮：按版本 JSON 的 sha1 逐文件核对客户端 jar、全部库与资源，
    缺失/损坏文件自动补下并返回修复数量。
- **设置**
  - 内存：512MB ~ 16GB；下载源：bmclapi 国内镜像 / mojang 官方；
    窗口 960×640（可在源码 Config 调整）。

## 已知问题

- **只有 Windows 版**：Mac 和 Linux 用不了。
- **首次运行会弹「未知发布者」**：证书自签名，没有 CA 背书，见上面「关于签名和版权」。
- **没有自动更新**：出了新版本要自己重新下载替换，启动器不会提示。
- **微软登录要输设备码**：得跳浏览器手动输入一次性码，比网页回调多一步。
- **提示「未获取到 Minecraft 资料」**：说明这个微软账号没买 Java 版，先用离线模式就是了。
- **首次启动要下 Java**：几十 MB，网速慢的时候会停在下载阶段，等它跑完就行。
- **Forge / NeoForge 靠官方 installer 安装**：国内网络偶尔超时，重试一次通常就好。
- **整合包只认 `.mrpack`**：CurseForge 的 zip 格式需要先转换。
- **加载器版本列表走国内镜像**：镜像没同步时会缺版本，换下载源或稍后刷新。

## 安全提示

- 分享启动器文件夹（发给同学、传群文件、打包上传）之前，先把里面的 `config.json` 删掉，
  那是你的账号文件。
- 登录令牌在新版里是加密存放的，密钥单独一个文件，所以**整个启动器文件夹都别直接发出去**。
- 别接收别人发来的 `config.json`，也不要拿别人的配置文件覆盖自己的，用启动器自己生成的那份。

## 许可

本项目以 **MIT License** 发布，完整条款见 [LICENSE](LICENSE)。

Copyright (C) 2026 xycwww2025

- 可以自由使用、修改、再分发，包括商业用途，也可闭源使用
- 只需要保留版权声明和许可声明
- 不提供任何担保

