import com.mclauncher.core.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

public class LaunchTest {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(java.nio.file.Paths.get("D:\\MinecraftLauncher"));
        String want = "26.3";
        List<VersionManager.VersionEntry> list = VersionManager.fetchManifest(cfg.manifestUrl());
        VersionManager.VersionEntry entry = null;
        for (VersionManager.VersionEntry e : list) if (e.id.equals(want)) { entry = e; break; }

        // 复用已安装文件（全部已下，install 会跳过并重建 classpath）
        Downloader.Result r = Downloader.install(cfg, entry.id, entry.url, new Downloader.Listener() {
            public void status(String m) {}
            public void progress(int d, int t) {}
        });

        String name = "Steve";
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
        Account acc = new Account(name, "offline", uuid, "0");

        GameLauncher.LaunchCommand lc = GameLauncher.buildCommand(cfg, r, acc);
        System.out.println("命令参数数: " + lc.command.size());
        Process p = GameLauncher.launch(lc);

        final StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line; int n = 0;
                while ((line = br.readLine()) != null) { if (n++ < 200) sb.append(line).append('\n'); }
            } catch (Exception ignore) {}
        });
        reader.setDaemon(true); reader.start();

        long deadline = System.currentTimeMillis() + 45000;
        boolean alive = true;
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive()) { alive = false; break; }
            Thread.sleep(1000);
        }
        String out = sb.toString();
        System.out.println("===== 游戏进程45秒后存活: " + (alive ? "是" : "否(退出码=" + p.exitValue() + ")") + " =====");
        System.out.println("===== 进程输出（前若干行）=====");
        System.out.println(out.length() > 6000 ? out.substring(0, 6000) : out);
        if (alive) p.destroyForcibly();
        System.exit(0);
    }
}
