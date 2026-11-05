import java.nio.file.*;
public class LoadProbe {
    public static void main(String[] a) {
        String dir = a.length > 0 ? a[0] : "C:/Program Files (x86)/Steam/steamapps/common/ProjectZomboid";
        String[] names = {"Lighting64","PZBullet64","PZClipper64","PZPathFind64","PZPopMan64","RakNet64","ZNetNoSteam64","fmod","fmodstudio","fmodintegration64","imgui-java64","jassimp64","bink2w64"};
        System.out.println("os.arch=" + System.getProperty("os.arch") + " java=" + System.getProperty("java.version"));
        for (String n : names) {
            String p = Paths.get(dir, n + ".dll").toAbsolutePath().toString();
            try { System.load(p); System.out.println("OK   " + n + " : " + p); }
            catch (Throwable t) { System.out.println("FAIL " + n + " : " + t); }
        }
    }
}
