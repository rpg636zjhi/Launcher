package com.mclauncher.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Modrinth 整合包：搜索、版本列表、下载解析 .mrpack 并部署到独立实例目录。 */
public class ModpackManager {

    private static final String API = "https://api.modrinth.com/v2";

    public static class SearchHit {
        public String projectId, title, description, author, iconUrl;
        @Override public String toString() { return title; }
    }

    public static class PackVersion {
        public String versionId, name, mrpackUrl, mcVersion;
    }

    public static class InstalledPack {
        public String instanceId, title, mcVersion, loaderKind, loaderVersion;
        public Path instanceDir;
    }

    /* ---------- 搜索 ---------- */
    public static List<SearchHit> search(String query, int limit) throws Exception {
        String facets = enc("[[\"project_type:modpack\"]]");
        String url = API + "/search?query=" + enc(query) + "&limit=" + limit
                + "&facets=" + facets;
        Map<String,Object> root = Json.obj(Json.parse(Http.getText(url)));
        List<Object> hits = Json.arr(root, "hits");
        List<SearchHit> out = new ArrayList<>();
        if (hits != null) for (Object o : hits) {
            Map<String,Object> h = Json.obj(o);
            SearchHit s = new SearchHit();
            s.projectId = Json.str(h, "project_id");
            s.title = Json.str(h, "title");
            s.description = Json.str(h, "description");
            s.author = Json.str(h, "author");
            s.iconUrl = Json.str(h, "icon_url");
            out.add(s);
        }
        return out;
    }

    public static List<PackVersion> listVersions(String projectId) throws Exception {
        List<Object> arr = Json.arr(Json.parse(Http.getText(API + "/project/" + projectId + "/version")));
        List<PackVersion> out = new ArrayList<>();
        if (arr != null) for (Object o : arr) {
            Map<String,Object> v = Json.obj(o);
            List<Object> gv = Json.arr(v, "game_versions");
            List<Object> files = Json.arr(v, "files");
            String fileUrl = null;
            if (files != null) for (Object f : files) {
                Map<String,Object> fm = Json.obj(f);
                if (Json.bool(fm, "primary")) { fileUrl = Json.str(fm, "url"); break; }
                if (fileUrl == null) fileUrl = Json.str(fm, "url");
            }
            PackVersion pv = new PackVersion();
            pv.versionId = Json.str(v, "id");
            pv.name = Json.str(v, "name");
            pv.mrpackUrl = fileUrl;
            pv.mcVersion = gv != null && !gv.isEmpty() ? gv.get(gv.size() - 1).toString() : "";
            out.add(pv);
        }
        return out;
    }

    /* ---------- 下载并安装 ---------- */
    public static InstalledPack install(Config cfg, SearchHit hit, PackVersion pv,
                                        Downloader.Listener l) throws Exception {
        String instanceId = safeName(hit.title) + "_" + safeName(pv.name);
        Path instanceDir = cfg.gameDir.resolve("instances").resolve(instanceId);
        Files.createDirectories(instanceDir);

        Path mrpack = cfg.gameDir.resolve("instances").resolve(instanceId + ".mrpack");
        l.status("正在下载整合包…");
        Exception last = null;
        for (int i = 0; i < 8; i++) {
            try { Http.download(pv.mrpackUrl, mrpack, p -> {}); last = null; break; }
            catch (Exception e) {
                last = e;
                Thread.sleep(e.getMessage() != null && e.getMessage().contains("403")
                        ? (long) (2000 * Math.pow(2, Math.min(i, 4))) : 600L * (i + 1));
            }
        }
        if (last != null) throw new Exception("整合包下载失败: " + last.getMessage());

        String indexText = null;
        try (ZipFile zf = new ZipFile(mrpack.toFile())) {
            // 1. 找索引
            ZipEntry indexEntry = zf.getEntry("modrinth.index.json");
            if (indexEntry == null) throw new Exception("mrpack 缺少 modrinth.index.json");
            try (InputStream in = zf.getInputStream(indexEntry)) {
                indexText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            // 2. 复制 overrides / client-overrides
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                String prefix = null;
                if (n.startsWith("client-overrides/")) prefix = "client-overrides/";
                else if (n.startsWith("overrides/")) prefix = "overrides/";
                if (prefix == null || e.isDirectory()) continue;
                String rel = n.substring(prefix.length());
                if (rel.isEmpty()) continue;
                Path out = instanceDir.resolve(rel).normalize();
                if (!out.startsWith(instanceDir)) continue;
                Files.createDirectories(out.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }

        Map<String,Object> idx = Json.obj(Json.parse(indexText));
        Map<String,Object> deps = Json.obj(idx, "dependencies");
        InstalledPack pack = new InstalledPack();
        pack.instanceId = instanceId;
        pack.title = hit.title;
        pack.instanceDir = instanceDir;
        pack.mcVersion = Json.str(deps, "minecraft");
        String fabric = Json.str(deps, "fabric-loader");
        String quilt = Json.str(deps, "quilt-loader");
        if (fabric != null) { pack.loaderKind = ModLoaderManager.FABRIC; pack.loaderVersion = fabric; }
        else if (quilt != null) { pack.loaderKind = ModLoaderManager.QUILT; pack.loaderVersion = quilt; }
        else { pack.loaderKind = null; pack.loaderVersion = null; }

        // 3. 下载各文件（mods 等），跳过仅服务端
        List<Object> files = Json.arr(idx, "files");
        int total = files == null ? 0 : files.size(), done = 0;
        if (files != null) for (Object o : files) {
            Map<String,Object> f = Json.obj(o);
            Map<String,Object> env = Json.obj(f, "env");
            if (env != null && "disabled".equals(Json.str(env, "client"))) continue;
            String rel = Json.str(f, "path");
            Path target = instanceDir.resolve(rel).normalize();
            if (!target.startsWith(instanceDir)) continue;
            if (!Files.exists(target)) {
                List<Object> downloads = Json.arr(f, "downloads");
                l.status("正在下载 mod 文件 (" + (++done) + "/" + total + ")");
                l.progress(done, total);
                Exception fl = null;
                if (downloads != null) for (Object d : downloads) {
                    try {
                        Http.download(d.toString(), target, p -> {});
                        fl = null; break;
                    } catch (Exception e) { fl = e; }
                }
                if (fl != null) throw new Exception("mod 文件下载失败: " + rel + " " + fl.getMessage());
            }
        }
        Files.deleteIfExists(mrpack);
        return pack;
    }

    private static String safeName(String s) {
        if (s == null) return "pack";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
