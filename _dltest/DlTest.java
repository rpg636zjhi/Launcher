import com.mclauncher.core.Config;
import com.mclauncher.core.Downloader;
import com.mclauncher.core.VersionManager;

import java.nio.file.Paths;
import java.util.List;

public class DlTest {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(Paths.get("D:\\MinecraftLauncher"));
        String want = "26.3";
        List<VersionManager.VersionEntry> list = VersionManager.fetchManifest(cfg.manifestUrl());
        VersionManager.VersionEntry entry = null;
        for (VersionManager.VersionEntry e : list) {
            if (e.id.equals(want)) { entry = e; break; }
        }
        if (entry == null) { System.out.println("清单里找不到 " + want); System.exit(1); }
        System.out.println("找到版本 " + entry.id + " url=" + entry.url);
        try {
            Downloader.Result r = Downloader.install(cfg, entry.id, entry.url, new Downloader.Listener() {
                public void status(String msg) { System.out.println("[状态] " + msg); }
                public void progress(int done, int total) {
                    if (done % 20 == 0 || done == total) System.out.println("[进度] " + done + "/" + total);
                }
            });
            System.out.println("下载成功 mainClass=" + r.mainClass);
        } catch (Throwable t) {
            System.out.println("=== 下载失败，完整异常 ===");
            t.printStackTrace(System.out);
        }
        System.exit(0);
    }
}
