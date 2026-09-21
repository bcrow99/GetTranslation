import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Test driver: takes a reference image and a shifted version of it (e.g.
 * the reference/shifted pair written by getTranslatedImage.java), converts
 * both to a single luminance channel, and estimates the translation
 * between them two ways:
 *
 *   1) ImageMapper.getTranslation() called directly on the full-resolution
 *      luminance images. This is the "simple version" -- per its own
 *      comment and per translate()'s documented "-1 to 1" input range, it
 *      only resolves SUBPIXEL offsets. For anything at or beyond about a
 *      pixel of true shift, expect this to either report status 4 (hit
 *      the 1-pixel boundary) or return something that doesn't match the
 *      applied shift -- that's expected, not a bug, and is exactly the
 *      gap getRefinedTranslation() exists to close.
 *
 *   2) TranslateMapper.getRefinedTranslation(), the coarse-to-fine wrapper
 *      that pyramids progressively-tightened overlap windows down near
 *      64x64, using getTranslation() at each level to refine a running
 *      estimate. This is the one that should recover a whole-pixel shift.
 *
 *   3) If shrinkLevelX/shrinkLevelY are supplied on the command line,
 *      TranslateMapper.getRefinedTranslation(source1, source2,
 *      shrinkLevelX, shrinkLevelY[, preShrinkLevel]) too -- the overload
 *      that takes a directly-specified amount of pyramid reduction per
 *      axis (a count of 2x halvings: 0 if that axis's true shift is under
 *      1 pixel, 1 if it's in [1,2), 2 if it's in [2,4), and so on) and
 *      uses it to size the first refinement round instead of discovering
 *      it from the whole image. See that overload's own comment in
 *      TranslateMapper.java for when this helps (a correct level: faster
 *      and more accurate) versus when it doesn't (a wrong level: falls
 *      back to the same robust mechanism the no-hint version uses, so
 *      it's no longer unbounded, but the first round was wasted work --
 *      that failure mode was found and fixed by actually running it, back
 *      when this was the tolerance-based overload this one replaced).
 *
 *   4) If preShrinkLevel is supplied (with or without a shrink-level
 *      hint), the matching pre-shrink overload too -- downsizes both
 *      images by 2^preShrinkLevel before anything else runs, for when the
 *      source images are large enough that full-resolution processing is
 *      expensive and more precise than the source actually warrants. See
 *      that overload's own comment in TranslateMapper.java; note its
 *      shrinkLevelX/shrinkLevelY (when also given) still describe the
 *      ORIGINAL images' magnitude, not the pre-shrunk ones'.
 *
 * Printing these side by side is deliberate -- it's the empirical check
 * for whether getRefinedTranslation() is actually doing the job the plain
 * getTranslation() can't, and (when a hint and/or pre-shrink is supplied)
 * for whether it actually helped on this particular pair rather than just
 * assuming it did.
 *
 * Usage:
 *   java getTranslation <originalImagePath> <translatedImagePath>
 *   java getTranslation <originalImagePath> <translatedImagePath> <preShrinkLevel>
 *   java getTranslation <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY>
 *   java getTranslation <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>
 *
 * shrinkLevelX/shrinkLevelY are each a count of 2x pyramid halvings for
 * that axis -- e.g. a true shift around 12 pixels needs ceil(log2(12)) =
 * 4 halvings, so "4 4" is the level to try; pass different values per
 * axis when you expect dx and dy to need meaningfully different amounts
 * of reduction. preShrinkLevel is also a count of 2x halvings, but of the
 * WHOLE source image up front, independent of shrinkLevelX/Y -- useful on
 * a large source image mainly for speed, since it's what determines how
 * much data every later stage has to work with.
 *
 * Expects the two images to be the same size. getTranslatedImage.java's
 * current version produces exactly that: a reference/shifted pair, both
 * cropped down to their mutual overlap at the requested (dx, dy), with no
 * padding in either -- pass its two outputs here as originalImagePath and
 * translatedImagePath respectively. (An earlier version of
 * getTranslatedImage.java instead produced one full-size image with the
 * revealed margin edge-replicated; this program works the same way
 * against that older style of input too, since all it actually requires
 * is that the two images be the same size -- it doesn't care whether that
 * came from a same-size pad or a same-size crop.) If you asked for a
 * (dx, dy) shift, the estimate from getRefinedTranslation() below should
 * come out close to (dx, dy) -- see getTranslatedImage.java's header
 * comment for why the sign should line up directly, with no flip needed.
 */
public class getTranslation
{
    // Below this, ImageMapper.getRefinedTranslation()'s pyramid target
    // (64x64, hardcoded inside that method) leaves little or no room to
    // do more than one refinement round on the shorter axis.
    private static final int RECOMMENDED_MIN_DIMENSION = 128;

    public static void main(String[] args)
    {
        if (args.length != 2 && args.length != 3 && args.length != 4 && args.length != 5)
        {
            System.err.println("Usage: java getTranslation <originalImagePath> <translatedImagePath>");
            System.err.println("   or: java getTranslation <originalImagePath> <translatedImagePath> <preShrinkLevel>");
            System.err.println("   or: java getTranslation <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY>");
            System.err.println("   or: java getTranslation <originalImagePath> <translatedImagePath> <shrinkLevelX> <shrinkLevelY> <preShrinkLevel>");
            System.exit(1);
        }

        String originalPath   = args[0];
        String translatedPath = args[1];

        // 3 args is <preShrinkLevel> alone; 4 or 5 args start with
        // <shrinkLevelX> <shrinkLevelY>, with a 5th being <preShrinkLevel>.
        boolean haveHint = (args.length == 4 || args.length == 5);
        boolean havePreShrink = (args.length == 3 || args.length == 5);

        int shrinkLevelX = 0, shrinkLevelY = 0, preShrinkLevel = 0;
        if (haveHint)
        {
            shrinkLevelX = (int) parseDoubleArg(args[2], "shrinkLevelX");
            shrinkLevelY = (int) parseDoubleArg(args[3], "shrinkLevelY");
        }
        if (havePreShrink)
        {
            preShrinkLevel = (int) parseDoubleArg(args[args.length - 1], "preShrinkLevel");
        }

        BufferedImage original   = readImage(originalPath);
        BufferedImage translated = readImage(translatedPath);

        int width  = original.getWidth();
        int height = original.getHeight();
        if (translated.getWidth() != width || translated.getHeight() != height)
        {
            System.err.println("Image size mismatch: \"" + originalPath + "\" is " + width + "x" + height
                    + ", \"" + translatedPath + "\" is " + translated.getWidth() + "x" + translated.getHeight()
                    + ". Both images must be the same size.");
            System.exit(1);
            return;
        }

        if (Math.min(width, height) < RECOMMENDED_MIN_DIMENSION)
        {
            System.out.println("Warning: shorter side is " + Math.min(width, height)
                    + "px. getRefinedTranslation() pyramids down toward 64x64, so an image this small "
                    + "may only get one refinement round (or none) -- results may be less meaningful.");
        }

        int[][] lum1 = toLuminance(original);
        int[][] lum2 = toLuminance(translated);

        System.out.println("Images: " + width + "x" + height
                + "  (\"" + originalPath + "\" vs \"" + translatedPath + "\")");
        System.out.println();

        // 1) The plain, subpixel-only estimator, run once on the full images.
        double[] simple = TranslateMapper.getTranslation(lum1, lum2);
        System.out.println("TranslateMapper.getTranslation() [subpixel-only, single pass]:");
        System.out.println("    status = " + (int) simple[0] + " (" + describeStatus((int) simple[0]) + ")");
        System.out.println("    x = " + simple[1] + "   y = " + simple[2]);
        System.out.println();

        // 2) The coarse-to-fine wrapper, no hint and no pre-shrink -- the
        // baseline every other variant below is compared against.
        long t0 = System.currentTimeMillis();
        double[] refined = TranslateMapper.getRefinedTranslation(lum1, lum2);
        long t1 = System.currentTimeMillis();
        System.out.println("TranslateMapper.getRefinedTranslation() [pyramided, iterative]:");
        System.out.println("    x = " + refined[0] + "   y = " + refined[1] + "   [" + (t1 - t0) + " ms]");

        // 3) Whichever additional variant was actually asked for on the
        // command line -- see this class's header comment for what each
        // does differently and when it's actually worth using.
        if (haveHint && !havePreShrink)
        {
            System.out.println();
            long t2 = System.currentTimeMillis();
            double[] hinted = TranslateMapper.getRefinedTranslation(lum1, lum2, shrinkLevelX, shrinkLevelY);
            long t3 = System.currentTimeMillis();
            System.out.println("TranslateMapper.getRefinedTranslation() [hinted: shrinkLevelX=" + shrinkLevelX
                    + " shrinkLevelY=" + shrinkLevelY + "]:");
            System.out.println("    x = " + hinted[0] + "   y = " + hinted[1] + "   [" + (t3 - t2) + " ms]");
        }
        else if (!haveHint && havePreShrink)
        {
            System.out.println();
            long t2 = System.currentTimeMillis();
            double[] preShrunk = TranslateMapper.getRefinedTranslation(lum1, lum2, preShrinkLevel);
            long t3 = System.currentTimeMillis();
            System.out.println("TranslateMapper.getRefinedTranslation() [pre-shrunk: preShrinkLevel=" + preShrinkLevel + "]:");
            System.out.println("    x = " + preShrunk[0] + "   y = " + preShrunk[1] + "   [" + (t3 - t2) + " ms]");
        }
        else if (haveHint && havePreShrink)
        {
            System.out.println();
            long t2 = System.currentTimeMillis();
            double[] hintedPreShrunk = TranslateMapper.getRefinedTranslation(lum1, lum2,
                    shrinkLevelX, shrinkLevelY, preShrinkLevel);
            long t3 = System.currentTimeMillis();
            System.out.println("TranslateMapper.getRefinedTranslation() [hinted: shrinkLevelX=" + shrinkLevelX
                    + " shrinkLevelY=" + shrinkLevelY + ", pre-shrunk: preShrinkLevel=" + preShrinkLevel + "]:");
            System.out.println("    x = " + hintedPreShrunk[0] + "   y = " + hintedPreShrunk[1]
                    + "   [" + (t3 - t2) + " ms]");
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

    private static String describeStatus(int status)
    {
        switch (status)
        {
            case 0: return "zero increment on the very first pass -- images already matched";
            case 1: return "converged: increment fell below 1% of the initial increment";
            case 2: return "stopped: increment reversed direction between iterations";
            case 3: return "did not converge within the internal iteration limit";
            case 4: return "stopped: translation reached translate()'s +/-1 pixel boundary";
            default: return "unrecognized status";
        }
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

    // Standard ITU-R BT.601 luma weights, rounded to nearest. Every
    // ImageMapper algorithm this program calls operates on a single-
    // channel int[][], so full RGB is collapsed to one luminance value
    // per pixel here -- and only here; getTranslatedImage.java never
    // needs to do this, since shifting doesn't touch pixel color.
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
}
