import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Reads an RGB image and writes out TWO images: both are crops of the
 * SAME input, offset from each other by a whole-pixel (dx, dy), sized to
 * exactly their mutual overlap -- (width - |dx|) x (height - |dy|). There
 * is no padding anywhere in either output; wherever the shift would have
 * revealed a margin with no real corresponding content, that margin is
 * simply left out of both crops rather than filled in. See the class-level
 * comment further down for the exact crop math and sign convention.
 *
 * Two behaviors are borrowed from DeltaWriter.java, at the user's request
 * -- deliberately NOT the rest of that file's editing UI (zoom, menus,
 * quantization controls, HiDPI font scaling), just these two pieces:
 *
 *   1) If no input path is given on the command line, a FileDialog is
 *      shown instead of failing with a usage error -- same pattern as
 *      DeltaWriter.main(): FileDialog(null, "Open Image", FileDialog.LOAD),
 *      and exit(0) rather than proceeding if the dialog is cancelled.
 *      dx/dy are NOT covered by this fallback -- they're still required
 *      command-line arguments either way, since only the input path has
 *      an obvious "let the user pick" substitute.
 *
 *   2) The loaded input is shown in a plain scrollable window before the
 *      crops are computed, so you can visually confirm what actually got
 *      loaded. This is a deliberately simplified version of DeltaWriter's
 *      display -- a JFrame with a JScrollPane around a canvas that paints
 *      the image at its native resolution (scrollable if it's larger than
 *      the window), sized to roughly fit the screen -- rather than
 *      DeltaWriter's zoom-scaled/HiDPI-aware display. If you want the
 *      image visually scaled to fit the window instead of scrolled, that's
 *      a small addition on top of this, just not included here.
 *
 * Output naming/location: both outputs are written into the SAME directory
 * the input was read from (not the current working directory, and not a
 * path you specify), named from the input's filename. Since there are two
 * outputs where DeltaWriter only ever has one, dx/dy are folded into the
 * names too, so repeated runs against the same source at different shifts
 * don't overwrite each other; "ref"/"shift" plus the dx/dy already say
 * what these files are, so there's no added prefix on top of that:
 *   input "/some/dir/photo.png", dx=5, dy=3  ->
 *       "/some/dir/photo_ref_5_3.png", "/some/dir/photo_shift_5_3.png"
 *
 * Convention (unchanged from earlier versions): the SHIFTED output's
 * content sits dx, dy pixels further along than the REFERENCE output's,
 * in the reference's own coordinate frame. Passing the reference output
 * as source1 and the shifted output as source2 to getTranslation.java
 * should recover approximately (dx, dy). Concretely, both crops come
 * straight out of the original input, at two different offsets:
 *   reference[i][j] = input[max(0,dy) + i][max(0,dx) + j]
 *   shifted[i][j]   = input[max(0,-dy) + i][max(0,-dx) + j]
 * both sized (width - |dx|) x (height - |dy|); shifted[i][j] ==
 * reference[i - dy][j - dx] wherever both sides are in range.
 *
 * Operates directly on packed RGB pixels (BufferedImage.getRGB/setRGB) --
 * cropping doesn't change any pixel's color, only which ones are kept, so
 * there's no need to split into channels here. That splitting only has to
 * happen on the estimation side, in getTranslation.java, where the
 * ImageMapper algorithms need a single-channel int[][].
 *
 * Usage:
 *   java getTranslatedImage [inputImagePath] <dx> <dy>
 *
 * If inputImagePath is omitted, a file-open dialog is shown in its place.
 * For now dx/dy are whole pixel values (parsed as integers), matching how
 * getTranslation.java's whole-pixel test case is meant to work.
 */
public class getTranslatedImage
{
    public static void main(String[] args)
    {
        String inputPath;
        int dx, dy;

        if (args.length == 3)
        {
            inputPath = args[0];
            dx = parseIntArg(args[1], "dx");
            dy = parseIntArg(args[2], "dy");
        }
        else if (args.length == 2)
        {
            dx = parseIntArg(args[0], "dx");
            dy = parseIntArg(args[1], "dy");

            // Same pattern as DeltaWriter.main(): no input path given, so
            // ask for one instead of failing with a usage error.
            FileDialog fd = new FileDialog((Frame) null, "Open Image", FileDialog.LOAD);
            fd.setVisible(true);
            if (fd.getFile() == null)
            {
                System.exit(0);
                return;
            }
            inputPath = new File(fd.getDirectory(), fd.getFile()).getPath();
        }
        else
        {
            System.err.println("Usage: java getTranslatedImage [inputImagePath] <dx> <dy>");
            System.err.println("  If inputImagePath is omitted, a file-open dialog is shown instead.");
            System.err.println("  dx, dy are always required -- there's no dialog substitute for those.");
            System.err.println("  Both outputs are written into the same directory as the input file,");
            System.err.println("  named from the input filename; see this file's header comment.");
            System.exit(1);
            return;
        }

        BufferedImage input = readImage(inputPath);
        showImage(input, inputPath);

        int width  = input.getWidth();
        int height = input.getHeight();
        int overlapWidth  = width  - Math.abs(dx);
        int overlapHeight = height - Math.abs(dy);
        if (overlapWidth <= 0 || overlapHeight <= 0)
        {
            System.err.println("Shift (" + dx + "," + dy + ") leaves no overlap at all in a "
                    + width + "x" + height + " image -- nothing to write.");
            return;
        }

        int refXoff   = Math.max(0, dx);
        int refYoff   = Math.max(0, dy);
        int shiftXoff = Math.max(0, -dx);
        int shiftYoff = Math.max(0, -dy);

        BufferedImage reference = crop(input, refXoff, refYoff, overlapWidth, overlapHeight);
        BufferedImage shifted   = crop(input, shiftXoff, shiftYoff, overlapWidth, overlapHeight);

        // Same directory the input came from -- not the current working
        // directory, which may well be somewhere else entirely (this
        // matters most for the file-dialog path, where the input could be
        // anywhere the user browsed to). getAbsoluteFile() first so a
        // bare filename with no directory component (a plain "photo.png"
        // typed on the command line, resolved against the cwd) still
        // resolves to a real parent directory instead of a null one.
        File outputDir = new File(inputPath).getAbsoluteFile().getParentFile();
        String base = baseNameWithoutExtension(inputPath);
        String ext  = extensionOf(inputPath);
        String referenceOutputPath = new File(outputDir, base + "_ref_"   + dx + "_" + dy + "." + ext).getPath();
        String shiftedOutputPath   = new File(outputDir, base + "_shift_" + dx + "_" + dy + "." + ext).getPath();

        writeImage(reference, referenceOutputPath);
        writeImage(shifted, shiftedOutputPath);

        System.out.println("Wrote two " + overlapWidth + "x" + overlapHeight + " images (overlap of a "
                + width + "x" + height + " input shifted by (" + dx + "," + dy + ")):");
        System.out.println("  reference -> \"" + referenceOutputPath + "\"");
        System.out.println("  shifted   -> \"" + shiftedOutputPath + "\"");
    }

    // Simplified stand-in for DeltaWriter's ImageCanvas + JScrollPane +
    // JFrame display: shows the loaded image at native resolution,
    // scrollable if it's bigger than the window, in a window sized to
    // roughly 70% of the screen (same fraction DeltaWriter budgets for
    // its own initial frame). No zoom controls, no HiDPI font-scaling
    // pass -- DeltaWriter needs those because it's a dense editor UI with
    // sliders and menus; this is just a "does this look like the right
    // file" confirmation before the crops get computed and written.
    // Closing the window ends the program (EXIT_ON_CLOSE), same as
    // closing DeltaWriter's last remaining window does.
    private static void showImage(BufferedImage image, String path)
    {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int maxWidth  = (int) (screen.getWidth()  * 0.70);
        int maxHeight = (int) (screen.getHeight() * 0.70);

        JPanel canvas = new JPanel()
        {
            @Override
            public Dimension getPreferredSize()
            {
                return new Dimension(image.getWidth(), image.getHeight());
            }

            @Override
            protected void paintComponent(Graphics g)
            {
                super.paintComponent(g);
                g.drawImage(image, 0, 0, this);
            }
        };
        canvas.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));

        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16);

        JFrame frame = new JFrame("getTranslatedImage  " + path
                + "  (" + image.getWidth() + "x" + image.getHeight() + ")");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        frame.setSize(Math.min(image.getWidth(), maxWidth) + 40, Math.min(image.getHeight(), maxHeight) + 60);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static BufferedImage crop(BufferedImage src, int xoff, int yoff, int w, int h)
    {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                dst.setRGB(col, row, src.getRGB(xoff + col, yoff + row));
            }
        }
        return dst;
    }

    private static BufferedImage readImage(String path)
    {
        BufferedImage img;
        try
        {
            img = ImageIO.read(new File(path));
        }
        catch (IOException e)
        {
            System.err.println("Could not read \"" + path + "\": " + e.getMessage());
            System.exit(1);
            return null;
        }
        if (img == null)
        {
            System.err.println("\"" + path + "\" was not recognized as an image (unsupported format?).");
            System.exit(1);
            return null;
        }
        return img;
    }

    private static void writeImage(BufferedImage img, String path)
    {
        String formatName = formatNameFromPath(path);
        try
        {
            boolean wrote = ImageIO.write(img, formatName, new File(path));
            if (!wrote)
            {
                System.err.println("No writer available for format \"" + formatName + "\" -- try a .png output path.");
                System.exit(1);
            }
        }
        catch (IOException e)
        {
            System.err.println("Could not write \"" + path + "\": " + e.getMessage());
            System.exit(1);
        }
    }

    private static int parseIntArg(String s, String name)
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            System.err.println(name + " must be a whole number (got \"" + s + "\").");
            System.exit(1);
            return 0;
        }
    }

    // Strips any directory components and the extension, leaving just the
    // bare name -- "/some/dir/photo.png" -> "photo".
    private static String baseNameWithoutExtension(String path)
    {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? name : name.substring(0, dot);
    }

    private static String extensionOf(String path)
    {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1) ? "png" : name.substring(dot + 1).toLowerCase();
    }

    // Picks an ImageIO format name from the output path's extension,
    // defaulting to "png" (lossless, always available) when there's no
    // extension or it isn't one ImageIO recognizes as a hint either way --
    // ImageIO.write will still fail cleanly above if the chosen name truly
    // has no writer.
    private static String formatNameFromPath(String path)
    {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1)
        {
            return "png";
        }
        String ext = path.substring(dot + 1).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) return "jpg";
        if (ext.equals("png")) return "png";
        if (ext.equals("bmp")) return "bmp";
        if (ext.equals("gif")) return "gif";
        return "png";
    }
}
