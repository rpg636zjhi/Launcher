package com.mclauncher.core;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Java 运行时自动供应。
 *
 * 关键规则（老版本必须精确匹配）：
 * - required <= 8（1.7 / 1.8 / 1.12 等）：**只接受完全等于该大版本的 Java**，
 *   用 Java 17/25 启动这类版本会因模块系统/ASM 直接崩。
 * - required >= 9：优先精确版本，找不到时退到 >= required 的可用版本。
 */
public class JavaRuntime {

    public static Path runtimeDir(Config cfg, int major) {
        return cfg.gameDir.resolve("runtime").resolve("jdk-" + major);
    }

    /** 按版本 JSON 的 javaVersion.majorVersion 要求供给 Java（启动器和安装器共用）。 */
    public static Path forVersion(Config cfg, String requiredMajor, Downloader.Listener l) throws Exception {
        int required = parseMajor(requiredMajor);
        if (required <= 8) return ensureExact(cfg, required, l);
        try {
            return ensureExact(cfg, required, l);
        } catch (Exception exactFail) {
            try { return ensure(cfg, required, l); }
            catch (Exception ignored) { throw exactFail; }
        }
    }

    public static int parseMajor(String s) {
        if (s == null) return 17;
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return 17; }
    }

    /** 精确匹配：只认 major 完全相同的运行时。 */
    public static Path ensureExact(Config cfg, int major, Downloader.Listener l) throws Exception {
        Path exe = findInRuntime(cfg, major);
        if (exe != null) return exe;
        Path sys = detectSystemJava(major, true);
        if (sys != null) { l.status("复用系统已安装的 Java " + major + "：" + sys); return sys; }
        return download(cfg, major, l);
    }

    /** 至少版本：>= major 的最高可用（现代版本可接受更高 Java 时使用）。 */
    public static Path ensure(Config cfg, int major, Downloader.Listener l) throws Exception {
        Path exe = findInRuntime(cfg, major);
        if (exe != null) return exe;
        Path sys = detectSystemJava(major, false);
        if (sys != null) { l.status("复用系统已安装 Java " + versionOf(sys)); return sys; }
        return download(cfg, major, l);
    }

    /** 兼容旧调用：等价于「按游戏要求供 Java」。 */
    public static Path ensureLatest(Config cfg, int requiredMajor, Downloader.Listener l) throws Exception {
        return forVersion(cfg, String.valueOf(requiredMajor), l);
    }

    /* ---------- 已下载的运行时 ---------- */
    private static Path findInRuntime(Config cfg, int major) {
        Path dir = runtimeDir(cfg, major);
        if (!Files.exists(dir)) return null;
        Path exe = findJava(dir);
        if (exe == null) return null;
        try {
            int actual = versionOf(exe);
            return actual == major ? exe : null;   // 目录名骗不了人：版本对不上就不用
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- 下载 ---------- */
    private static Path download(Config cfg, int major, Downloader.Listener l) throws Exception {
        Path base = runtimeDir(cfg, major);
        Files.createDirectories(base);
        String os = osToken(), arch = archToken();

        String url = downloadUrl(major, os, arch);
        if (url == null)
            throw new Exception("无法自动下载 Java " + major + "：请手动安装 JDK " + major
                    + " 并设置 JAVA_HOME（老版本对 Java 版本是精确要求的）");

        Path archive = base.resolve("jdk-" + major + ".zip");
        l.status("正在下载 Java " + major + " 运行时…");
        Exception last = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            try { Http.download(url, archive, p -> {}); last = null; break; }
            catch (Exception e) {
                last = e;
                boolean rate = e.getMessage() != null && e.getMessage().contains("403");
                Thread.sleep(rate ? (long) (2000 * Math.pow(2, Math.min(attempt, 4))) : 600L * (attempt + 1));
            }
        }
        if (last != null) throw new Exception("Java 运行时下载失败: " + last.getMessage());

        l.status("正在解压 Java 运行时…");
        unzip(archive, base);
        Files.deleteIfExists(archive);
        Path exe = findJava(base);
        if (exe == null) throw new Exception("解压后未找到 java 可执行文件");
        return exe;
    }

    /**
     * Java 下载地址：
     * - 11/17/21/25 等：微软 OpenJDK（aka.ms 直链，国内可达）
     * - 8：微软已不再提供，改走 Adoptium（Eclipse Temurin），仅 Windows 提供 zip
     */
    private static String downloadUrl(int major, String os, String arch) {
        // 32 位系统：微软不提供 x86 构建，统一走 Adoptium（Temurin）的 x86 包
        if ("x86".equals(arch))
            return "https://api.adoptium.net/v3/binary/latest/" + major + "/ga/" + os + "/x86"
                    + "/jdk/hotspot/normal/eclipse";
        if (major >= 11)
            return "https://aka.ms/download-jdk/microsoft-jdk-" + major + "-" + os + "-" + arch + ".zip";
        if (major == 8 && "windows".equals(os))
            return "https://api.adoptium.net/v3/binary/latest/8/ga/windows/" + arch
                    + "/jdk/hotspot/normal/eclipse";
        return null;
    }

    private static String osToken() {
        String o = Downloader.detectOs();
        return o.equals("windows") ? "windows" : o.equals("osx") ? "macos" : "linux";
    }

    private static String archToken() {
        String a = Downloader.detectArch();
        if (a.equals("aarch64")) return "aarch64";
        if (a.equals("x86")) return "x86";
        return "x64";
    }

    /* ---------- 系统探测 ---------- */
    private static Path findJava(Path dir) {
        if (dir == null || !Files.exists(dir)) return null;
        String name = Downloader.detectOs().equals("windows") ? "java.exe" : "java";
        try (var walk = Files.walk(dir)) {
            return walk.filter(p -> p.getParent() != null && p.getParent().endsWith("bin")
                            && p.getFileName().toString().equals(name))
                       .findFirst().orElse(null);
        } catch (Exception e) { return null; }
    }

    /**
     * 扫描 JAVA_HOME 与常见安装目录。
     * exact=true：只要版本完全等于 major；exact=false：要 >= major 的最高版本。
     */
    static Path detectSystemJava(int major, boolean exact) throws Exception {
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
        int best = -1;
        Path bestExe = null;
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (var s = Files.list(r)) {
                for (Path d : (Iterable<Path>) s::iterator) {
                    if (!Files.isDirectory(d)) continue;
                    Path exe = d.resolve("bin").resolve(javaName);
                    if (!Files.isRegularFile(exe)) continue;
                    try {
                        int v = versionOf(exe);
                        if (exact ? v == major : v >= major) {
                            if (v > best) { best = v; bestExe = exe; }
                        }
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
        // Java 8 输出形如 `1.8.0_xxx`，其余形如 `21.0.x`
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("version \"(\\d+)").matcher(out);
        if (!m.find()) throw new Exception("无法解析 Java 版本");
        int major = Integer.parseInt(m.group(1));
        if (major == 1) {
            java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("version \"1\\.(\\d+)").matcher(out);
            if (m2.find()) return Integer.parseInt(m2.group(1));
        }
        return major;
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
