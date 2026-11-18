package com.mclauncher.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 游戏文件下载/安装：版本JSON、客户端jar、库(含native)、资源；支持续传、退避、完整性校验。 */
public class Downloader {

    public interface Listener {
        void status(String msg);
        void progress(int done, int total);
    }

    public static class Result {
        public Map<String,Object> versionJson;
        public List<String> classpath = new ArrayList<>();
        public Path nativesDir;
        public String assetIndexId;
        public String mainClass;
        public String javaVersionMajor = "17";
    }

    private static final String RES_BASE = "https://resources.download.minecraft.net/";
    private static final String OS = detectOs();
    private static final String ARCH = detectArch();
    private static final int MAX_RETRIES = 8;

    public static Result install(Config cfg, String versionId, String versionJsonUrl, Listener l) throws Exception {
        Path gameDir = cfg.gameDir;
        Path vdir = gameDir.resolve("versions").resolve(versionId);
        Files.createDirectories(vdir);
        Result r = new Result();

        // 1. 版本 JSON
        Path vjson = vdir.resolve(versionId + ".json");
        l.status("正在下载版本信息…");
        String jsonText;
        if (Files.exists(vjson)) jsonText = new String(Files.readAllBytes(vjson), StandardCharsets.UTF_8);
        else {
            jsonText = Http.getText(versionJsonUrl);
            Files.write(vjson, jsonText.getBytes(StandardCharsets.UTF_8));
        }
        Map<String,Object> vj = Json.obj(Json.parse(jsonText));
        r.versionJson = vj;
        r.mainClass = Json.str(vj, "mainClass");
        Map<String,Object> jvInfo = Json.obj(vj, "javaVersion");
        Number jvMajor = jvInfo == null ? null : Json.num(jvInfo, "majorVersion");
        r.javaVersionMajor = jvMajor == null ? "17" : String.valueOf(jvMajor.intValue());

        // 2. 客户端 jar（多源：官方 → 镜像对象路径 → 镜像版本路由 → launcher.mojang）
        Path jar = vdir.resolve(versionId + ".jar");
        Map<String,Object> client = Json.obj(Json.obj(vj, "downloads"), "client");
        if (client != null) {
            String jarUrl = Json.str(client, "url");
            String jarSha1 = Json.str(client, "sha1");
            l.status("正在下载游戏客户端…");
            List<String> cands = new ArrayList<>();
            cands.add(jarUrl);
            if (!"mojang".equals(cfg.downloadMirror)) {
                if (jarSha1 != null && !jarSha1.isEmpty())
                    cands.add(cfg.mirrorBase() + "/v1/objects/" + jarSha1 + "/client.jar");
                cands.add(cfg.mirrorBase() + "/version/" + versionId + "/client");
                cands.add(jarUrl.replace("https://piston-data.mojang.com", "https://launcher.mojang.com"));
            }
            downloadAny(cands, jar, jarSha1, cfg);
        }

        // 3. 库
        r.nativesDir = gameDir.resolve("libraries").resolve("natives").resolve(versionId);
        Files.createDirectories(r.nativesDir);
        List<Object> libs = Json.arr(vj, "libraries");
        if (libs != null) {
            int total = libs.size(), done = 0;
            for (Object o : libs) {
                Map<String,Object> lib = Json.obj(o);
                l.status("正在下载库文件 (" + (++done) + "/" + total + ")");
                l.progress(done, total);
                installLibrary(cfg, lib, r);
            }
        }

        // 4. 资源
        installAssets(cfg, vj, l, r);
        return r;
    }

    private static void installLibrary(Config cfg, Map<String,Object> lib, Result r) throws Exception {
        if (!rulesAllow(lib)) return;
        Map<String,Object> dl = Json.obj(lib, "downloads");
        Map<String,Object> natives = Json.obj(lib, "natives");

        if (natives != null) {
            String classifier = pickNatives(natives);
            Map<String,Object> classifiers = Json.obj(dl, "classifiers");
            Map<String,Object> artifact = Json.obj(classifiers, classifier);
            if (artifact != null) {
                Path f = libPath(cfg, lib, artifact, classifier);
                String url = Json.str(artifact, "url");
                if (url == null) url = mavenUrl(cfg, Json.str(lib, "name") + ":" + classifier);
                if (url == null) return;
                downloadAny(candidates(cfg, url), f, Json.str(artifact, "sha1"), cfg);
                extractNatives(f, r.nativesDir, lib);
            }
        } else {
            Map<String,Object> artifact = Json.obj(dl, "artifact");
            if (artifact != null) {
                Path f = libPath(cfg, lib, artifact, null);
                String url = Json.str(artifact, "url");
                if (url == null) url = mavenUrl(cfg, Json.str(lib, "name"));
                if (url == null) return;
                downloadAny(candidates(cfg, url), f, Json.str(artifact, "sha1"), cfg);
                r.classpath.add(f.toString());
            }
        }
    }

    private static List<String> candidates(Config cfg, String url) {
        List<String> cands = new ArrayList<>();
        if (url == null) return cands;
        cands.add(url);
        if ("mojang".equals(cfg.downloadMirror)) return cands;
        if (url.startsWith("https://libraries.minecraft.net/"))
            cands.add(cfg.mirrorBase() + "/libraries/" + url.substring("https://libraries.minecraft.net/".length()));
        if (url.startsWith("https://piston-data.mojang.com/")) {
            String tail = url.substring("https://piston-data.mojang.com/".length());
            cands.add(cfg.mirrorBase() + "/" + tail);
        }
        return cands;
    }

    /** 多源 + 断点续传重试；失败保留部分文件，最终按 sha1 校验。 */
    private static void downloadAny(List<String> urls, Path target, String sha1, Config cfg) throws Exception {
        if (urls.isEmpty()) throw new Exception("无可用下载源");
        if (needDownload(target, sha1)) {
            Exception last = null;
            boolean ok = false;
            for (int attempt = 0; attempt < MAX_RETRIES && !ok; attempt++) {
                for (String u : urls) {
                    try { Http.download(u, target, p -> {}); ok = true; last = null; break; }
                    catch (Exception e) { last = e; /* 保留部分文件供 Range 续传 */ }
                }
                if (!ok) {
                    boolean rate = last != null && last.getMessage() != null && last.getMessage().contains("403");
                    long wait = rate ? (long) (2000 * Math.pow(2, Math.min(attempt, 4))) : 600L * (attempt + 1);
                    Thread.sleep(wait);
                }
            }
            if (!ok) throw new Exception("所有下载源均失败（已重试 " + MAX_RETRIES + " 轮，可再次点击继续）: "
                    + urls.get(0) + " (" + (last == null ? "" : last.getMessage()) + ")");
        }
        if (Files.exists(target) && sha1 != null && !sha1.isEmpty()
                && !sha1(target).equalsIgnoreCase(sha1)) {
            Files.deleteIfExists(target);
            Exception last = null;
            for (String u : urls) {
                try { Http.download(u, target, p -> {}); last = null; break; }
                catch (Exception e) { last = e; }
            }
            if (last != null) throw new Exception("重下失败: " + last.getMessage());
        }
    }

    private static Path libPath(Config cfg, Map<String,Object> lib, Map<String,Object> artifact, String classifier) {
        String path = Json.str(artifact, "path");
        if (path != null)
            return cfg.gameDir.resolve("libraries").resolve(path.replace('/', java.io.File.separatorChar));
        String[] parts = Json.str(lib, "name").split(":");
        String group = parts[0], art = parts[1], ver = parts[2];
        String cls = parts.length > 3 ? parts[3] : classifier;
        String jarName = art + "-" + ver + (cls != null ? "-" + cls : "") + ".jar";
        String rel = group.replace('.', '/') + "/" + art + "/" + ver + "/" + jarName;
        return cfg.gameDir.resolve("libraries").resolve(rel.replace('/', java.io.File.separatorChar));
    }

    private static String mavenUrl(Config cfg, String name) {
        String[] p = name.split(":");
        if (p.length < 3) return null;
        String group = p[0].replace('.', '/');
        String cls = p.length > 3 ? "-" + p[3] : "";
        String base = "mojang".equals(cfg.downloadMirror) ? "https://libraries.minecraft.net"
                                                          : cfg.mirrorBase() + "/libraries";
        return base + "/" + group + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + cls + ".jar";
    }

    private static boolean rulesAllow(Map<String,Object> lib) {
        List<Object> rules = Json.arr(lib, "rules");
        if (rules == null || rules.isEmpty()) return true;
        for (Object o : rules) {
            Map<String,Object> rule = Json.obj(o);
            if (ruleMatches(rule)) return "allow".equals(Json.str(rule, "action"));
        }
        return true;
    }

    private static boolean ruleMatches(Map<String,Object> rule) {
        Map<String,Object> os = Json.obj(rule, "os");
        if (os != null) {
            String name = Json.str(os, "name");
            if (name != null && !name.equals(OS)) return false;
            String arch = Json.str(os, "arch");
            if (arch != null && !arch.equals(ARCH)) return false;
        }
        Map<String,Object> features = Json.obj(rule, "features");
        if (features != null) {
            for (Map.Entry<String,Object> e : features.entrySet()) {
                Boolean val = e.getValue() instanceof Boolean ? (Boolean) e.getValue() : null;
                if (val == null) continue;
                boolean has = "is_demo_user".equals(e.getKey()) || "has_custom_resolution".equals(e.getKey());
                if (has != val) return false;
            }
        }
        return true;
    }

    private static String pickNatives(Map<String,Object> natives) {
        String c = Json.str(natives, OS);
        return c == null ? "natives-" + OS : c;
    }

    private static void extractNatives(Path jar, Path nativesDir, Map<String,Object> lib) throws Exception {
        Set<String> exclude = new HashSet<>();
        List<Object> excl = Json.arr(Json.obj(lib, "extract"), "exclude");
        if (excl != null) for (Object e : excl) exclude.add(Json.str(e));
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            var it = jf.entries();
            while (it.hasMoreElements()) {
                var e = it.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !(n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib"))) continue;
                if (exclude.stream().anyMatch(n::startsWith)) continue;
                Path out = nativesDir.resolve(n.replace('/', java.io.File.separatorChar));
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                try (var in = jf.getInputStream(e)) { Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
        }
    }

    private static void installAssets(Config cfg, Map<String,Object> vj, Listener l, Result r) throws Exception {
        Map<String,Object> assetIndex = Json.obj(vj, "assetIndex");
        if (assetIndex == null) return;
        String id = Json.str(assetIndex, "id");
        r.assetIndexId = id;
        Path idxFile = cfg.gameDir.resolve("assets").resolve("indexes").resolve(id + ".json");
        Files.createDirectories(idxFile.getParent());
        List<String> idxCands = new ArrayList<>();
        String url = Json.str(assetIndex, "url");
        idxCands.add(url);
        if (!"mojang".equals(cfg.downloadMirror) && url != null && url.startsWith("https://piston-meta.mojang.com/"))
            idxCands.add(cfg.mirrorBase() + "/" + url.substring("https://piston-meta.mojang.com/".length()));
        downloadAny(idxCands, idxFile, Json.str(assetIndex, "sha1"), cfg);

        Map<String,Object> idx = Json.obj(Json.parse(new String(Files.readAllBytes(idxFile), StandardCharsets.UTF_8)));
        Map<String,Object> objects = Json.obj(idx, "objects");
        if (objects == null) return;
        List<String> keys = new ArrayList<>(objects.keySet());
        int total = keys.size(), done = 0;
        Path objectsDir = cfg.gameDir.resolve("assets").resolve("objects");
        Files.createDirectories(objectsDir);
        for (String key : keys) {
            Map<String,Object> obj = Json.obj(objects.get(key));
            String hash = Json.str(obj, "hash");
            if (hash == null || hash.length() < 2) continue;
            Path target = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (needDownload(target, hash)) {
                l.status("正在下载资源文件 (" + (done + 1) + "/" + total + ")");
                List<String> cands = new ArrayList<>();
                cands.add(RES_BASE + hash.substring(0, 2) + "/" + hash);
                if (!"mojang".equals(cfg.downloadMirror))
                    cands.add(cfg.mirrorBase() + "/assets/" + hash.substring(0, 2) + "/" + hash);
                downloadAny(cands, target, hash, cfg);
            }
            l.progress(++done, total);
        }
    }

    /** 完整性校验：扫描已安装版本的全部应有文件，缺失/损坏则补下，返回修复的文件数。 */
    public static int verify(Config cfg, String versionId, Listener l) throws Exception {
        Path vdir = cfg.gameDir.resolve("versions").resolve(versionId);
        Path vjson = vdir.resolve(versionId + ".json");
        if (!Files.exists(vjson)) throw new Exception("版本 JSON 不存在，无法校验，请先安装");
        Map<String,Object> vj = Json.obj(Json.parse(new String(Files.readAllBytes(vjson), StandardCharsets.UTF_8)));
        int[] fixed = {0};
        Listener wrap = new Listener() {
            public void status(String m) { l.status(m); }
            public void progress(int d, int t) { l.progress(d, t); }
        };

        Map<String,Object> client = Json.obj(Json.obj(vj, "downloads"), "client");
        if (client != null) {
            Path jar = vdir.resolve(versionId + ".jar");
            if (needDownload(jar, Json.str(client, "sha1"))) {
                String sha1 = Json.str(client, "sha1");
                List<String> cands = new ArrayList<>();
                cands.add(Json.str(client, "url"));
                if (!"mojang".equals(cfg.downloadMirror))
                    cands.add(cfg.mirrorBase() + "/v1/objects/" + sha1 + "/client.jar");
                downloadAny(cands, jar, sha1, cfg);
                fixed[0]++;
            }
        }
        Result tmp = new Result();
        tmp.nativesDir = cfg.gameDir.resolve("libraries").resolve("natives").resolve(versionId);
        Files.createDirectories(tmp.nativesDir);
        List<Object> libs = Json.arr(vj, "libraries");
        if (libs != null) for (Object o : libs) {
            Map<String,Object> before = Json.obj(o);
            int beforeCount = countFiles(cfg.gameDir.resolve("libraries"));
            installLibrary(cfg, before, tmp);
            int afterCount = countFiles(cfg.gameDir.resolve("libraries"));
            if (afterCount > beforeCount) fixed[0]++;
        }
        installAssets(cfg, vj, wrap, new Result());
        return fixed[0];
    }

    private static int countFiles(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.walk(dir)) { return (int) s.filter(Files::isRegularFile).count(); }
        catch (Exception e) { return 0; }
    }

    private static boolean needDownload(Path f, String sha1) {
        if (!Files.exists(f)) return true;
        if (sha1 == null || sha1.isEmpty()) return false;
        try { return !sha1(f).equalsIgnoreCase(sha1); }
        catch (Exception e) { return true; }
    }

    public static String sha1(Path f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String detectOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    public static String detectArch() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        if (a.contains("aarch64") || a.contains("arm64")) return "aarch64";
        return a.contains("64") ? "x86_64" : "x86";
    }
}
