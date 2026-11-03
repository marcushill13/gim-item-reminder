package com.gimreminder;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Images are generated in memory. No screenshot files are saved to disk. */
final class DiscordVisuals
{
    private DiscordVisuals() {}

    static String pngBase64(BufferedImage image)
    {
        return encode(image, "png");
    }

    static String jpegBase64(Image frame, int maxWidth)
    {
        if (frame == null) return null;
        int w = frame.getWidth(null);
        int h = frame.getHeight(null);
        if (w < 1 || h < 1) return null;
        double scale = Math.min(1d, (double) maxWidth / w);
        int outW = Math.max(1, (int) Math.round(w * scale));
        int outH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage output = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = output.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(frame, 0, 0, outW, outH, null);
        }
        finally { graphics.dispose(); }
        return encode(output, "jpg");
    }

    private static String encode(BufferedImage image, String format)
    {
        if (image == null) return null;
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, bytes)) return null;
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
        catch (IOException ex)
        {
            return null;
        }
    }
}
