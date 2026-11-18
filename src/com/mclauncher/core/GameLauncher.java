package com.mclauncher.core;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 拼装并启动游戏进程：JVM 参数、classpath、版本游戏参数、mod 加载器与皮肤站注入。 */
public class GameLauncher {

    public interface LogSink { void line(String line); }

    private final Config cfg;
    private final Account account;
    private final Downloader.Result base;
    private final ModLoaderManager.ModProfile mod;
    private final Path gameDirectory;

    public GameLauncher(Config cfg, Account account, Downloader.Result base,
                        ModLoaderManager.ModProfile mod, Path gameDirectory) {
        this.cfg = cfg;
        this.account = account;
        this.base = base;
        this.mod = mod;
        this.gameDirectory = gameDirectory != null ? gameDirectory : cfg.gameDir;
    }

    public Process start(LogSink sink) throws Exception {
        List<String> cmd = buildCommand();
        Files.createDirectories(gameDirectory);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(gameDirectory.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();

        // 读取并转发日志（UTF-8）
        Thread reader = new Thread(() -> {
            try (var r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) if (sink != null) sink.line(line);
            } catch (Exception ignore) {}
        }, "game-log");
        reader.setDaemon(true);
        reader.start();
        return p;
    }

    List<String> buildCommand() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava());

        // JVM：内存
        cmd.add("-Xms" + Math.max(512, cfg.memoryMb / 2) + "M");
        cmd.add("-Xmx" + cfg.memoryMb + "M");

        // 皮肤站 authlib-injector
        if ("skinstation".equals(account.type) && account.skinServer != null) {
            Path injector = AuthlibInjector.ensure(cfg);
            cmd.add("-javaagent:" + injector.toString() + "=" + account.skinServer);
            cmd.add("-Dauthlibinjector.side=client");
        }

        // classpath
        List<String> cp = new ArrayList<>();
        Path clientJar = cfg.gameDir.resolve("versions").resolve(currentVersionId())
                                    .resolve(currentVersionId() + ".jar");
        cp.add(clientJar.toString());
        cp.addAll(base.classpath);
        if (mod != null) cp.addAll(mod.classpath);
        String cpJoined = String.join(File.pathSeparator, cp);

        // 版本 JSON 的 JVM 参数（--enable-native-access/--add-exports/-Djava.library.path/-cp 等）
        boolean hasJvmArgs = appendJvmArgs(cmd, cpJoined);
        if (!hasJvmArgs) {
            // 兜底：老版本无 arguments.jvm 时才手动补
            cmd.add("-Djava.library.path=" + base.nativesDir.toString());
            cmd.add("-Dorg.lwjgl.librarypath=" + base.nativesDir.toString());
            cmd.add("-cp");
            cmd.add(cpJoined);
        }

        // mod 加载器额外 JVM 参数
        if (mod != null) {
            Map<String,String> sub = placeholderMap();
            for (String a : mod.jvmArgs) cmd.add(substitute(a, sub));
        }

        // mainClass
        cmd.add(mod != null && mod.mainClass != null ? mod.mainClass : base.mainClass);

        // 游戏参数
        appendGameArgs(cmd);
        return cmd;
    }

    /** 追加版本 JSON arguments.jvm（展开 ${classpath} 与占位符）；无 jvm 段时返回 false。 */
    private boolean appendJvmArgs(List<String> cmd, String classpath) {
        Map<String,Object> args = Json.obj(base.versionJson, "arguments");
        List<Object> jvm = args == null ? null : Json.arr(args, "jvm");
        if (jvm == null || jvm.isEmpty()) return false;
        Map<String,String> sub = placeholderMap();
        for (Object o : jvm) {
            if (o instanceof String) {
                cmd.add(expand((String) o, sub, classpath));
            } else if (o instanceof Map) {
                Map<String,Object> block = Json.obj(o);
                if (blockRulesAllow(block)) {
                    Object val = block.get("value");
                    if (val instanceof String) cmd.add(expand((String) val, sub, classpath));
                    else if (val instanceof List)
                        for (Object v : (List<?>) val) if (v instanceof String)
                            cmd.add(expand((String) v, sub, classpath));
                }
            }
        }
        return true;
    }

    private static String expand(String s, Map<String,String> sub, String classpath) {
        return substitute(s.replace("${classpath}", classpath), sub);
    }

    private String currentVersionId() {
        String id = Json.str(base.versionJson, "id");
        return id != null ? id : cfg.selectedVersion;
    }

    private void appendGameArgs(List<String> cmd) {
        Map<String,String> sub = placeholderMap();
        Map<String,Object> args = Json.obj(base.versionJson, "arguments");
        List<Object> game = args == null ? null : Json.arr(args, "game");
        if (game != null) {
            for (Object o : game) {
                if (o instanceof String) {
                    cmd.add(substitute((String) o, sub));
                } else if (o instanceof Map) {
                    // 带规则的参数块，仅评估允许的字符串
                    Map<String,Object> block = Json.obj(o);
                    if (blockRulesAllow(block)) {
                        Object val = block.get("value");
                        if (val instanceof String) cmd.add(substitute((String) val, sub));
                        else if (val instanceof List)
                            for (Object v : (List<?>) val) if (v instanceof String)
                                cmd.add(substitute((String) v, sub));
                    }
                }
            }
        } else {
            // 旧版 minecraftArguments 空格分隔
            String legacy = Json.str(base.versionJson, "minecraftArguments");
            if (legacy != null) for (String a : legacy.split(" ")) cmd.add(substitute(a, sub));
        }
    }

    private boolean blockRulesAllow(Map<String,Object> block) {
        List<Object> rules = Json.arr(block, "rules");
        if (rules == null) return true;
        for (Object o : rules) {
            Map<String,Object> rule = Json.obj(o);
            boolean disallow = "disallow".equals(Json.str(rule, "action"));
            Map<String,Object> os = Json.obj(rule, "os");
            boolean osMatch = true;
            if (os != null) {
                String name = Json.str(os, "name");
                String arch = Json.str(os, "arch");
                if (name != null && !name.equals(Downloader.detectOs())) osMatch = false;
                if (arch != null && !arch.equals(Downloader.detectArch())) osMatch = false;
            }
            Map<String,Object> features = Json.obj(rule, "features");
            boolean featMatch = true;
            if (features != null) {
                for (String k : features.keySet()) {
                    boolean want = Boolean.TRUE.equals(features.get(k));
                    if (want != featureEnabled(k)) { featMatch = false; break; }
                }
            }
            boolean match = osMatch && featMatch;
            if (match && disallow) return false;   // 命中 disallow → 禁用整块
            if (!match && !disallow) return false; // allow 未命中 → 禁用整块
        }
        return true;
    }

    /** 启动器当前启用的 features：仅自定义分辨率；quick play / demo 一律不启用。 */
    private boolean featureEnabled(String feature) {
        if ("has_custom_resolution".equals(feature))
            return cfg.windowWidth > 0 && cfg.windowHeight > 0;
        return false; // is_demo_user / has_quick_plays_support / is_quick_play_* 均不启用
    }

    private Map<String,String> placeholderMap() {
        Map<String,String> m = new LinkedHashMap<>();
        m.put("auth_player_name", account.name);
        m.put("version_name", currentVersionId());
        m.put("game_directory", gameDirectory.toString());
        m.put("assets_root", cfg.gameDir.resolve("assets").toString());
        m.put("assets_index_name", base.assetIndexId == null ? "" : base.assetIndexId);
        m.put("auth_uuid", account.uuid);
        m.put("auth_access_token", account.accessToken);
        m.put("auth_xuid", "");
        m.put("clientid", "");
        m.put("user_type", "msa".equals(account.type) ? "msa" : "mojang");
        m.put("version_type", Json.str(base.versionJson, "type"));
        m.put("user_properties", "{}");
        m.put("resolution_width", String.valueOf(cfg.windowWidth));
        m.put("resolution_height", String.valueOf(cfg.windowHeight));
        m.put("launcher_name", "xycLauncher");
        m.put("launcher_version", "2.0");
        m.put("classpath_separator", File.pathSeparator);
        m.put("library_directory", cfg.gameDir.resolve("libraries").toString());
        m.put("natives_directory", base.nativesDir.toString());
        return m;
    }

    private static String substitute(String s, Map<String,String> m) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf('$', i);
            if (start < 0) { out.append(s.substring(i)); break; }
            int end = s.indexOf('}', start);
            if (end < 0) { out.append(s.substring(i)); break; }
            String key = s.substring(start + 2, end);
            out.append(s, i, start);
            String val = m.get(key);
            if (val != null) out.append(val);
            i = end + 1;
        }
        return out.toString();
    }

    private String resolveJava() throws Exception {
        // 1. 下拉里明确指定的 Java 版本优先（Java 25 / 固定 17），确保"选了什么就用什么"
        int required = Integer.parseInt(base.javaVersionMajor);
        Downloader.Listener silent = new Downloader.Listener() {
            public void status(String m) {}
            public void progress(int d, int t) {}
        };
        if (cfg.javaMajor > 0) return JavaRuntime.ensure(cfg, cfg.javaMajor, silent).toString();
        // 2. 未指定版本时才用手动配置的 java 路径作兜底
        if (cfg.javaPath != null && !cfg.javaPath.isEmpty() && Files.exists(Path.of(cfg.javaPath)))
            return cfg.javaPath;
        // 3. 自动：最新版或按游戏要求版本
        return JavaRuntime.ensureLatest(cfg, required, silent).toString();
    }
}
