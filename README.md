# xyc Launcher · Linux 版

一个极简、绿色便携的 Minecraft Java 版启动器。**把整个文件夹放到任意位置即可运行**，配置写在同目录的 `config.json`，不写系统目录、不改 PATH。

这是原 Windows 版（.NET 外壳 + 内嵌 jar）的 Linux 移植版：核心本来就是跨平台的 Java/Swing 程序，本版本补齐了 Linux 缺失的部分——启动脚本、`.tar.gz` 运行时解压、Java 8 的 Adoptium 下载源、PATH 探测、桌面集成，以及 **Wayland 原生支持**。功能与 Windows 版一致。

> **X11 和 Wayland 都能跑，且在 Wayland 下是原生客户端**（不是 XWayland 兼容层）。原理见下方 [Wayland 支持](#wayland-支持)。

## 快速开始

```bash
# 1. 解压/下载后进入目录
cd xyc-launcher-linux

# 2. 赋予执行权限（仅首次）
chmod +x run.sh

# 3. 启动
./run.sh
```

`run.sh` 会**先检测系统里已经安装的 Java，有就直接用，绝不重复下载**。检测顺序：

1. 内置运行时 `.minecraft/runtime/` 下的 `jdk-*/bin/java`
2. 环境变量 `JAVA_HOME`
3. `PATH` 里的 `java`
4. 常见目录 `/usr/lib/jvm`、`/opt/java`、`/usr/java`、`~/.sdkman/candidates/java` 等

**只有上面全部找不到时**，才会按需自动下载 **Temurin JRE 21**（约 45MB）到 `.minecraft/runtime/`。也就是说：**你系统里只要装了 Java，就什么都不用装、也不会下载任何东西**；终端会打印「使用本地已安装的 Java（未下载任何东西）」。

> **启动器自身要求 Java 17 或更高**（jar 是 Java 17 字节码）；本地若只有 Java 8/11，脚本会自动跳过并下载 JRE 21。
>
> **游戏本体所需的 Java 由启动器按游戏版本自动单独准备**（老版本精确匹配 Java 8，新版用 17/21），与跑界面的 Java 互不干扰——所以即使你用 Java 21 跑启动器，也照样能启动需要 Java 8 的老版本游戏。想手动指定跑界面的 Java，设 `JAVA_HOME` 或 `XYC_JAVA` 即可。

## Wayland 支持

`run.sh` 会**自动识别当前图形会话，并优先复用本地已有 Java，不会为了 Wayland 而强制下载**：

| 会话 | 本地 Java 情况 | 行为 |
| --- | --- | --- |
| Wayland | 本地 Java 就是 JBR（含 `libawt_wlawt.so`） | 直接复用，**原生 Wayland**，不下载 |
| Wayland | 本地是普通 Java（OpenJDK/Temurin…） | 直接用它走 **XWayland**，**不下载** |
| Wayland | 完全没有 Java | 才自动下载 JBR21 走原生 |
| X11 | 有本地 Java | 直接用它走原生 X11，不下载 |

也就是说：**默认不会因为 Wayland 就下载 95MB 的 JBR**。终端会明确告诉你用的是本地 Java 还是原生 Wayland；如果本地 Java 不是 JBR，会提示「本地 Java 不含 WLToolkit，在 Wayland 下通过 XWayland 运行」。

**想要原生 Wayland** 时有两种方式，按需选择：

```bash
# 方式 A：想原生，但允许自动下载 JBR（仅在本地 Java 不支持时下载一次到 .jbr/）
XYC_FORCE_NATIVE_WAYLAND=1 ./run.sh

# 方式 B：自己已有 JBR，直接指定（完全不下载）
JBR_HOME=/path/to/jbr ./run.sh
# 或把 JBR 解压到启动器目录的 .jbr/ 文件夹，脚本自动识别
```

### 强制图形模式

| 命令 | 效果 |
| --- | --- |
| `./run.sh` | 自动：优先用本地 Java；本地是 JBR 才走原生 Wayland，否则 X11/XWayland，**均不下载** |
| `XYC_FORCE_NATIVE_WAYLAND=1 ./run.sh` | Wayland 下强制原生：本地 Java 不支持时自动下载 JBR |
| `XYC_UI=wayland ./run.sh` | 强制原生 Wayland（没有 JBR 会自动下载） |
| `XYC_UI=x11 ./run.sh` | 强制 X11 / XWayland（用本地 Java） |
| `XYC_VULKAN=1 XYC_UI=wayland ./run.sh` | 原生 Wayland + Vulkan 硬件渲染（需显卡驱动支持） |

### 为什么需要 JBR

原生 Wayland 工具包（`WLToolkit`）目前**只在 JetBrains Runtime 里提供**，标准 OpenJDK / Temurin / 微软 OpenJDK 都没有——普通 Java 在 Wayland 下只能走 XWayland 兼容层（高分屏模糊、窗口集成差）。JBR 由 JetBrains 发布、基于 OpenJDK 21、可自由使用。

> 已知限制（JBR 的 WLToolkit 实验性部分）：原生窗口标题栏/decoration、拖放、部分输入法尚不完善；对本启动器的标准 Swing 界面无影响。若遇到兼容问题，`XYC_UI=x11 ./run.sh` 即可走 XWayland 回退。

### 诊断

```bash
chmod +x wayland-check.sh
./wayland-check.sh
```

会打印会话类型、Wayland socket、Java 运行时，以及是否具备原生 Wayland 支持。

### 图形界面直接双击

在文件管理器里右键 `run.sh` → 属性 → 权限 → 勾选「允许作为程序执行」，之后双击即可；或在「打开方式」里选终端运行。

### 注册到应用菜单（可选）

```bash
./install-desktop.sh
```

会把启动器加入系统应用菜单，并询问是否也在桌面放图标。移动的文件夹位置后重新运行一次该脚本即可更新路径。

## 功能

| 功能 | 说明 |
| --- | --- |
| 游戏版本下载 | 拉取 release / snapshot / old_beta / old_alpha 全部版本，自动下客户端、库、native、资源 |
| Mod 加载器 | Fabric / Quilt / Forge / NeoForge，随启动自动安装 |
| 整合包 | 搜索 Modrinth `.mrpack`，下载并部署到 `.minecraft/instances/<包名>/` 独立目录 |
| Java 自动供应 | 按版本 JSON 的 `javaVersion` 精确匹配；老版本 Java 8，新版 17/21，自动下载解压 |
| 启动参数 | 完整解析 `arguments.jvm` / `arguments.game`，按 os/arch/features 规则精确启用 |
| 完整性校验 | 按 sha1 逐文件核对客户端、库、资源，缺失或损坏自动补下 |
| 账号 | 离线账号、微软正版（设备码登录）、皮肤站（Yggdrasil / authlib-injector） |
| 下载源 | bmclapi 国内镜像 / mojang 官方，多源回退 + 断点续传 + sha1 校验 |

## 目录结构

```
xyc-launcher-linux/
├── run.sh                 主启动脚本（自动识别 X11/Wayland，自动找/下 Java）
├── wayland-check.sh       Wayland 原生支持诊断
├── build.sh               从源码重新编译 launcher.jar
├── install-desktop.sh     注册到应用菜单/桌面
├── xyc-launcher.desktop   桌面项模板
├── launcher.jar           启动器本体（跨平台）
├── config.json            首次运行后自动生成（含账号，勿外发）
├── .jbr/                  Wayland 原生运行时（首次在 Wayland 下运行自动下载）
├── .minecraft/            游戏目录（版本/库/资源/运行时/整合包实例）
├── src/                   完整 Java 源码
├── assets/icon.png        图标
├── HeadlessTest.java      无头逻辑自测
├── LICENSE                MIT
└── SECURITY.md            安全说明
```

## 从源码重新编译

需要 JDK（含 `javac`，如 `sudo apt install openjdk-21-jdk`）：

```bash
./build.sh
```

会用源码重建 `launcher.jar`。想用别的 JDK：`JAVAC=/path/to/javac JAR=/path/to/jar ./build.sh`。

自测（含 JSON、离线账号、加载器 meta、Modrinth 搜索、配置持久化）：

```bash
mkdir -p out && javac -encoding UTF-8 -cp launcher.jar -d out HeadlessTest.java
java -cp out:launcher.jar HeadlessTest
```

## 与 Windows 版的差异

- 启动入口从 `run.bat` / `.exe` 外壳换成 `run.sh`；界面与功能完全相同。
- Java 下载：Windows 是 `.zip`（微软/Adoptium），Linux 是 `.tar.gz`（纯 Java 解压，保留可执行位）；Java 8 统一走 Adoptium。
- 新增 PATH 目录探测，兼容 apt / sdkman / 手动解压安装的 Java。
- 新增桌面项与安装脚本。
- 新增 Wayland 原生模式：Wayland 会话下自动用 JBR21 的 `WLToolkit` 启动（`XYC_UI` 可强制切换），X11/XWayland 会话用标准 Java。
- 原版的 P2P 局域网分发、自动更新、证书回落属于 Windows 外壳/独立模块，不在本开源 jar 的功能范围内。

## 常见问题

- **双击 run.sh 没反应**：文件管理器需先允许「作为程序执行」，或直接在终端 `./run.sh`。
- **提示下载 Java 失败**：说明系统里没检测到 Java 且网络不通。手动装 Java 17+ 后设 `JAVA_HOME`，或把任意 JDK 解压到 `.minecraft/runtime/` 下即可，脚本会优先用本地的。
- **Wayland 下没走原生、提示走 XWayland**：因为本地 Java 不是 JBR。这是默认行为（不下载）；想要原生就设 `XYC_FORCE_NATIVE_WAYLAND=1` 自动下载 JBR，或 `JBR_HOME` 指定已有 JBR。
- **Wayland 下窗口样式怪 / 输入法异常**：这是 JBR WLToolkit 的实验性限制，`XYC_UI=x11 ./run.sh` 可回退 XWayland。
- **怎么确认是不是原生 Wayland**：`./wayland-check.sh` 看会话与 `libawt_wlawt.so`；原生模式下终端会打印「图形模式：Wayland 原生（WLToolkit）」和 `-Dawt.toolkit.name=WLToolkit` 参数。
- **版本列表加载失败**：国内网络可换下载源（界面「下载源」下拉），或在 `config.json` 里把 `downloadMirror` 设为 `mojang`。
- **中文显示成方块**：系统缺少中文字体，安装如 `fonts-noto-cjk`（Debian/Ubuntu）或 `wqy-microhei` 后重启启动器。
- **游戏启动崩溃且日志提到 Java 版本**：老版本（1.12 及以前）必须用 Java 8，新版用 17/21；启动器会自动按版本准备，若手动指定过 `javaPath`，可在设置里改回「自动」。
- **想换游戏目录**：改 `config.json` 的 `gameDir`，或在同目录建 `.minecraft` 文件夹即可（默认就是它）。

## 安全提示

- `config.json` 含账号与令牌，**不要外发整个启动器文件夹**。分享前删掉它。
- 配置里的 Java 路径会被直接执行，别用别人给的配置。
- 下载源尽量用官方或 bmclapi，不要随意切到不认识的镜像。
- 详见 [SECURITY.md](SECURITY.md)。

## 许可与声明

MIT License，见 [LICENSE](LICENSE)。Copyright (C) 2026 xycwww2025。

Minecraft 是 Mojang Studios 的商标。本项目为非官方工具，不提供游戏本体，与 Mojang Studios / Microsoft 无关联。

