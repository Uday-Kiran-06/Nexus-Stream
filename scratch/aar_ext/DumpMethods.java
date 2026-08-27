import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

public class DumpMethods {
    public static void main(String[] args) throws Exception {
        URL[] urls = {new URL("file:///C:/Users/ASUS/.gemini/antigravity/scratch/youtube-livestream/bgmi-streamer/scratch/aar_ext/classes/")};
        URLClassLoader cl = new URLClassLoader(urls);
        Class<?> cls = cl.loadClass("com.pedro.encoder.input.gl.render.filters.object.SurfaceFilterRender");
        for (Method m : cls.getDeclaredMethods()) {
            System.out.println(m.toString());
        }
    }
}
