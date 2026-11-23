import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.zip.*;

// Runs inside PojavLauncher ("Execute a .jar") so it can read Pojav-owned 0600 save files.
// Zips the Cobbleverse instance's saves/ and servers.dat to a group-readable file for adb.
public class WorldExport {
    public static void main(String[] args) throws Exception {
        Path base = Paths.get("/storage/emulated/0/Android/data/net.kdt.pojavlaunch/files");
        Path inst = base.resolve("custom_instances/cobbleverse_-_pokemon_adventure_[cobblemon]");
        Path out = base.resolve("cobbleverse_world_export.zip");
        Path log = base.resolve("cobbleverse_world_export.log");
        StringBuilder sb = new StringBuilder();
        int[] n = {0};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            List<Path> roots = new ArrayList<>();
            roots.add(inst.resolve("saves"));
            for (String f : new String[]{"servers.dat", "servers.dat_old"}) if (Files.exists(inst.resolve(f))) roots.add(inst.resolve(f));
            for (Path root : roots) {
                Files.walk(root).filter(Files::isRegularFile).forEach(p -> {
                    try {
                        zos.putNextEntry(new ZipEntry(inst.relativize(p).toString()));
                        Files.copy(p, zos);
                        zos.closeEntry();
                        n[0]++;
                    } catch (IOException e) { sb.append("FAIL ").append(p).append(": ").append(e).append('\n'); }
                });
            }
        }
        sb.append("files=").append(n[0]).append(" size=").append(Files.size(out)).append('\n');
        try { Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rw-rw----")); } catch (Exception e) { sb.append("chmod ").append(e).append('\n'); }
        Files.write(log, sb.toString().getBytes());
        try { Files.setPosixFilePermissions(log, PosixFilePermissions.fromString("rw-rw----")); } catch (Exception ignored) {}
        System.out.println(sb);
        System.out.println("DONE - you can close this window.");
    }
}
