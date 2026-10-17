import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

// Builds store/play-icon-512.png (512x512, opaque, full-bleed) for Drive Logger.
// Based on cycle_route_logger/store/tools/StoreImages.java. Comments are ASCII-only.
//
// Same shapes as res/drawable/ic_launcher_foreground.xml (108x108 viewport, safe zone radius 33 around 54,54).
//
//   & "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" store\tools\StoreImages.java
public final class StoreImages {

    static final Color BG = new Color(0x14, 0x21, 0x3D);
    static final Color WHITE = Color.WHITE;
    static final Color ROAD = new Color(0xFF, 0xB3, 0x00);
    static final Color DOT = new Color(0xFF, 0x3B, 0x30);

    public static void main(String[] args) throws IOException {
        File dst = new File("store", "play-icon-512.png");
        ImageIO.write(render(512), "png", dst);
        System.out.println("wrote " + dst.getPath());
    }

    static BufferedImage render(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(BG);
        g.fill(new Rectangle2D.Double(0, 0, size, size));
        double scale = size / 72.0;
        g.translate(-18 * scale, -18 * scale);
        g.scale(scale, scale);
        drawArtwork(g);
        g.dispose();
        return image;
    }

    static void drawArtwork(Graphics2D g) {
        // Road.
        g.setColor(ROAD);
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(36, 77, 72, 77));
        // Car body with window holes.
        GeneralPath body = new GeneralPath();
        body.moveTo(27, 62);
        body.lineTo(27, 54);
        body.quadTo(27, 50, 31, 49);
        body.lineTo(40, 48);
        body.lineTo(46, 41);
        body.quadTo(48, 39, 51, 39);
        body.lineTo(62, 39);
        body.quadTo(65, 39, 67, 41);
        body.lineTo(73, 48);
        body.lineTo(78, 49);
        body.quadTo(82, 50, 82, 54);
        body.lineTo(82, 62);
        body.closePath();
        Area car = new Area(body);
        GeneralPath w1 = new GeneralPath();
        w1.moveTo(47, 47); w1.lineTo(51, 42); w1.lineTo(56, 42); w1.lineTo(56, 47); w1.closePath();
        GeneralPath w2 = new GeneralPath();
        w2.moveTo(59, 47); w2.lineTo(59, 42); w2.lineTo(63, 42); w2.quadTo(64, 42, 65, 43); w2.lineTo(68, 47); w2.closePath();
        car.subtract(new Area(w1));
        car.subtract(new Area(w2));
        g.setColor(WHITE);
        g.fill(car);
        // Wheels (rings).
        for (double cx : new double[] {40, 70}) {
            Area ring = new Area(new Ellipse2D.Double(cx - 7, 58, 14, 14));
            ring.subtract(new Area(new Ellipse2D.Double(cx - 3, 62, 6, 6)));
            g.fill(ring);
        }
        // Recording dot.
        g.setColor(DOT);
        g.fill(new Ellipse2D.Double(63.5, 26.5, 9, 9));
    }
}
