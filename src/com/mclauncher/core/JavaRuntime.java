package com.mclauncher.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 运行时自动供应：
 * 1. 自动检测本机/内置 Java；缺失时自动下载微软 OpenJDK 便携包并解压。
 * 2. 默认使用最新版 Java（探测 26 → 25 → … 取最新可达版本），游戏要求更高时按游戏要求。
 */
public class JavaRuntime {

    public static Path runtimeDir(Config cfg, int major) {
        return cfg.gameDir.resolve("runtime").resolve("jdk-" + major);
    }

    /** 按指定大版本确保可用，返回 java 可执行文件路径。
     *  顺序：本启动器已下载的运行时 → 系统已安装 Java（自动探测，>= 目标版本）→ 自动下载微软 OpenJDK。 */
    public static Path ensure(Config cfg, int major, Downloader.Listener l) throws Exception {
        Path exe = findJava(runtimeDir(cfg, major));
        if (exe != null) return exe;

        Path sys = detectSystemJava(major);
        if (sys != null) { l.status("复用系统已安装的 Java " + versionOf(sys)); return sys; }

        Path base = runtimeDir(cfg, major);
        Files.createDirectories(base);
        String osName = Downloader.detectOs().equals("windows") ? "windows"
                : Downloader.detectOs().equals("osx") ? "macos" : "linux";
        String arch = Downloader.detectArch().equals("aarch64") ? "aarch64" : "x64";
        String url = "https://aka.ms/download-jdk/microsoft-jdk-" + major + "-" + osName + "-" + arch + ".zip";

        Path zip = base.resolve("jdk-" + major + ".zip");
        l.status("正在下载 Java " + major + " 运行时…");
        Exception last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try { Http.download(url, zip, p -> {}); last = null; break; }
            catch (Exception e) {
                last = e;
                boolean rate = e.getMessage() != null && e.getMessage().contains("403");
                Thread.sleep(rate ? (long) (2000 * Math.pow(2, Math.min(attempt, 4))) : 600L * (attempt + 1));
            }
        }
        if (last != null) throw new Exception("Java 运行时下载失败: " + last.getMessage());

        l.status("正在解压 Java 运行时…");
        unzip(zip, base);
        Files.deleteIfExists(zip);
        exe = findJava(base);
        if (exe == null) throw new Exception("解压后未找到 java 可执行文件");
        return exe;
    }

    /** 默认策略：使用最新版 Java。已安装的最高版本可用则直接用，否则下载最新可达版本（不早于游戏要求）。 */
    public static Path ensureLatest(Config cfg, int requiredMajor, Downloader.Listener l) throws Exception {
        // 1. 复用已安装且满足要求的最新版本
        Path runtimeRoot = cfg.gameDir.resolve("runtime");
        int best = -1;
        Path bestExe = null;
        if (Files.isDirectory(runtimeRoot)) {
            try (var s = Files.list(runtimeRoot)) {
                for (Path d : (Iterable<Path>) s::iterator) {
                    String n = d.getFileName().toString();
                    if (!n.startsWith("jdk-")) continue;
                    try {
                        int m = Integer.parseInt(n.substring(4));
                        Path exe = findJava(d);
                        if (m >= requiredMajor && m > best && exe != null) { best = m; bestExe = exe; }
                    } catch (NumberFormatException ignore) {}
                }
            }
        }
        if (bestExe != null) return bestExe;

        // 2. 从最新向下探测可达的版本并下载
        int probeMax = Math.max(requiredMajor, 26);
        for (int m = probeMax; m >= requiredMajor; m--) {
            if (headAvailable(cfg, m)) {
                Path exe = ensure(cfg, m, l);
                if (exe != null) return exe;
            }
        }
        // 3. 兜底：按游戏要求版本下载
        return ensure(cfg, requiredMajor, l);
    }

    private static boolean headAvailable(Config cfg, int major) {
        String osName = Downloader.detectOs().equals("windows") ? "windows"
                : Downloader.detectOs().equals("osx") ? "macos" : "linux";
        String arch = Downloader.detectArch().equals("aarch64") ? "aarch64" : "x64";
        String url = "https://aka.ms/download-jdk/microsoft-jdk-" + major + "-" + osName + "-" + arch + ".zip";
        return Http.headOk(url);
    }

    private static Path findJava(Path dir) {
        if (dir == null || !Files.exists(dir)) return null;
        String name = Downloader.detectOs().equals("windows") ? "java.exe" : "java";
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.getParent() != null && p.getParent().endsWith("bin")
                            && p.getFileName().toString().equals(name))
                       .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    /** 自动探测系统已安装的 Java：扫描 JAVA_HOME 与常见安装目录，验证版本后返回满足 >=major 的最高可用版。 */
    static Path detectSystemJava(int major) throws Exception {
        java.util.List<Path> roots = new java.util.ArrayList<>();
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) roots.add(java.nio.file.Path.of(home));
        boolean win = Downloader.detectOs().equals("windows");
        if (win) {
            for (String p : new String[]{
                "C:\\Program Files\\Java", "C:\\Program Files (x86)\\Java",
                "C:\\Program Files\\Eclipse Adoptium", "C:\\Program Files\\Microsoft",
                "C:\\Program Files\\Android\\openjdk", "C:\\Program Files (x86)\\Android\\openjdk",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\Amazon Corretto", "C:\\Program Files\\BellSoft"})
                roots.add(java.nio.file.Path.of(p));
        } else {
            for (String p : new String[]{"/usr/lib/jvm", "/opt/java", "/Library/Java/JavaVirtualMachines"})
                roots.add(java.nio.file.Path.of(p));
        }
        String javaName = win ? "java.exe" : "java";
        int best = -1; Path bestExe = null;
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (var s = Files.list(r)) {
                for (Path d : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(d)) continue;
                    Path exe = d.resolve("bin").resolve(javaName);
                    if (!Files.isRegularFile(exe)) continue;
                    try {
                        int v = versionOf(exe);
                        if (v >= major && v > best) { best = v; bestExe = exe; }
                    } catch (Exception ignore) {}
                }
            } catch (Exception ignore) {}
        }
        return bestExe;
    }

    /** 通过 `java -version` 解析大版本号；无法执行/解析时抛异常（调用方跳过，兼容失效的 junction）。 */
    private static int versionOf(Path exe) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "-version");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("version \"(\\d+)").matcher(out);
        if (!m.find()) throw new Exception("无法解析 Java 版本");
        return Integer.parseInt(m.group(1));
    }

    /** 对外：解析给定 java 可执行文件的大版本号。 */
    public static int detectMajor(Path exe) throws Exception { return versionOf(exe); }

    private static void unzip(Path zip, Path dest) throws Exception {
        try (InputStream fis = Files.newInputStream(zip); ZipInputStream zis = new ZipInputStream(fis)) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) throw new Exception("非法 zip 条目: " + e.getName());
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    try (var os = Files.newOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
