public class LoadAll {
    public static void main(String[] args) {
        String[] libs = {"Lighting64", "PZPopMan64", "PZPathFind64", "PZBullet64", "PZClipper64"};
        for (String l : libs) {
            System.loadLibrary(l);
            System.out.println("LoadAll: " + l + " carregada");
        }
        System.out.println("LoadAll: " + libs.length + " libs carregadas");
    }
}
