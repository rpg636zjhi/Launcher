#!/usr/bin/env python3
"""从 Release 的 exe 里把内嵌 jar 和签名证书揪出来。

用法：
    python extract.py xyc.Launcher.exe

产出：
    xyc_embedded.jar        内嵌的 jar（两个 exe 里逐字节相同）
    classes/                jar 展开后的内容
    shell/xyc.Launcher.cer  自签名证书（DER）

原理：这个 exe 是 .NET 外壳 + 资源里塞了一个 jar。jar 以 ZIP 的 PK\\x03\\x04 开头，
结尾是 ZIP 的 EOCD（PK\\x05\\x06）加 22 字节注释区，所以从文件头扫到 EOCD 就是完整 jar。
证书在 PE 头的 IMAGE_DIRECTORY_ENTRY_SECURITY 指向的位置，前面 8 字节是 WIN_CERTIFICATE 头。
"""
import hashlib
import os
import sys
import zipfile
import io

import pefile


def find_jar(raw: bytes) -> bytes:
    start = raw.find(b"PK\x03\x04")
    if start < 0:
        raise SystemExit("没找到 PK\\x03\\x04，不是内嵌 zip 的 exe")
    eocd = raw.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise SystemExit("没找到 ZIP 结尾记录")
    return raw[start:eocd + 22]


def dump_cert(pe, raw: bytes) -> None:
    entry = pe.OPTIONAL_HEADER.DATA_DIRECTORY[
        pefile.DIRECTORY_ENTRY["IMAGE_DIRECTORY_ENTRY_SECURITY"]
    ]
    if not entry.Size:
        print("这个 exe 没有数字签名")
        return
    blob = raw[entry.VirtualAddress: entry.VirtualAddress + entry.Size]
    # WIN_CERTIFICATE：4 字节长度 + 2 字节版本 + 2 字节类型，之后才是 PKCS#7
    der = blob[8:]
    os.makedirs("shell", exist_ok=True)
    out = "shell/xyc.Launcher.cer"
    open(out, "wb").write(der)
    print(f"证书 -> {out}  {len(der)} 字节  sha256={hashlib.sha256(der).hexdigest()}")


def main() -> None:
    path = sys.argv[1] if len(sys.argv) > 1 else "xyc.Launcher.exe"
    raw = open(path, "rb").read()
    jar = find_jar(raw)
    open("xyc_embedded.jar", "wb").write(jar)
    print(f"jar -> xyc_embedded.jar  {len(jar)} 字节  sha256={hashlib.sha256(jar).hexdigest()}")

    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        z.extractall("classes")
        classes = sum(1 for n in z.namelist() if n.endswith(".class"))
    print(f"已展开 {classes} 个 class 到 classes/")

    pe = pefile.PE(path, fast_load=False)
    dump_cert(pe, raw)


if __name__ == "__main__":
    main()
