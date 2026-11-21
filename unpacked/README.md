# 发布包拆解

把 Release 里的 `xyc.Launcher1.0.1.exe` 拆开，看看里面到底是什么。

## 里面是什么

exe 本身是个 **.NET 外壳**（PE32，C# 写的），真正的启动器是塞在它资源里的一个 jar：

```
xyc.Launcher1.0.1.exe  187,984 字节
├── .NET 外壳        .text   161,280 字节   ← 读 jar、找 Java、起进程
├── 图标 / 版本资源   .rsrc    18,432 字节
├── 重定位表          .reloc      512 字节
├── 数字签名         7,248 字节（PKCS#7，自签名，含 DigiCert 时间戳）
└── 内嵌 jar         145,672 字节   ← 启动器本体，49 个 class
```

两个 exe（64 位 / 32 位）里的 jar **逐字节相同**，只有外壳不一样。

## 目录

| 路径 | 是什么 |
|---|---|
| `xyc_embedded.jar` | 从 exe 里原样取出的 jar，145,672 字节 |
| `classes/` | 同一个 jar 展开后的内容，49 个 `.class` + `META-INF/MANIFEST.MF` + `icon.png` |
| `shell/pe-info.txt` | PE 结构、版本资源、外壳方法名与字符串常量、证书摘要 |
| `shell/xyc.Launcher.cer` | 签名证书（DER） |
| `extract.py` | 复现脚本，从原版 exe 现场跑就能得到上面这些 |

## 关于 P2P

`classes/com/mclauncher/core/` 下的 `P2PNode.class`、`P2PNode$1.class`、`P2PNode$2.class`、
`P2PNode$3.class` 就是局域网分发那一块。**这里是编译后的字节码，不是源码**——反编译能看到类结构和调用关系，
但拿不到原始的 `.java`。仓库里没有 P2P 的源码。

## 主程序

入口类 `com/mclauncher/main/Main.class`。跑起来要 Java 8+，大致相当于：

```bat
java -cp xyc_embedded.jar com.mclauncher.main.Main
```

不过它依赖工作目录下会自己生成的 `config.json`，直接跑会按默认值在 `./.minecraft` 里建目录。

## 自己验一遍

```bat
python extract.py xyc.Launcher1.0.1.exe
```

需要 `pefile`（`pip install pefile`）。跑完会得到 `xyc_embedded.jar`、`classes/`、`shell/xyc.Launcher.cer`，
和这里的内容应该一致。
