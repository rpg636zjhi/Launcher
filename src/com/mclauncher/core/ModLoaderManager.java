package com.mclauncher.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * mod 加载器：Fabric / Quilt（profile JSON 方案）。
 * 解析 meta 的启动 profile，下载加载器库并提供 mainClass 与额外 JVM 参数。
 */
public class ModLoaderManager {

    public static final String FABRIC = "fabric";
    public static final String QUILT = "quilt";

    public static class ModProfile {
        public String kind;
        public String mcVersion;
        public String loaderVersion;
        public String mainClass;
        public List<String> jvmArgs = new ArrayList<>();
        public List<String> classpath = new ArrayList<>();
    }

    private static String metaBase(String kind) {
        return FABRIC.equals(kind) ? "https://meta.fabricmc.net/v2"
                                   : "https://meta.quiltmc.org/v3";
    }

    /** 加载器版本号列表（稳定版优先）。 */
    public static List<String> listLoaderVersions(String kind) throws Exception {
        List<Object> arr = Json.arr(Json.parse(Http.getText(metaBase(kind) + "/versions/loader")));
        List<String> out = new ArrayList<>();
        if (arr != null) for (Object o : arr) {
            Map<String,Object> m = Json.obj(o);
            if (Json.bool(m, "stable")) out.add(Json.str(m, "version"));
        }
        if (out.isEmpty() && arr != null)
            for (Object o : arr) out.add(Json.str(Json.obj(o), "version"));
        return out;
    }

    /** 拉取并安装 profile：下载加载器库，返回可直接用于启动的 ModProfile。 */
    public static ModProfile install(Config cfg, String kind, String mc, String loaderVersion,
                                     Downloader.Listener l) throws Exception {
        String url = metaBase(kind) + "/versions/loader/" + mc + "/" + loaderVersion + "/profile/json";
        Map<String,Object> pj = Json.obj(Json.parse(Http.getText(url)));

        ModProfile mp = new ModProfile();
        mp.kind = kind;
        mp.mcVersion = mc;
        mp.loaderVersion = loaderVersion;
        mp.mainClass = Json.str(pj, "mainClass");

        Map<String,Object> args = Json.obj(pj, "arguments");
        List<Object> jvm = args == null ? null : Json.arr(args, "jvm");
        if (jvm != null) for (Object o : jvm) if (o instanceof String) mp.jvmArgs.add((String) o);

        Path libRoot = cfg.gameDir.resolve("libraries");
        List<Object> libs = Json.arr(pj, "libraries");
        int total = libs == null ? 0 : libs.size(), done = 0;
        if (libs != null) for (Object o : libs) {
            Map<String,Object> lib = Json.obj(o);
            String name = Json.str(lib, "name");
            String base = Json.str(lib, "url");
            if (base == null) base = "https://maven.fabricmc.net/";
            String rel = mavenToPath(name);
            Path target = libRoot.resolve(rel.replace('/', java.io.File.separatorChar));
            l.status("正在下载 " + kind + " 库 (" + (++done) + "/" + total + ")");
            l.progress(done, total);
            String jarUrl = base.endsWith("/") ? base + rel : base + "/" + rel;
            downloadWithResume(jarUrl, target);
            mp.classpath.add(target.toString());
        }
        return mp;
    }

    private static void downloadWithResume(String url, Path target) throws Exception {
        if (Files.exists(target) && target.toFile().length() > 0) return;
        Exception last = null;
        for (int i = 0; i < 6; i++) {
            try { Http.download(url, target, p -> {}); last = null; break; }
            catch (Exception e) { last = e; Thread.sleep(500L * (i + 1)); }
        }
        if (last != null) throw new Exception("加载器库下载失败: " + last.getMessage());
    }

    /** maven 坐标 group:artifact:version[:classifier] → 相对 jar 路径。 */
    public static String mavenToPath(String name) {
        String[] p = name.split(":");
        String group = p[0].replace('.', '/');
        String art = p[1];
        String ver = p[2];
        String cls = p.length > 3 ? "-" + p[3] : "";
        return group + "/" + art + "/" + ver + "/" + art + "-" + ver + cls + ".jar";
    }
}
