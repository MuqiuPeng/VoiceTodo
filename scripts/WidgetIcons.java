import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Cuts the app icon (art/icon.jpg, black on white) out of its background and
 * writes a dark and a white copy on transparency for the widget:
 *
 *   java scripts/WidgetIcons.java art/icon.jpg app/src/main/res/drawable-nodpi
 *
 * Darkness becomes opacity, so the anti-aliased edges stay smooth.
 */
public class WidgetIcons {
    static final int SIZE = 384;

    public static void main(String[] args) throws Exception {
        BufferedImage src = ImageIO.read(new File(args[0]));
        int w = src.getWidth(), h = src.getHeight();
        int[] alpha = new int[w * h];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int lum = (((p >> 16) & 255) * 299 + ((p >> 8) & 255) * 587 + (p & 255) * 114) / 1000;
                // Paper (≥ 235) is fully clear, ink (≤ 45) fully opaque.
                int a = Math.max(0, Math.min(255, (235 - lum) * 255 / (235 - 45)));
                alpha[y * w + x] = a;
                if (a > 8) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
        }
        // A square around the drawing with a little margin.
        int side = (int) (Math.max(maxX - minX, maxY - minY) * 1.06);
        int cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        write(alpha, w, h, cx - side / 2, cy - side / 2, side, 0x1F1F1F, new File(args[1], "widget_mark_dark.png"));
        write(alpha, w, h, cx - side / 2, cy - side / 2, side, 0xFFFFFF, new File(args[1], "widget_mark_light.png"));
    }

    static void write(int[] alpha, int w, int h, int x0, int y0, int side, int rgb, File out) throws Exception {
        BufferedImage img = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                int sx = x0 + x, sy = y0 + y;
                int a = sx >= 0 && sy >= 0 && sx < w && sy < h ? alpha[sy * w + sx] : 0;
                img.setRGB(x, y, (a << 24) | rgb);
            }
        }
        // Halve while it is more than twice the target, then one smooth final step.
        while (img.getWidth() / 2 >= SIZE) img = scale(img, img.getWidth() / 2);
        ImageIO.write(scale(img, SIZE), "png", out);
    }

    static BufferedImage scale(BufferedImage img, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
