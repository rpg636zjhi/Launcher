# xyclauncher 修改说明（v2.1）

> 这次只动了两件你最需要的事：**Java 版本选择**、**Forge / NeoForge 支持**，
> 顺手修了三个会直接踩坑的地方（整合包实例重名、mod 文件不验哈希、Forge 库不走镜像）。
> 全部改动已编译通过（JDK 17），`launcher.jar` 已重新打包。

---

## 1. Java 版本：从「永远 Java 25」改成「以版本要求为准」

**原来的问题**

```java
int required = Integer.parseInt(base.javaVersionMajor);        // 算出来但没用
if (cfg.javaMajor > 0) return JavaRuntime.ensure(cfg, cfg.javaMajor, ...);  // 默认 25，直接返回
```

只要下拉不是"按游戏要求"，`required` 就是废变量。1.7 / 1.8 / 1.12 以及大量老 Forge 整合包
全被塞进 Java 25 → 启动即崩（就是你截图里 `Missing correct java installation` 那条路的根源）。

**现在的规则**（`GameLauncher.resolveJava` + `JavaRuntime`）

| 情况 | 行为 |
|---|---|
| 版本要求 ≤ 8 | **只接受完全等于该大版本的 Java**，绝不降级到高版本 |
| 版本要求 ≥ 9 | 优先精确版本，拿不到才退到 `>= required` 的可用版本 |
| 手动指定 `javaPath` | 版本兼容才用；不兼容就忽略并在 stderr 提示，而不是硬崩 |
| 下拉选 `javaMajor` | 同上，只在兼容时生效 |

**顺带修好的两处**

- `detectSystemJava` 现在区分「精确匹配」和「至少版本」，老版本不会再把系统里的 JDK 25 当 Java 8 用
- Java 8 官方 aka.ms 已经不提供了（微软 OpenJDK 只从 11 起），**补了 Adoptium 下载源**（Windows 走 zip）；
  Linux/macOS 无法自动下载 Java 8 时给明确提示，让你手动装
- `versionOf` 现在能正确解析 Java 8 的 `1.8.0_xxx`（以前会把 major 解析成 `1`）
- 下拉默认项从「Java 25（推荐）」改为「自动：按游戏版本要求（推荐）」，`Config.javaMajor` 默认值 `-1`

---

## 2. Forge / NeoForge：走官方安装器 + 版本继承

**原来的问题**：`ModLoaderManager` 只有 FABRIC / QUILT 两个常量，Forge 整合包解析出
`loaderKind = null` 后按原版启动，一堆 mod 必然报错。

**现在的做法**（最稳的一种，不手搓 Forge 的补丁流程）

1. 下载官方 installer：`forge-{mc}-{ver}-installer.jar` / `neoforge-{ver}-installer.jar`
2. 用**按版本要求选出的 Java** 执行 `--installClient <gameDir>`（带 10 分钟超时与输出尾巴回传）
3. 安装器生成 `versions/<id>/<id>.json`（带 `inheritsFrom`）
4. 新增 `Downloader.installLocal()`：读这个 json，自动把父版本（原版）先装好，
   再**合并子版本**：
   - 库按 `group:artifact` 去重，子版本覆盖父版本的旧版本号，然后重建 classpath
   - `mainClass` 取子版本的
   - `arguments.jvm` / `arguments.game` 父 + 子拼接
   - 1.12 及以前的 `minecraftArguments` 子版本优先
   - `clientJar` 指向父版本 jar（`Result` 新增 `clientJar` / `versionId` 字段）
5. 已装过的加载器直接跳过，秒开

**版本列表**

- Forge：官方 `promotions_slim.json`，按 `{mc}-recommended` / `{mc}-latest` 取
- NeoForge：`maven-metadata.xml`，按版本号前缀筛选，**同时兼容两套编号**：
  - 新方案 `26.3` → 前缀 `26.3.`（实际有 `26.3.0.110`）
  - 老方案 `1.21.1` → 前缀 `21.1.`（实际有 `21.1.267`）；`1.20.1` → `47.`
  - 过滤 alpha / beta，最新版排前面

**镜像**：Forge / NeoForge / Fabric 的 maven 地址都会追加 BMCLAPI 的 `/maven/` 代理作为
备选源；Forge installer 下载失败时第二轮自动换镜像。列表请求加了 3 次退避重试（这些站偶尔 503）。

---

## 3. 整合包：实例重名 + 哈希校验

- `safeName` 以前把非 ASCII 全换成 `_`，`乌托邦探险之旅` → `_________`，同长度中文包直接撞目录互相覆盖。
  现在保留中英文，只清掉 `\ / : * ? " < > |` 和空白，并附一段短哈希，重名不再串。
- mrpack 的 `files[].hashes` 以前完全没用。现在下完按 `sha1`（其次 `sha512`）核对，
  已存在的文件哈希不符会先删掉重下，下坏了当场报错而不是拖到启动。

---

## 4. 怎么测（Windows）

```bat
build.bat                     :: 或直接用已打好的 launcher.jar
java -jar launcher.jar
```

建议按这个顺序验：

1. **原版 1.20.1**：Java 下拉选「自动：按游戏版本要求」，启动看是否用 17
2. **老版本 1.12.2 / 1.7.10**：确认它不再用 Java 25，而是要求 Java 8（没装会提示装 JDK 8）
3. **Forge**：原版页选中 1.20.1 → 加载器页选 Forge → 「加载加载器版本」→ 应出现 47.4.28 / 47.4.29
   → 启动，日志里会看到 installer 输出和 `1.20.1-forge-47.4.29 已就绪`
4. **NeoForge**：选 1.21.1 或 26.3，列表应出 `21.1.x` / `26.3.0.x`
5. **整合包**：装一个中文名的 Modrinth Forge 包，确认实例目录名可读、不重名

---

## 5. 已验证 / 未验证

**已实测通过（Linux + JDK 17 编译运行）**

- 编译零错误
- Forge 列表：`1.20.1 → [47.4.28, 47.4.29]`；`26.3 → [66.1.8]`
- NeoForge 列表：`26.3 → 26.3.0.110 …`（32 个）；`1.21.1 → 21.1.267 …`（262 个）
- Quilt 列表：310 个
- 版本合并：伪造 Forge json 走 `installLocal`，`id`、`mainClass`（BootstrapLauncher）、
  `arguments.jvm/game` 拼接、`clientJar` 指向父版本——全部正确
- Java 策略：要求 8 → 明确报「请手动装 JDK 8」；要求 17 → 复用系统 JDK 17
- 安装器 URL：`forge-1.20.1-47.4.29-installer.jar`、`forge-26.3-66.1.8-installer.jar` 均 200

**没法在这里验证**

- 真实跑一次 Forge / NeoForge 安装器（需要 Windows 的 MC 环境 + 完整下载链路，当前沙箱对
  `maven.neoforged.net` 返回 503）
- 真实启动游戏（无图形界面）
- NeoForge 安装器 URL 是按官方 maven 标准路径推出的，本机 503 无法实测，你那边跑一次即知

---

## 6. 我仍然没动的（你决定要不要继续）

1. **README 里的 P2P / 多线程多点下载**：代码里确实不存在（除了日志线程没有并发），
   现在还是串行下库和资源。这块要真做，是独立的一坨活（线程池 + 进度聚合 + 失败重试聚合）。
2. **token 明文存 config.json**：`accessToken` / `refreshToken` 都裸存。至少可以改成
   存到系统凭据管理器，或者至少单独存文件 + 加权限。
3. **共用 `msClientId`**：`0add8caf-…` 是第三方共享的 Azure 客户端 ID，一旦被撤销全员登录失效。
   正经做法是让作者自己注册一个。
4. **CurseForge 整合包**：只接了 Modrinth。

---

## 7. 32 位（x86）支持

打包了 32 位版（`xycLauncher-win32.zip`），但光换 JRE 不够，代码里有三处必须跟着改：

1. **Java 下载改为按架构选源**：32 位系统拿不到微软的 aka.ms x64 直链，`archToken()` 现在会返回
   `x86`，`downloadUrl()` 对 x86 一律走 Adoptium（Temurin）的 `.../windows/x86/jdk/...`，
   8 和 17+ 都能下
2. **本地库（natives）按架构挑 classifier**：原版 1.20.1 的 `natives-windows` 是 x64 的 DLL，
   32 位下加载直接 UnsatisfiedLinkError。现在 `installLibrary` 在 `natives-windows` 命中后，
   若架构是 x86 / arm64 会改挑 `natives-windows-x86` / `natives-windows-arm64`（不存在才回退）
3. **堆内存封顶**：32 位 JVM 可用堆约 1.5G，`config.json` 写 2048 会直接起不来。
   `buildCommand` 检测到 `sun.arch.data.model=32` 时把 `-Xmx` 压到 1536M

32 位版的能力边界（写进了包里的说明）：
- 1.12.2 及更早最稳；1.17+ 官方仍提供 x86 原生库，但内存吃紧
- 大型整合包（需 3G 以上）跑不动，该用 64 位版
- Forge / NeoForge 安装器也会自动用 32 位 JDK
