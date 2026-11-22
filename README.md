# xyc Launcher

一个极简的 Minecraft Java 版启动器。安装包 184 KB，不捆 Java、不捆依赖，双击即用；第一次打开会按游戏版本要求自己配好运行时。

仅支持 Minecraft: Java Edition（Windows）

## 下载

| 版本 | 体积 | 文件 |
| --- | --- | --- |
| 64 位（推荐） | 184 KB | `xyc.Launcher1.0.1.exe` |
| 32 位 | 184 KB | `xyc.Launcher.32bit1.0.1.exe` |

- 系统：Windows + .NET Framework 4.x（Win10 / Win11 自带，不用另装）
- Java：不用手动装。依次在 `JAVA_HOME`、注册表、`.minecraft\runtime`、`PATH` 里找，找不到就自动下载对应版本并解压
- 配置：双击即开，设置写在 exe 同目录的 `config.json`

## 校验与签名

两个文件都是 187,984 字节（即表中的 184 KB）。下载后可核对哈希：

| 文件 | SHA-256 |
| --- | --- |
| `xyc.Launcher1.0.1.exe` | `e7477d2eaa6458cd6c2268aad4319e3cb2eb70e99e659a3a65f56944bcc8fa6a` |
| `xyc.Launcher.32bit1.0.1.exe` | `ff6df2bcb121b1db18f676231295045db9dfce6ef3023fe7b05e1bb61c1a70e4` |

对得上就是原版，对不上别运行。

exe 带 Authenticode 数字签名（SHA-256 / RSA，含 DigiCert 时间戳），证书 Subject 是 `_xycwww_`，有效期到 2036 年；文件属性里是 `xyc Launcher 1.0.1.0`、© `_xycwww_`。

这张证书是自签名的，没有 CA 签发，所以 Windows 第一次运行会弹「未知发布者」。这不是文件有问题，只是没有第三方为身份背书；要消掉这个提示只能买代码签名证书（OV/EV，一年几百到几千）。

### 发布包拆解

Release 里的 exe 是「.NET 外壳 + 内嵌 jar」两层，拆出来的东西都在 `unpacked/`：

- `xyc_embedded.jar`：从 exe 原样取出的 jar，125,473 字节，44 个 class
- `classes/`：同一个 jar 展开后的内容，可直接反编译
- `shell/pe-info.txt`：PE 结构、版本资源、外壳方法名和字符串常量
- `shell/xyc.Launcher.cer`：签名证书（DER）
- `extract.py`：复现脚本，拿原版 exe 自己跑一遍就能得到上面这些

两个 exe（64 / 32 位）里的 jar 逐字节相同，只有外壳不一样。

## 功能

### 游戏版本下载

「刷新版本列表」拉取全部版本（release / snapshot / old_beta / old_alpha）。启动时自动下载版本 JSON、客户端 jar、全部库文件（含 native 解压）与资源文件。

下载默认走 BMCLAPI 国内镜像，官方源→镜像逐文件回退；断点续传 + 8 轮重试 + 403 指数退避，客户端大文件慢网也能下完；最终按 sha1 校验，不符自动重下。

### Mod 加载器

支持 Fabric / Quilt / Forge / NeoForge。选好加载器版本后启动时自动下载加载器库并生成启动 profile；Forge / NeoForge 会自动下载官方 installer 并静默安装。

### 整合包

搜索 Modrinth 整合包（`.mrpack`），下载并解析索引，自动把 mods 与覆盖文件部署到 `.minecraft\instances\<包名>\` 独立实例目录，按整合包要求自动装对应 MC 版本与加载器。

### Java 自动检测与下载

启动时读取版本 JSON 的 `javaVersion` 要求；本机有可用的 Java 就直接用，否则自动下载微软 OpenJDK（aka.ms 官方直链，支持断点续传）并解压。

「Java」下拉默认「自动：按游戏版本要求（推荐）」，也可选「自动：使用最新版」「固定 Java 17」「固定 Java 21」。

### 启动参数

完整解析版本 JSON 的 `arguments.jvm`（`--enable-native-access`、`--add-exports`、`-Djava.library.path`、`-cp` 等 Java 25 必需项）与 `arguments.game`；按规则（os/arch/features）精确启用参数，自定义分辨率自动传入宽高，quick play / demo 等未启用功能参数不会被误传，杜绝「Only one quick play option」类崩溃。

### 文件完整性校验

「校验文件完整性」按钮：按版本 JSON 的 sha1 逐文件核对客户端 jar、全部库与资源，缺失或损坏自动补下并返回修复数量。

### 账号

- 离线模式：输入任意名字，生成离线账号（单机 / 局域网 / 部分服务器）。
- 微软正版登录：设备码全链路（OAuth → Xbox Live → XSTS → Minecraft → 玩家档案），自动保存 `refreshToken`，令牌过期自动刷新。
- 皮肤站外置登录：填写 Yggdrasil API（默认 LittleSkin）+ 账号密码；启动时自动下载并注入 `authlib-injector`，支持 LittleSkin、Blessing Skin 等兼容站点。

### 自动更新

启动时查 GitHub 最新 Release（认 `config.json` 里的 `githubRepo` 或 `updateUrl`），拿到新包先比大小再比 sha256，对得上才换。

替换走一个临时批处理：旧 exe 改名备份 → 拷新文件 → 成功删备份，失败自动回滚，下次打开会提示上次没更成功。国内直连慢时先试 `gh-proxy.com` / `ghproxy.net` 镜像，再回原地址。

### P2P 局域网分发

同一局域网内的设备自动互相发现：UDP 广播，默认 48901 端口，30 秒没有心跳就当对方离线；端口被占用时可用环境变量 `XYC_P2P_PORT` 换一个。

谁已经下过某个文件，别人就直接从他那拉，不走公网。传输走局域网 HTTP，端口由系统临时分配、随广播告知对方。同一个文件最多同时向 5 台设备并发拉取，谁先给完算谁的。

落盘前按 sha1 核对，先写临时文件、校验通过再原子替换，对不上直接丢掉重下，不会留半截文件。只对外提供游戏目录里的文件，路径越界一律拒绝。

不需要这功能就把 `config.json` 里的 `p2pEnabled` 改成 `false`。公共网络请勿开启，原因见「安全提示」。

### 设置

内存 512MB ~ 16GB；下载源 bmclapi 国内镜像 / mojang 官方；窗口 960×640（可在源码 Config 调整）。

## 已知问题

- 只有 Windows 版，Mac 和 Linux 用不了。
- 自动更新第一次要手动填一次源：点右下「检查更新」会弹框让你填 `xyc-launcher/Launcher`，填一次就记进 `config.json`，之后启动自动查。不填的话自动检查直接跳过，等于没更新。
- 自动更新可能被 GitHub 限流：同一 IP 每小时只给 60 次 API 请求，报错就过一会儿再点。
- 微软登录要输设备码，得跳浏览器手动输入一次性码，比网页回调多一步。
- 提示「未获取到 Minecraft 资料」：说明这个微软账号没买 Java 版，先用离线模式就是了。
- 首次启动要下 Java，几十 MB，网速慢会停在下载阶段，等它跑完就行。
- Forge / NeoForge 靠官方 installer 安装，国内网络偶尔超时，重试一次通常就好。
- 整合包只认 `.mrpack`，CurseForge 的 zip 格式需要先转换。
- 加载器版本列表走国内镜像，镜像没同步时会缺版本，换下载源或稍后刷新。
- P2P 服务偏脆：有人连着不发请求会拖慢局域网互传。

## 安全提示

### 分享文件夹前

删掉 `config.json`，那是你的账号文件。登录令牌在新版里是加密存放的，密钥单独一个文件，所以整个启动器文件夹都别直接发出去。别接收别人发来的 `config.json`，也不要拿别人的配置覆盖自己的，用启动器自己生成的那份。

### P2P

P2P 没有口令。同一局域网里连上端口就能取走游戏目录里的文件（日志、存档、别的启动器留下的账号库等，前提是猜对路径），还会广播这台机器的电脑名。

在公共 Wi-Fi、网吧、公司网络里别开着 P2P：那等于把游戏目录挂在局域网里给人看。不需要就把 `p2pEnabled` 关掉。

首次启动会被 Windows 防火墙拦一下，选「专用网络」允许，否则同网段看不见对方。

### 配置与执行

配置里的 Java 路径会被直接执行，只校验文件名是不是 `java.exe` 和 `-version` 的输出。别用别人给的配置。

### 下载源

版本和加载器尽量从官方或 BMCLAPI 下，别为了快随手切到不认识的镜像。`mcimirror.com` 这类第三方镜像提供的是版本清单，选它就意味着装哪个客户端由它决定。

Java 运行库和皮肤站插件下载时没做哈希校验，只靠 HTTPS 保证来源可信。

### 证书回落

1.0.1 起 HTTPS 会在 Java 自带证书校验失败时回落到 Windows 根证书库。装过企业代理 / 抓包工具根证书的机器，那些连接也会被当成可信，介意就自己留意。

## 许可

MIT License，完整条款见 [LICENSE](LICENSE)。

Copyright (C) 2026 xycwww2025

可以自由使用、修改、再分发，包括商业用途，也可闭源使用；只需保留版权声明和许可声明。不提供任何担保。

## 声明

Minecraft 是 Mojang Studios 的商标。本项目为非官方工具，不提供游戏本体，与 Mojang Studios / Microsoft 无关联。
