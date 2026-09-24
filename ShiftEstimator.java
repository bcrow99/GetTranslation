import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

/**
 * A GUI version of getTranslation.java (see that file's own header comment
 * for the full rundown of what it prints and why) -- same status vocabulary,
 * same getTranslation()/getRefinedTranslation() calls, same report shape,
 * but this one also opens a window showing the two luminance channels
 * overlaid in false color so misalignment is visible by eye, not just as
 * numbers: the REFERENCE image renders in GRAY, the SHIFTED image renders
 * in YELLOW. Concretely, per pixel: blue = the reference luminance alone
 * (yellow has no blue component, so only the reference ever contributes to
 * it); red and green both = max(reference luminance, shifted luminance)
 * (both gray and yellow carry equal red and green). Where the two images
 * agree, red/green/blue all end up close to the same value -- neutral
 * gray. Where the shifted image has real signal the reference doesn't (a
 * misaligned edge, most often), red and green jump above blue and that
 * pixel reads as yellow -- so misalignment shows up as yellow fringing
 * against an otherwise gray picture, which is the whole point: you can
 * visually confirm (or contradict) whatever the printed x/y estimate below
 * claims. max() is commutative, so which image is conceptually "on top"
 * doesn't change the result -- no alpha compositing or draw order is
 * involved.
 *
 * The window has a View menu and a Parameters menu, both modeled directly
 * on DeltaWriter.java's own equivalents:
 *
 *   - View: Zoom In / Zoom Out / Fit / 100%, same accelerators
 *     (Ctrl+= / Ctrl+- / Ctrl+0 / Ctrl+1) and the same Ctrl+scroll-wheel
 *     zoom-at-cursor behavior as DeltaWriter's own View menu. The window
 *     also OPENS already fit to the screen, the same way DeltaWriter sizes
 *     its initial window against 70% of the screen dimensions.
 *
 *   - Parameters: "Shift" opens a small panel with two spinners (a text
 *     field plus up/down buttons -- javax.swing.JSpinner's own default
 *     look) for the X and Y shift-level hints (0-10, the same
 *     shrinkLevelX/shrinkLevelY count-of-halvings hint getTranslation.java
 *     and ShiftDetector.java both already take on the command line), fed
 *     live into TranslateMapper.getRefinedTranslation(lum1, lum2,
 *     shrinkLevelX, shrinkLevelY) -- that hinted estimate is now always
 *     shown in the report, not only when a hint was given on the command
 *     line, since the panel gives you a live one either way. "Smooth"
 *     opens a panel with two sliders (0-10) for TranslateMapper's own
 *     bilateralSmooth()/anisotropicSmooth() -- exactly DeltaWriter's own
 *     Smooth/Smooth2 sliders, just combined into one panel here rather
 *     than two separate menu items, per how this file was asked for.
 *     Changing either slider re-smooths BOTH luminance channels (same
 *     smoothing levels applied to each), rebuilds the overlay from the
 *     smoothed channels, and reruns every estimate against them -- so you
 *     can see, in the same window, both what smoothing does to the visual
 *     overlay and what it does to the numbers.
 *
 * A deliberate copy, not a modification: getTranslation.java stays exactly
 * as it is. This file duplicates its image-loading, luminance-conversion,
 * and status-reporting logic rather than sharing it, the same way
 * ShiftDetector.java duplicates rather than reuses getTranslation.java's
 * own toLuminance() -- both are small, standalone drivers, and per this
 * project's established practice that's preferred over coupling two
 * independent command-line tools together.
 *
 * File selection mirrors ShiftDetector.java: when no image paths are given
 * on the command line, java.awt.FileDialog prompts for both, the same
 * class and usage pattern ShiftDetector.java (and ImageTranslater.java's
 * own Open menu item) already use. Unlike ShiftDetector, though, this
 * program ALSO accepts image paths directly on the command line, exactly
 * the way getTranslation.java does -- so the optional shrinkLevelX/
 * shrinkLevelY/preShrinkLevel hints need to work whether or not paths were
 * given. The two are told apart by whether the first argument parses as a
 * number: real file paths never do, so if args[0] is numeric (or there are
 * no args at all), this falls back to file dialogs and treats every
 * argument as a hint; otherwise the first two arguments are taken as the
 * image paths and everything after them is the hint, exactly matching
 * getTranslation.java's own arg-count scheme shifted by two. (The
 * shrinkLevelX/shrinkLevelY given this way, if any, only seed the Shift
 * panel's starting values -- they're live-editable from the GUI from then
 * on.)
 *
 * Usage:
 *   java ShiftEstimator
 *   java ShiftEstimator <preShrinkLevel>
 *   java ShiftEstimator <shrinkLevelX> <shrinkLevelY>
 *   java ShiftEstimator <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>
 *   java ShiftEstimator <originalImagePath> <translatedImagePath>
 *   java ShiftEstimator <originalImagePath> <translatedImagePath> <preShrinkLevel>
 *   java ShiftEstimator <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY>
 *   java ShiftEstimator <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>
 *
 * (The first four forms fall back to a file dialog for both images; the
 * last four give them directly. preShrinkLevel, if given, is fixed for the
 * session -- there's no live control for it, only shrinkLevelX/
 * shrinkLevelY get one, per what this file was asked for.)
 */
public class ShiftEstimator
{
    // Same reasoning as getTranslation.java's own copy of this constant.
    private static final int RECOMMENDED_MIN_DIMENSION = 128;

    public static void main(String[] args)
    {
        boolean pathsGiven = (args.length >= 2 && !isNumeric(args[0]));

        if (!pathsGiven && args.length == 1 && !isNumeric(args[0]))
        {
            System.err.println("A single non-numeric argument isn't a valid form -- ");
            System.err.println("if you're giving image paths, both are required.");
            printUsageAndExit();
        }

        String originalPath;
        String translatedPath;
        String[] hintArgs;

        if (pathsGiven)
        {
            originalPath = args[0];
            translatedPath = args[1];
            hintArgs = Arrays.copyOfRange(args, 2, args.length);
        }
        else
        {
            hintArgs = args;
            originalPath = null;
            translatedPath = null;
        }

        if (hintArgs.length > 3)
        {
            printUsageAndExit();
        }

        // Same arg-count scheme as getTranslation.java: 1 extra = preShrinkLevel
        // alone, 2 = shrinkLevelX + shrinkLevelY, 3 = both.
        boolean haveHint = (hintArgs.length == 2 || hintArgs.length == 3);
        boolean havePreShrink = (hintArgs.length == 1 || hintArgs.length == 3);

        int shrinkLevelX = 0, shrinkLevelY = 0, preShrinkLevel = 0;
        if (haveHint)
        {
            shrinkLevelX = (int) parseDoubleArg(hintArgs[0], "shrinkLevelX");
            shrinkLevelY = (int) parseDoubleArg(hintArgs[1], "shrinkLevelY");
        }
        if (havePreShrink)
        {
            preShrinkLevel = (int) parseDoubleArg(hintArgs[hintArgs.length - 1], "preShrinkLevel");
        }
        // The Shift panel's spinners only go 0-10 -- clamp a command-line
        // hint into that same range so a wildly out-of-range value on the
        // command line doesn't disagree with what the panel can show.
        shrinkLevelX = clampToSpinnerRange(shrinkLevelX);
        shrinkLevelY = clampToSpinnerRange(shrinkLevelY);

        if (!pathsGiven)
        {
            originalPath = chooseFile("Select the ORIGINAL (reference) image");
            if (originalPath == null)
            {
                System.exit(0);
                return;
            }
            translatedPath = chooseFile("Select the TRANSLATED (shifted) image");
            if (translatedPath == null)
            {
                System.exit(0);
                return;
            }
        }

        BufferedImage original = readImage(originalPath);
        BufferedImage translated = readImage(translatedPath);

        int width = original.getWidth();
        int height = original.getHeight();
        if (translated.getWidth() != width || translated.getHeight() != height)
        {
            System.err.println("Image size mismatch: \"" + originalPath + "\" is " + width + "x" + height
                    + ", \"" + translatedPath + "\" is " + translated.getWidth() + "x" + translated.getHeight()
                    + ". Both images must be the same size.");
            System.exit(1);
            return;
        }

        String warning = "";
        if (Math.min(width, height) < RECOMMENDED_MIN_DIMENSION)
        {
            warning = "Warning: shorter side is " + Math.min(width, height)
                    + "px. getRefinedTranslation() pyramids down toward 64x64, so an image this small "
                    + "may only get one refinement round (or none) -- results may be less meaningful.\n\n";
        }

        int[][] lum1 = toLuminance(original);
        int[][] lum2 = toLuminance(translated);

        String initialReport = buildReport(originalPath, translatedPath, width, height, warning,
                lum1, lum2, shrinkLevelX, shrinkLevelY, havePreShrink, preShrinkLevel);
        System.out.print(initialReport);

        String finalOriginalPath = originalPath;
        String finalTranslatedPath = translatedPath;
        int finalShrinkLevelX = shrinkLevelX;
        int finalShrinkLevelY = shrinkLevelY;
        boolean finalHavePreShrink = havePreShrink;
        int finalPreShrinkLevel = preShrinkLevel;
        String finalWarning = warning;
        SwingUtilities.invokeLater(() -> new EstimatorWindow(finalOriginalPath, finalTranslatedPath,
                width, height, finalWarning, lum1, lum2,
                finalShrinkLevelX, finalShrinkLevelY, finalHavePreShrink, finalPreShrinkLevel).show());
    }

    private static int clampToSpinnerRange(int v)
    {
        if (v < 0) return 0;
        if (v > 10) return 10;
        return v;
    }

    private static void printUsageAndExit()
    {
        System.err.println("Usage: java ShiftEstimator");
        System.err.println("   or: java ShiftEstimator <preShrinkLevel>");
        System.err.println("   or: java ShiftEstimator <shrinkLevelX> <shrinkLevelY>");
        System.err.println("   or: java ShiftEstimator <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>");
        System.err.println("   or: java ShiftEstimator <originalImagePath> <translatedImagePath>");
        System.err.println("   or: java ShiftEstimator <originalImagePath> <translatedImagePath> <preShrinkLevel>");
        System.err.println("   or: java ShiftEstimator <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY>");
        System.err.println("   or: java ShiftEstimator <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>");
        System.err.println("(The first four forms open a file dialog for both images.)");
        System.exit(1);
    }

    private static boolean isNumeric(String s)
    {
        try
        {
            Double.parseDouble(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    private static double parseDoubleArg(String s, String name)
    {
        try
        {
            return Double.parseDouble(s);
        }
        catch (NumberFormatException e)
        {
            System.err.println(name + " must be a number (got \"" + s + "\").");
            System.exit(1);
            return 0;
        }
    }

    private static String chooseFile(String title)
    {
        FileDialog fd = new FileDialog((Frame) null, title, FileDialog.LOAD);
        fd.setVisible(true);
        if (fd.getFile() == null)
        {
            return null;
        }
        return new File(fd.getDirectory(), fd.getFile()).getPath();
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

    // Standard ITU-R BT.601 luma weights, rounded to nearest -- same as
    // getTranslation.java's and ShiftDetector.java's own copies, duplicated
    // here for the same reason theirs are duplicated from each other (see
    // this file's header comment).
    private static int[][] toLuminance(BufferedImage img)
    {
        int width  = img.getWidth();
        int height = img.getHeight();
        int[][] lum = new int[height][width];
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                int rgb = img.getRGB(col, row);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                lum[row][col] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return lum;
    }

    // TranslateMapper.bilateralSmooth()/anisotropicSmooth() both operate on
    // a flat, row-major int[] (see their own comments in TranslateMapper.java),
    // not the int[][] the rest of this file uses -- these two convert
    // between the two shapes.
    private static int[] flatten(int[][] a)
    {
        int h = a.length, w = a[0].length;
        int[] f = new int[h * w];
        for (int row = 0; row < h; row++)
        {
            System.arraycopy(a[row], 0, f, row * w, w);
        }
        return f;
    }

    private static int[][] unflatten(int[] f, int w, int h)
    {
        int[][] a = new int[h][w];
        for (int row = 0; row < h; row++)
        {
            System.arraycopy(f, row * w, a[row], 0, w);
        }
        return a;
    }

    // Reference -> gray, shifted -> yellow. See this file's header comment
    // for the full reasoning: blue comes from the reference alone; red and
    // green both come from whichever of the two images has more signal at
    // that pixel, so agreement reads as gray and a shifted-only edge reads
    // as yellow.
    private static BufferedImage buildOverlay(int[][] referenceLum, int[][] shiftedLum)
    {
        int height = referenceLum.length;
        int width  = referenceLum[0].length;
        BufferedImage overlay = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                int refV   = clamp255(referenceLum[row][col]);
                int shiftV = clamp255(shiftedLum[row][col]);
                int redGreen = Math.max(refV, shiftV);
                overlay.setRGB(col, row, (redGreen << 16) | (redGreen << 8) | refV);
            }
        }
        return overlay;
    }

    // toLuminance() should already stay within 0-255 (it's a weighted
    // average of three 0-255 channels), but clamping here costs nothing and
    // guards buildOverlay() against ever packing a bad value into the RGB
    // word if that ever stops being true.
    private static int clamp255(int v)
    {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    // Same status vocabulary as getTranslation.java's own describeStatus(),
    // with one intentional difference: the boundary text here says "+/-.5",
    // not "+/-1". getTranslation.java is left untouched per this file's own
    // instructions, so its copy still says "+/-1" -- but TranslateMapper.
    // getTranslation()'s actual boundary is +/-.5 pixels (see that method's
    // own BUG FIX note on the translate()-domain calibration fix), so this
    // copy describes what the method genuinely does rather than reproducing
    // getTranslation.java's now-stale wording.
    private static String describeStatus(int status)
    {
        switch (status)
        {
            case 0: return "zero increment on the very first pass -- images already matched";
            case 1: return "converged: both axes' increments fell below 1% of their own initial increment";
            case 2: return "did not converge within the internal iteration limit (inconclusive, not necessarily a failure -- more resolution or more iterations might resolve it)";
            case 3: return "indeterminate: local gradient structure too degenerate to resolve (inconclusive -- more resolution might resolve it)";
            case 4: return "stopped: only X reached translate()'s +/-.5 pixel trust boundary (expected for a shift bigger than that, not itself a failure)";
            case 5: return "stopped: only Y reached translate()'s +/-.5 pixel trust boundary (expected for a shift bigger than that, not itself a failure)";
            case 6: return "stopped: both X and Y reached translate()'s +/-.5 pixel trust boundary (expected for a shift bigger than that, not itself a failure)";
            default: return "unrecognized status";
        }
    }

    // Split in two so EstimatorWindow can recompute only what a given
    // Parameters change actually affects, instead of rerunning everything
    // on every tick of every slider/spinner (see EstimatorWindow's own
    // recomputeSmoothingDependent()/recomputeHintDependent()):
    //
    //   - buildStaticReport(): the warning, image size line, the plain
    //     getTranslation() call, and the no-hint getRefinedTranslation()
    //     call. None of this depends on the Shift panel's hint values, only
    //     on the (possibly smoothed) luminance data -- so it only needs to
    //     be rebuilt when the Smooth panel changes, never when only the
    //     Shift panel does.
    //   - buildHintedReport(): the hinted getRefinedTranslation() call
    //     (always shown -- see below), plus the pre-shrink-combined variant
    //     when havePreShrink was set at startup. This is the only part that
    //     depends on shrinkLevelX/shrinkLevelY, so it's the only part a
    //     Shift-panel-only change needs to rerun; a Smooth-panel change
    //     still has to rerun it too, since the luminance data it estimates
    //     against changed.
    //
    // buildReport() below just concatenates the two, unchanged from before,
    // for main()'s one-time startup print, where there's no reason to keep
    // them separate.
    //
    // Unlike getTranslation.java, the hinted getRefinedTranslation() variant
    // is ALWAYS included, not only when a hint was given on the command
    // line -- the Shift panel gives every session a live, editable hint
    // (starting at 0,0 if none was given), so there's always one to show.
    // preShrinkLevel stays command-line-only (see this file's header
    // comment), so that variant only appears when havePreShrink was set at
    // startup.
    private static String buildStaticReport(String originalPath, String translatedPath, int width, int height,
            String warning, int[][] lum1, int[][] lum2)
    {
        StringBuilder report = new StringBuilder();
        report.append(warning);
        report.append("Images: ").append(width).append("x").append(height)
                .append("  (\"").append(originalPath).append("\" vs \"").append(translatedPath).append("\")\n\n");

        double[] simple = TranslateMapper.getTranslation(lum1, lum2);
        report.append("TranslateMapper.getTranslation() [subpixel-only, single pass]:\n");
        report.append("    status = ").append((int) simple[0]).append(" (").append(describeStatus((int) simple[0])).append(")\n");
        report.append("    x = ").append(simple[1]).append("   y = ").append(simple[2]).append("\n\n");

        long t0 = System.currentTimeMillis();
        double[] refined = TranslateMapper.getRefinedTranslation(lum1, lum2);
        long t1 = System.currentTimeMillis();
        report.append("TranslateMapper.getRefinedTranslation() [pyramided, iterative]:\n");
        report.append("    x = ").append(refined[0]).append("   y = ").append(refined[1])
                .append("   [").append(t1 - t0).append(" ms]\n\n");

        return report.toString();
    }

    private static String buildHintedReport(int[][] lum1, int[][] lum2,
            int shrinkLevelX, int shrinkLevelY, boolean havePreShrink, int preShrinkLevel)
    {
        StringBuilder report = new StringBuilder();

        long t2 = System.currentTimeMillis();
        double[] hinted = TranslateMapper.getRefinedTranslation(lum1, lum2, shrinkLevelX, shrinkLevelY);
        long t3 = System.currentTimeMillis();
        report.append("TranslateMapper.getRefinedTranslation() [hinted: shrinkLevelX=").append(shrinkLevelX)
                .append(" shrinkLevelY=").append(shrinkLevelY).append("]:\n");
        report.append("    x = ").append(hinted[0]).append("   y = ").append(hinted[1])
                .append("   [").append(t3 - t2).append(" ms]\n");

        if (havePreShrink)
        {
            report.append("\n");
            long t4 = System.currentTimeMillis();
            double[] hintedPreShrunk = TranslateMapper.getRefinedTranslation(lum1, lum2,
                    shrinkLevelX, shrinkLevelY, preShrinkLevel);
            long t5 = System.currentTimeMillis();
            report.append("TranslateMapper.getRefinedTranslation() [hinted: shrinkLevelX=").append(shrinkLevelX)
                    .append(" shrinkLevelY=").append(shrinkLevelY).append(", pre-shrunk: preShrinkLevel=").append(preShrinkLevel).append("]:\n");
            report.append("    x = ").append(hintedPreShrunk[0]).append("   y = ").append(hintedPreShrunk[1])
                    .append("   [").append(t5 - t4).append(" ms]\n");
        }

        return report.toString();
    }

    private static String buildReport(String originalPath, String translatedPath, int width, int height,
            String warning, int[][] lum1, int[][] lum2,
            int shrinkLevelX, int shrinkLevelY, boolean havePreShrink, int preShrinkLevel)
    {
        return buildStaticReport(originalPath, translatedPath, width, height, warning, lum1, lum2)
                + buildHintedReport(lum1, lum2, shrinkLevelX, shrinkLevelY, havePreShrink, preShrinkLevel);
    }

    // =========================================================================
    // GUI -- View menu (Zoom In/Out/Fit/100%, Ctrl+scroll zoom-at-cursor) and
    // initial fit-to-screen sizing modeled on DeltaWriter.java's own; the
    // Parameters menu (Shift, Smooth) is new to this file. See the class
    // header comment for the overall design.
    // =========================================================================

    private static class EstimatorWindow
    {
        private final String originalPath, translatedPath;
        private final int width, height;
        private final String warning;
        private final int[][] referenceLum;   // raw, never modified
        private final int[][] shiftedLum;     // raw, never modified
        private final boolean havePreShrink;
        private final int preShrinkLevel;     // fixed for the session -- no live control

        private int shiftHintX, shiftHintY;         // 0-10, live via the Shift panel
        private int smoothBilateral, smoothAnisotropic; // 0-10, live via the Smooth panel

        // Cached between recomputes so a Shift-only change can reuse them
        // instead of re-smoothing: the currently-smoothed luminance data
        // (== referenceLum/shiftedLum themselves when both smoothing levels
        // are 0 -- see smoothed()), and the two report halves (see
        // buildStaticReport()/buildHintedReport()'s own comment for why the
        // report is split this way).
        private int[][] refWork, shiftedWork;
        private String staticReportText = "", hintedReportText = "";

        private JFrame frame;
        private JScrollPane scrollPane;
        private ImageCanvas canvas;
        private JTextArea reportArea;

        private BufferedImage baseOverlay;    // current overlay, un-zoomed
        private BufferedImage displayImage;   // baseOverlay scaled by zoomScale
        private double zoomScale = 1.0;
        private double fitScale = 1.0;

        private static final double ZOOM_FACTOR = 1.25;
        private static final double ZOOM_MIN = 0.05;
        private static final double ZOOM_MAX = 32.0;

        EstimatorWindow(String originalPath, String translatedPath, int width, int height, String warning,
                int[][] referenceLum, int[][] shiftedLum,
                int shiftHintX, int shiftHintY, boolean havePreShrink, int preShrinkLevel)
        {
            this.originalPath = originalPath;
            this.translatedPath = translatedPath;
            this.width = width;
            this.height = height;
            this.warning = warning;
            this.referenceLum = referenceLum;
            this.shiftedLum = shiftedLum;
            this.shiftHintX = shiftHintX;
            this.shiftHintY = shiftHintY;
            this.havePreShrink = havePreShrink;
            this.preShrinkLevel = preShrinkLevel;
        }

        void show()
        {
            frame = new JFrame("ShiftEstimator -- reference (gray): " + new File(originalPath).getName()
                    + "   shifted (yellow): " + new File(translatedPath).getName());
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            canvas = new ImageCanvas();
            scrollPane = new JScrollPane(canvas, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
            scrollPane.getHorizontalScrollBar().setUnitIncrement(16);
            // Same Ctrl+scroll-wheel zoom-at-cursor behavior as DeltaWriter's
            // own scroll_pane listener.
            scrollPane.addMouseWheelListener(e ->
            {
                if (e.isControlDown())
                {
                    JViewport vp = scrollPane.getViewport();
                    Point vpos = vp.getViewPosition();
                    Point mpt = e.getPoint();
                    int mcx = mpt.x + vpos.x, mcy = mpt.y + vpos.y;
                    double old = zoomScale;
                    zoomScale = (e.getWheelRotation() < 0) ? Math.min(ZOOM_MAX, zoomScale * ZOOM_FACTOR)
                                                            : Math.max(ZOOM_MIN, zoomScale / ZOOM_FACTOR);
                    if (zoomScale == old) return;
                    updateDisplayImage();
                    double r = zoomScale / old;
                    vp.setViewPosition(new Point(Math.max(0, (int) (mcx * r) - mpt.x), Math.max(0, (int) (mcy * r) - mpt.y)));
                    updateTitle();
                }
                else
                {
                    scrollPane.dispatchEvent(e);
                }
            });

            reportArea = new JTextArea();
            reportArea.setEditable(false);
            reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            JScrollPane reportScroll = new JScrollPane(reportArea);
            reportScroll.setPreferredSize(new Dimension(width, 170));

            JPanel content = new JPanel(new BorderLayout());
            content.add(scrollPane, BorderLayout.CENTER);
            content.add(reportScroll, BorderLayout.SOUTH);
            frame.setContentPane(content);

            frame.setJMenuBar(buildMenuBar());

            // Initial fit-to-screen sizing and zoom, the same shape as
            // DeltaWriter's constructor: cap against 70% of the screen, and
            // OPEN already fit rather than at 100% and needing a manual Fit.
            // The 230px height margin (vs. DeltaWriter's plain 80px) leaves
            // room for this window's extra report panel below the image.
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int mw = (int) (screen.width * 0.70) - 40;
            int mh = (int) (screen.height * 0.70) - 230;
            fitScale = Math.min(1.0, Math.min((double) mw / width, (double) mh / height));
            zoomScale = fitScale;

            recomputeSmoothingDependent();

            frame.setSize(Math.min((int) (width * fitScale) + 40, (int) (screen.width * 0.70)),
                    Math.min((int) (height * fitScale) + 230, (int) (screen.height * 0.70)));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        // ---- Recompute pipeline -------------------------------------------
        //
        // Two entry points instead of DeltaWriter's single always-rerun-
        // everything ApplyHandler, since here it's cheap to tell exactly
        // what a given Parameters change can and can't affect:
        //
        //   - recomputeSmoothingDependent(): the Smooth panel changed (or
        //     startup). Smoothing changes the actual luminance data, so
        //     everything downstream of it has to rerun -- re-smooth both
        //     channels, rebuild the overlay, and rebuild BOTH report halves.
        //   - recomputeHintDependent(): the Shift panel changed. The
        //     smoothed data and the overlay are untouched by a shift-level
        //     hint, and neither is the plain getTranslation()/no-hint
        //     getRefinedTranslation() report text -- only the hinted
        //     getRefinedTranslation() call depends on shrinkLevelX/
        //     shrinkLevelY, so only buildHintedReport() needs to rerun,
        //     against the already-current refWork/shiftedWork.
        //
        // Both end by reassembling reportArea's text from the two cached
        // halves, so whichever one didn't change is simply reused.

        private void recomputeSmoothingDependent()
        {
            refWork = smoothed(referenceLum);
            shiftedWork = smoothed(shiftedLum);

            baseOverlay = buildOverlay(refWork, shiftedWork);
            updateDisplayImage();

            staticReportText = buildStaticReport(originalPath, translatedPath, width, height, warning, refWork, shiftedWork);
            hintedReportText = buildHintedReport(refWork, shiftedWork, shiftHintX, shiftHintY, havePreShrink, preShrinkLevel);
            updateReportArea();
        }

        private void recomputeHintDependent()
        {
            hintedReportText = buildHintedReport(refWork, shiftedWork, shiftHintX, shiftHintY, havePreShrink, preShrinkLevel);
            updateReportArea();
        }

        private void updateReportArea()
        {
            reportArea.setText(staticReportText + hintedReportText);
            reportArea.setCaretPosition(0);
        }

        private int[][] smoothed(int[][] lum)
        {
            if (smoothBilateral == 0 && smoothAnisotropic == 0)
            {
                return lum;
            }
            int[] flat = flatten(lum);
            if (smoothBilateral > 0) flat = TranslateMapper.bilateralSmooth(flat, width, height, smoothBilateral);
            if (smoothAnisotropic > 0) flat = TranslateMapper.anisotropicSmooth(flat, width, height, smoothAnisotropic);
            return unflatten(flat, width, height);
        }

        // ---- View menu / zoom, modeled on DeltaWriter's own ----------------

        private JMenuBar buildMenuBar()
        {
            JMenuBar menuBar = new JMenuBar();

            JMenu viewMenu = new JMenu("View");
            JMenuItem zoomIn = new JMenuItem("Zoom In");
            zoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK));
            zoomIn.addActionListener(e -> zoomBy(ZOOM_FACTOR));
            viewMenu.add(zoomIn);

            JMenuItem zoomOut = new JMenuItem("Zoom Out");
            zoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
            zoomOut.addActionListener(e -> zoomBy(1.0 / ZOOM_FACTOR));
            viewMenu.add(zoomOut);

            JMenuItem fit = new JMenuItem("Fit");
            fit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
            fit.addActionListener(e ->
            {
                Dimension vps = scrollPane.getViewport().getSize();
                zoomScale = Math.min((double) vps.width / width, (double) vps.height / height);
                updateDisplayImage();
                updateTitle();
            });
            viewMenu.add(fit);

            JMenuItem actualSize = new JMenuItem("100%");
            actualSize.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK));
            actualSize.addActionListener(e ->
            {
                zoomScale = 1.0;
                updateDisplayImage();
                updateTitle();
            });
            viewMenu.add(actualSize);

            JMenu parametersMenu = new JMenu("Parameters");
            parametersMenu.add(makeShiftItem());
            parametersMenu.add(makeSmoothItem());

            menuBar.add(viewMenu);
            menuBar.add(parametersMenu);
            return menuBar;
        }

        private void zoomBy(double factor)
        {
            double newScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoomScale * factor));
            if (newScale == zoomScale) return;
            JViewport vp = scrollPane.getViewport();
            Point vpos = vp.getViewPosition();
            Dimension vs = vp.getSize();
            double cx = vpos.x + vs.width / 2.0, cy = vpos.y + vs.height / 2.0;
            double r = newScale / zoomScale;
            zoomScale = newScale;
            updateDisplayImage();
            vp.setViewPosition(new Point(Math.max(0, (int) (cx * r - vs.width / 2.0)), Math.max(0, (int) (cy * r - vs.height / 2.0))));
            updateTitle();
        }

        private void updateDisplayImage()
        {
            if (zoomScale == 1.0)
            {
                displayImage = baseOverlay;
            }
            else
            {
                int w = Math.max(1, (int) (width * zoomScale));
                int h = Math.max(1, (int) (height * zoomScale));
                AffineTransform t = new AffineTransform();
                t.scale(zoomScale, zoomScale);
                displayImage = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR)
                        .filter(baseOverlay, new BufferedImage(w, h, baseOverlay.getType()));
            }
            canvas.setPreferredSize(new Dimension((int) (width * zoomScale), (int) (height * zoomScale)));
            canvas.revalidate();
            canvas.repaint();
        }

        private void updateTitle()
        {
            frame.setTitle("ShiftEstimator -- reference (gray): " + new File(originalPath).getName()
                    + "   shifted (yellow): " + new File(translatedPath).getName()
                    + "  [" + Math.round(zoomScale * 100) + "%]");
        }

        // ---- Parameters menu -----------------------------------------------

        // Shift panel: two JSpinners (0-10) -- a text field with up/down
        // buttons is exactly JSpinner's own default look, so no hand-built
        // buttons are needed. Every change re-runs only the hinted
        // getRefinedTranslation() call, via recomputeHintDependent(); the
        // static part of the report and the overlay don't depend on the
        // shift hints, so they're left alone (see recomputeHintDependent()
        // and buildHintedReport() for why this split is safe).
        private JMenuItem makeShiftItem()
        {
            JMenuItem item = new JMenuItem("Shift");
            JDialog dialog = new JDialog(frame, "Shift");

            JSpinner xSpinner = new JSpinner(new SpinnerNumberModel(shiftHintX, 0, 10, 1));
            JSpinner ySpinner = new JSpinner(new SpinnerNumberModel(shiftHintY, 0, 10, 1));
            xSpinner.addChangeListener(e -> { shiftHintX = (Integer) xSpinner.getValue(); recomputeHintDependent(); });
            ySpinner.addChangeListener(e -> { shiftHintY = (Integer) ySpinner.getValue(); recomputeHintDependent(); });

            JPanel panel = new JPanel(new GridLayout(2, 2, 6, 6));
            panel.add(new JLabel("X shift:"));
            panel.add(xSpinner);
            panel.add(new JLabel("Y shift:"));
            panel.add(ySpinner);
            dialog.add(panel);

            item.addActionListener(e ->
            {
                Point loc = frame.getLocation();
                dialog.setLocation((int) loc.getX(), (int) loc.getY() - 60);
                dialog.pack();
                dialog.setVisible(true);
            });
            return item;
        }

        // Smooth panel: two JSliders (0-10), same shape as DeltaWriter's own
        // Smooth/Smooth2 slider dialogs (a slider paired with a small
        // read-out text field), combined into one panel here rather than
        // two separate menu items. Every change re-smooths both luminance
        // channels, rebuilds the overlay, and reruns every estimate (via
        // recomputeSmoothingDependent()), since smoothing changes the
        // underlying luminance data that both report halves are computed
        // from -- unlike the shift hints, there's no cheaper path here.
        private JMenuItem makeSmoothItem()
        {
            JMenuItem item = new JMenuItem("Smooth");
            JDialog dialog = new JDialog(frame, "Smooth");

            JPanel panel = new JPanel(new GridLayout(2, 1, 4, 8));
            panel.add(makeSmoothRow("Bilateral", smoothBilateral, v -> { smoothBilateral = v; recomputeSmoothingDependent(); }));
            panel.add(makeSmoothRow("Anisotropic", smoothAnisotropic, v -> { smoothAnisotropic = v; recomputeSmoothingDependent(); }));
            dialog.add(panel);

            item.addActionListener(e ->
            {
                Point loc = frame.getLocation();
                dialog.setLocation((int) loc.getX(), (int) loc.getY() - 60);
                dialog.pack();
                dialog.setVisible(true);
            });
            return item;
        }

        private JPanel makeSmoothRow(String title, int init, java.util.function.IntConsumer onChange)
        {
            JSlider slider = new JSlider(0, 10, init);
            JTextField field = new JTextField(3);
            field.setText(" " + init + " ");
            field.setEditable(false);
            slider.addChangeListener(e ->
            {
                int v = slider.getValue();
                field.setText(" " + v + " ");
                onChange.accept(v);
            });
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.add(new JLabel(title), BorderLayout.WEST);
            row.add(slider, BorderLayout.CENTER);
            row.add(field, BorderLayout.EAST);
            return row;
        }

        private class ImageCanvas extends JPanel
        {
            ImageCanvas() { setOpaque(true); }

            @Override
            public Dimension getPreferredSize()
            {
                return displayImage != null
                        ? new Dimension(displayImage.getWidth(), displayImage.getHeight())
                        : new Dimension(Math.max(1, (int) (width * zoomScale)), Math.max(1, (int) (height * zoomScale)));
            }

            @Override
            protected void paintComponent(Graphics g)
            {
                super.paintComponent(g);
                if (displayImage != null)
                {
                    g.drawImage(displayImage, 0, 0, this);
                }
            }
        }
    }
}
