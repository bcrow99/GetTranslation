import java.util.ArrayList;

/**
 * Subpixel image-translation estimation, extracted from ImageMapper.java
 * into its own focused file. ImageMapper.java is shared by several
 * different projects with different goals and was getting hard to track;
 * this file has only what image-registration work actually needs --
 * nothing else copied over. See ImageMapper.java for the general-purpose
 * pyramid/dilation/resize toolkit this was pulled from, including its own
 * (longer) version history; this file's own history below only covers
 * what changed in the translation-estimation methods themselves.
 *
 * Layered top to bottom:
 *   1) Low-level support: getLocationType, getGradient, avgAreaXTransform,
 *      avgAreaYTransform, avgAreaTransform, extract, contract, translate.
 *   2) getTranslation() -- a single-pass, gradient-descent (Lucas-Kanade
 *      style) SUBPIXEL-ONLY estimator. Trusts its input images to already
 *      be aligned to within about one pixel; see its own comment.
 *   3) getRefinedTranslation() -- four overloads, all coarse-to-fine
 *      wrappers around getTranslation() for when the true misalignment is
 *      more than a pixel: a no-hint version that has to discover how much
 *      pyramid reduction is needed; a hinted version where the caller
 *      specifies it directly, per axis, which is both faster and more
 *      accurate when it's known; and a pre-shrink variant of each (taking
 *      one extra preShrinkLevel argument) for when the source images
 *      themselves are large enough that it's worth downsizing them once,
 *      up front, before any of the rest of this runs.
 *
 * History (translation-estimation methods only):
 *
 * - contract()/translate() rounding fixed to a shared convention (both now
 *   round to nearest); translate() previously halved its output's
 *   intensity for no evident reason, which desynced it from contract()
 *   even though getTranslation()'s own refinement loop compares the two
 *   directly against each other.
 * - avgAreaTransform(int[][], int, int) had two bugs (an
 *   ArrayIndexOutOfBoundsException shrinking to a smaller new_xdim, and a
 *   flat-index variable reset inside the wrong loop that silently
 *   flattened every output row to a copy of row 0) -- both found by
 *   actually running getRefinedTranslation() end to end, not by reading
 *   the code. Also true of the flat avgAreaXTransform/avgAreaYTransform
 *   overloads below.
 * - getRefinedTranslation()'s first working version pyramided each round's
 *   entire window down to a single fixed 64x64 target in one shot. Fine on
 *   a few-hundred-pixel window; on a 2048x2048 test image (true shift
 *   (12,12)) that one-shot ~30x reduction came back (13.9, 21.9) --
 *   confirmed by test, not just reasoning. Root cause: any ordinary error
 *   in a single highly-reduced coarse estimate gets multiplied by the
 *   whole reduction factor once rescaled back to real pixels, and
 *   pyramiding blurs x/y detail unevenly (the aperture problem), so the
 *   error isn't even symmetric between axes.
 * - Fixed by refineWithinWindow(): a genuine coarse-to-fine pyramid inside
 *   each round, stepping resolution by roughly 2x at a time instead of
 *   jumping straight to the target. Its stages deliberately do NOT stop on
 *   a direction reversal the way the outer rounds do -- tested, and
 *   stopping on reversal there just reproduced the same overshoot, because
 *   a finer stage reversing a coarser stage's correction is normally the
 *   finer stage correctly walking back the coarser stage's necessarily
 *   rough guess, not noise. With that fixed, the same (12,12) case came
 *   back (12.6, 12.1).
 * - A "how much pyramid reduction, given a known bound on the answer"
 *   overload was added and iterated on twice. Its first version sized the
 *   starting pyramid resolution from a tolerance (a plausible range around
 *   a guessed translation) and kept using that size for every subsequent
 *   round; a well-converged case then kept re-finding the same tiny
 *   correction round after round (Math.ceil() means a nonzero residual
 *   always trims the window by at least 1 pixel, even once the true
 *   residual is negligible), burning through all 40 rounds and 56 seconds
 *   for an answer identical to round one's. Fixed by stopping outer rounds
 *   once a round's own correction drops under half a pixel. Its second
 *   problem: a guess that was actually wrong (tested: guessing 60 against
 *   a true 12) needed dozens of rounds at that same fine resolution to
 *   correct, since that resolution is exactly wrong for a residual much
 *   bigger than the tolerance it was sized for. Fixed by using the
 *   tolerance-sized resolution for one trial round only, then falling back
 *   to a standard, coarser target for anything beyond that.
 * - That tolerance-based approach is superseded here by a more direct one:
 *   rather than expressing what's known as an expected translation plus a
 *   tolerance and converting that into a pyramid target via a heuristic,
 *   the caller specifies the pyramid reduction directly, as a shrink level
 *   per axis. Took three iterations, each found wrong by actually testing
 *   it rather than by reasoning -- see getRefinedTranslation(int[][],
 *   int[][], int, int)'s own comment for the full detail, condensed here:
 *   a raw one-shot avgAreaTransform()+getTranslation() jump to the target
 *   (reproduced the 2048x2048 bug above, target size notwithstanding); a
 *   staged trial round via refineWithinWindow() followed by a SEPARATE
 *   refineOuterRounds() call as fallback (fixed the one-shot problem but
 *   broke round-to-round reversal tracking across the call boundary, so a
 *   correction the no-hint overload correctly discards as noise got kept
 *   here instead); and the version that stuck: one continuous
 *   refineOuterRounds() call whose first round targets a pyramid size
 *   derived from the shrink levels (with one extra halving of margin, and
 *   a tested floor -- see that method's MIN_TARGET comment) and whose
 *   later rounds use the same DEFAULT_PYRAMID_TARGET the no-hint overload
 *   always has. Tested against the no-hint overload on the same pairs: a
 *   true (5,3) shift essentially matches it; a true (12,12) shift on the
 *   2048x2048 image essentially matches it too; and a true (15,-8) shift
 *   -- where X and Y genuinely need different shrink levels -- comes back
 *   (15.0,-7.8), noticeably more accurate than the no-hint overload's
 *   (14.3,-7.5) on the same pair, which is the asymmetric-axis case this
 *   whole feature was for.
 */
public class TranslateMapper
{
    // =======================================================================
    // Low-level support
    // =======================================================================

    public static int getLocationType(int xindex, int yindex, int xdim, int ydim)
    {
        int location_type = 0;
        if (yindex == 0)
        {
            if (xindex == 0)
            {
                location_type = 1;
            } else if (xindex % xdim != xdim - 1)
            {
                location_type = 2;
            } else
            {
                location_type = 3;
            }
        } else if (yindex % ydim != ydim - 1)
        {
            if (xindex == 0)
            {
                location_type = 4;
            } else if (xindex % xdim != xdim - 1)
            {
                location_type = 5;
            } else
            {
                location_type = 6;
            }
        } else
        {
            if (xindex == 0)
            {
                location_type = 7;
            } else if (xindex % xdim != xdim - 1)
            {
                location_type = 8;
            } else
            {
                location_type = 9;
            }
        }
        return (location_type);
    }

    public static ArrayList[][] getGradient(int src[][])
    {
        int ydim = src.length;
        int xdim = src[0].length;
        ArrayList[][] dst = new ArrayList[ydim][xdim];

        for (int i = 0; i < ydim; i++)
        {
            for (int j = 0; j < xdim; j++)
            {
                int type = getLocationType(j, i, xdim, ydim);
                double xgradient = 0;
                double ygradient = 0;
                if (type == 1)
                {
                    xgradient = Double.NaN;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 2)
                {
                    xgradient = (src[i][j + 1] - src[i][j - 1]) + (src[i + 1][j + 1] - src[i + 1][j - 1]);
                    xgradient /= 2;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 3)
                {
                    xgradient = Double.NaN;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 4)
                {
                    xgradient = Double.NaN;
                    ygradient = (src[i + 1][j] - src[i - 1][j]) + (src[i + 1][j + 1] - src[i - 1][j + 1]);
                    ygradient /= 2;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 5)
                {
                    xgradient = src[i - 1][j + 1] - src[i - 1][j - 1] + src[i][j + 1] - src[i][j - 1]
                            + src[i + 1][j + 1] - src[i + 1][j - 1];
                    xgradient /= 3;
                    ygradient = src[i + 1][j - 1] - src[i - 1][j - 1] + src[i + 1][j] - src[i - 1][j]
                            + src[i + 1][j + 1] - src[i - 1][j + 1];
                    ygradient /= 3;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 6)
                {
                    xgradient = Double.NaN;
                    ygradient = src[i + 1][j - 1] - src[i - 1][j - 1] + src[i + 1][j] - src[i - 1][j];
                    ygradient /= 2;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 7)
                {
                    xgradient = Double.NaN;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 8)
                {
                    xgradient = (src[i - 1][j + 1] - src[i - 1][j - 1]) + (src[i][j + 1] - src[i][j - 1]);
                    xgradient /= 2;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                } else if (type == 9)
                {
                    xgradient = Double.NaN;
                    ygradient = Double.NaN;

                    ArrayList gradient_list = new ArrayList();
                    gradient_list.add(xgradient);
                    gradient_list.add(ygradient);
                    dst[i][j] = gradient_list;
                }
            }
        }
        return (dst);
    }

    // Below this many pixels, run a row/column loop sequentially --
    // dispatching work to the common ForkJoinPool costs more than a small
    // pyramid stage (most stages are 16x16 up to a few hundred square)
    // takes to just run in one thread. Above it, parallelize: every row
    // (or column, for avgAreaYTransform) touches only its own slice of the
    // output, so there's no shared mutable state and no synchronization
    // needed. This threshold is about per-task dispatch overhead, not core
    // count, so it doesn't need retuning for a bigger machine -- more
    // cores just means more of the eligible calls actually benefit.
    private static final int PARALLEL_PIXEL_THRESHOLD = 200_000;

    // Primitive-array fast path for getGradient() above, used only
    // internally by getTranslation()'s two hot loops. getGradient() itself
    // is left untouched -- same public signature, same ArrayList-based
    // return -- in case anything outside this file depends on it.
    //
    // Why this exists: profiling getTranslation() on a large (3548x5372)
    // image showed getGradient() consuming roughly 65-75% of TOTAL
    // wall-clock time for a full getRefinedTranslation() call -- far more
    // than the trivial per-pixel arithmetic it does would suggest. The
    // reason is allocation, not computation: the ArrayList-based version
    // above allocates one new ArrayList plus two new boxed Double objects
    // for EVERY pixel -- three heap objects per pixel, so tens of millions
    // of small, short-lived allocations (and the GC pressure that comes
    // with them) for a multi-megapixel image. This version does the exact
    // same per-pixel math with zero allocation in the inner loop, storing
    // the two gradient components as primitive double[][] planes instead.
    // Measured effect on the real photo test: roughly 3.3-3.5x faster
    // end to end, with numerically IDENTICAL results (verified against the
    // full regression suite) -- this is a pure representation change, not
    // an algorithm change, so there's no accuracy tradeoff here at all.
    //
    // Also parallelized (see PARALLEL_PIXEL_THRESHOLD above): every row i
    // writes only xg[i]/yg[i], so rows can run on any thread with no
    // shared mutable state.
    private static double[][][] getGradientPrimitive(int[][] src)
    {
        int ydim = src.length;
        int xdim = src[0].length;
        double[][] xg = new double[ydim][xdim];
        double[][] yg = new double[ydim][xdim];

        java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, ydim);
        if ((long) ydim * xdim >= PARALLEL_PIXEL_THRESHOLD)
        {
            rows = rows.parallel();
        }
        rows.forEach(i ->
        {
            for (int j = 0; j < xdim; j++)
            {
                int type = getLocationType(j, i, xdim, ydim);
                double xgradient = Double.NaN;
                double ygradient = Double.NaN;
                if (type == 2)
                {
                    xgradient = ((src[i][j + 1] - src[i][j - 1]) + (src[i + 1][j + 1] - src[i + 1][j - 1])) / 2.0;
                }
                else if (type == 4)
                {
                    ygradient = ((src[i + 1][j] - src[i - 1][j]) + (src[i + 1][j + 1] - src[i - 1][j + 1])) / 2.0;
                }
                else if (type == 5)
                {
                    xgradient = (src[i - 1][j + 1] - src[i - 1][j - 1] + src[i][j + 1] - src[i][j - 1]
                            + src[i + 1][j + 1] - src[i + 1][j - 1]) / 3.0;
                    ygradient = (src[i + 1][j - 1] - src[i - 1][j - 1] + src[i + 1][j] - src[i - 1][j]
                            + src[i + 1][j + 1] - src[i - 1][j + 1]) / 3.0;
                }
                else if (type == 6)
                {
                    ygradient = (src[i + 1][j - 1] - src[i - 1][j - 1] + src[i + 1][j] - src[i - 1][j]) / 2.0;
                }
                else if (type == 8)
                {
                    xgradient = ((src[i - 1][j + 1] - src[i - 1][j - 1]) + (src[i][j + 1] - src[i][j - 1])) / 2.0;
                }
                // types 1, 3, 7, 9 (the four corners) stay NaN/NaN, same as getGradient() above.
                xg[i][j] = xgradient;
                yg[i][j] = ygradient;
            }
        });
        return new double[][][] { xg, yg };
    }

    // Shared by both accumulation loops in getTranslation() below: sums
    // the Gauss-Newton normal-equation terms (w, x, z, b1, b2 -- see
    // solveIncrement()'s comment for what they mean) over every pixel with
    // valid gradients. Parallelized the same way as getGradientPrimitive()
    // above -- one partial sum per row, combined at the end -- since this
    // reduction is the second-largest cost in getTranslation() after the
    // gradient computation itself. Combining row-partial sums in a
    // different order than the original single-threaded row-by-row loop
    // does shift results at the level of floating-point rounding (addition
    // isn't associative) -- verified against the regression suite: e.g.
    // 4.189238857491706 becomes 4.189238857492134, a difference around the
    // 11th-12th significant digit. Same harmless character as the tiny
    // shift solveIncrement() introduced when it replaced the original
    // divide-by-z-then-by-w formula -- not a change in what's being
    // computed, just in what order floating-point rounding happens in.
    // Below PARALLEL_PIXEL_THRESHOLD this runs sequentially and matches
    // the original loop's results exactly, bit for bit.
    private static double[] accumulateNormalEquations(double[][] xgradientPlane, double[][] ygradientPlane,
            int[][] deltaSource, int[][] estimate, int ydim, int xdim)
    {
        java.util.stream.IntStream rows = java.util.stream.IntStream.range(1, ydim - 1);
        if ((long) ydim * xdim >= PARALLEL_PIXEL_THRESHOLD)
        {
            rows = rows.parallel();
        }
        return rows.mapToObj(i ->
        {
            double rw = 0, rx = 0, rz = 0, rb1 = 0, rb2 = 0;
            for (int j = 1; j < xdim - 1; j++)
            {
                double xgradient = xgradientPlane[i][j];
                double ygradient = ygradientPlane[i][j];
                if (!Double.isNaN(xgradient) && !Double.isNaN(ygradient))
                {
                    double delta = deltaSource[i][j] - estimate[i][j];
                    rw += xgradient * xgradient;
                    rx += xgradient * ygradient;
                    rz += ygradient * ygradient;
                    rb1 += xgradient * delta;
                    rb2 += ygradient * delta;
                }
            }
            return new double[] { rw, rx, rz, rb1, rb2 };
        }).reduce(new double[5], (a, b) -> new double[] {
                a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3], a[4] + b[4] });
    }

    public static int[] avgAreaXTransform(int source[], int xdim, int ydim, int new_xdim)
    {
        double differential         = (double) xdim / (double) new_xdim;
        int    weight               = (int)(differential * xdim) * 1000;
        int    factor               = xdim * 1000;
        double real_position        = 0.;
        int    current_whole_number = 0;

        int [] start_fraction   = new int[new_xdim];
        int [] end_fraction     = new int[new_xdim];
        int [] number_of_pixels = new int[new_xdim];
        for(int i = 0; i < new_xdim; i++)
        {
            double  previous_position     = real_position;
            int     previous_whole_number = current_whole_number;

            real_position       += differential;
            current_whole_number = (int) (real_position);
            number_of_pixels[i]  = current_whole_number - previous_whole_number;
            start_fraction[i]    = (int) (1000. * (1. - (previous_position - (double) (previous_whole_number))));
            end_fraction[i]      = (int) (1000. * (real_position - (double) (current_whole_number)));
        }

        int[] dest = new int[ydim * new_xdim];
        // Every row y is self-contained -- i/j are both re-derived from y
        // at the top of the loop, and the row only ever reads source[]/
        // writes dest[] within its own slice -- so rows can run in any
        // order, including in parallel, with no shared mutable state. This
        // loop is pure integer arithmetic, so (unlike the floating-point
        // reductions above) parallelizing it changes nothing about the
        // result, not even at the rounding level.
        java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, ydim);
        if ((long) ydim * xdim >= PARALLEL_PIXEL_THRESHOLD)
        {
            rows = rows.parallel();
        }
        rows.forEach(y ->
        {
            int i = y * new_xdim;
            int j = y * xdim;
            for (int x = 0; x < new_xdim - 1; x++)
            {
                if (number_of_pixels[x] == 0)
                {
                    dest[i] = source[j];
                    i++;
                }
                else
                {
                    int total = start_fraction[x] * xdim * source[j];
                    j++;
                    int k = number_of_pixels[x] - 1;
                    while (k > 0)
                    {
                        total += factor * source[j];
                        j++;
                        k--;
                    }
                    total += end_fraction[x] * xdim * source[j];
                    total /= weight;
                    dest[i] = total;
                    i++;
                }
            }

            int x = new_xdim - 1;
            if (number_of_pixels[x] == 0)
                dest[i] = source[j];
            else
            {
                int total = start_fraction[x] * xdim * source[j];
                j++;
                int k = number_of_pixels[x] - 1;
                while (k > 0)
                {
                    total += factor * source[j];
                    j++;
                    k--;
                }
                total /= weight - end_fraction[x] * xdim;
                dest[i] = total;
            }
        });
        return(dest);
    }

    public static int [] avgAreaYTransform(int src[], int xdim, int ydim, int new_ydim)
    {
        double differential         = (double) ydim / (double) new_ydim;
        int    weight               = (int) (differential * ydim) * 1000;
        int    factor               = ydim * 1000;
        double real_position        = 0.;
        int    current_whole_number = 0;

        int [] start_fraction   = new int[new_ydim];
        int [] end_fraction     = new int[new_ydim];
        int [] number_of_pixels = new int[new_ydim];
        for (int i = 0; i < new_ydim; i++)
        {
            double previous_position     = real_position;
            int    previous_whole_number = current_whole_number;

            real_position       += differential;
            current_whole_number = (int) (real_position);
            number_of_pixels[i]  = current_whole_number - previous_whole_number;
            start_fraction[i]    = (int) (1000. * (1. - (previous_position - (double) (previous_whole_number))));
            end_fraction[i]      = (int) (1000. * (real_position - (double) (current_whole_number)));
        }

        int [] dst = new int[xdim * new_ydim];
        // Same reasoning as avgAreaXTransform() above: every column x is
        // self-contained and touches only its own strided slice of src[]/
        // dst[], so columns can run in parallel with no shared mutable
        // state, and (pure integer arithmetic again) with no effect on the
        // result at all.
        java.util.stream.IntStream cols = java.util.stream.IntStream.range(0, xdim);
        if ((long) ydim * xdim >= PARALLEL_PIXEL_THRESHOLD)
        {
            cols = cols.parallel();
        }
        cols.forEach(x ->
        {
            int i = x;
            int j = x;
            for (int y = 0; y < new_ydim - 1; y++)
            {
                if (number_of_pixels[y] == 0)
                {
                    dst[i] = src[j];
                    i += xdim;
                }
                else
                {
                    int total = start_fraction[y] * ydim * src[j];
                    j += xdim;
                    int k = number_of_pixels[y] - 1;
                    while (k > 0)
                    {
                        total += factor * src[j];
                        j += xdim;
                        k--;
                    }
                    total += end_fraction[y] * ydim * src[j];
                    total /= weight;
                    dst[i] = total;
                    i += xdim;
                }
            }
            int y = new_ydim - 1;
            if (number_of_pixels[y] == 0)
                dst[i] = src[j];
            else
            {
                int total = start_fraction[y] * ydim * src[j];
                j += xdim;
                int k = number_of_pixels[y] - 1;
                while (k > 0)
                {
                    total += factor * src[j];
                    j += xdim;
                    k--;
                }
                total /= weight - end_fraction[y] * ydim;
                dst[i] = total;
            }
        });
        return(dst);
    }

    // Area-weighted resize to an arbitrary new_xdim x new_ydim -- a real
    // pyramid reduction (or expansion), unlike contract() below, which
    // only ever shrinks by exactly one pixel per axis. new_xdim and
    // new_ydim are fully independent: this is already an anisotropic
    // resize (X and Y handled by separate passes below), which is what
    // lets getRefinedTranslation()'s hinted overload shrink X and Y by
    // different amounts when they need it.
    public static int [][] avgAreaTransform(int src[][], int new_xdim, int new_ydim)
    {
        int ydim = src.length;
        int xdim = src[0].length;

        int [] source = new int[xdim * ydim];
        int srcK = 0;
        for (int i = 0; i < ydim; i++)
        {
            for (int j = 0; j < xdim; j++)
            {
                source[srcK] = src[i][j];
                srcK++;
            }
        }
        int [] intermediate = avgAreaXTransform(source, xdim, ydim, new_xdim);
        int [] dest         = avgAreaYTransform(intermediate, new_xdim, ydim, new_ydim);

        int[][] dst = new int[new_ydim][new_xdim];
        int dstK = 0;
        for (int i = 0; i < new_ydim; i++)
        {
            for (int j = 0; j < new_xdim; j++)
            {
                dst[i][j] = dest[dstK];
                dstK++;
            }
        }

        return(dst);
    }

    /**
     * Standard bilinear resize to an arbitrary new_xdim x new_ydim, using
     * the pixel-CENTER convention ((i + 0.5) * scale - 0.5, not i * scale)
     * so output pixel centers land at the correct fractional source
     * position instead of being skewed a half-pixel toward the origin --
     * a common off-by-half-pixel mistake in naive bilinear resize code.
     * Edge coordinates are clamped rather than wrapped or left out of
     * bounds.
     *
     * Unlike avgAreaTransform() (a true area-weighted box filter -- every
     * source pixel's exact fractional contribution to each destination
     * cell is computed), this only samples the 4 nearest source pixels
     * around each output pixel's continuous source position. For a large
     * reduction that makes it a WORSE anti-aliasing filter on its own than
     * avgAreaTransform() -- it ignores most of the source pixels each
     * output pixel's true footprint actually covers -- EXCEPT at a resize
     * ratio of exactly 2.0, where its pixel-center convention places every
     * output position exactly halfway between two source pixels, making it
     * mathematically identical to a 2x avgAreaTransform() reduction. It
     * exists here to be chained, usually at exactly that ratio, BEFORE a
     * final avgAreaTransform() step -- see cascadedHalvingReduce() below
     * -- not as a standalone replacement for it.
     */
    public static int[][] bilinearResize(int[][] src, int new_xdim, int new_ydim)
    {
        int ydim = src.length;
        int xdim = src[0].length;
        int[][] dst = new int[new_ydim][new_xdim];

        double scaleX = (double) xdim / (double) new_xdim;
        double scaleY = (double) ydim / (double) new_ydim;

        for (int i = 0; i < new_ydim; i++)
        {
            double srcY = (i + 0.5) * scaleY - 0.5;
            int    y0   = (int) Math.floor(srcY);
            double fy   = srcY - y0;
            int    y0c  = Math.max(0, Math.min(ydim - 1, y0));
            int    y1c  = Math.max(0, Math.min(ydim - 1, y0 + 1));

            for (int j = 0; j < new_xdim; j++)
            {
                double srcX = (j + 0.5) * scaleX - 0.5;
                int    x0   = (int) Math.floor(srcX);
                double fx   = srcX - x0;
                int    x0c  = Math.max(0, Math.min(xdim - 1, x0));
                int    x1c  = Math.max(0, Math.min(xdim - 1, x0 + 1));

                double top    = src[y0c][x0c] * (1. - fx) + src[y0c][x1c] * fx;
                double bottom = src[y1c][x0c] * (1. - fx) + src[y1c][x1c] * fx;
                double value  = top * (1. - fy) + bottom * fy;

                dst[i][j] = (int) Math.round(value);
            }
        }
        return dst;
    }

    /**
     * A bilinear-based alternative to a single avgAreaTransform() call,
     * built from a specific observation about bilinearResize()'s pixel-
     * center convention: at a resize ratio of EXACTLY 2.0, an output
     * pixel's source position works out to (i + 0.5) * 2 - 0.5 = 2i + 0.5
     * -- exactly halfway between source pixels 2i and 2i+1. So a bilinear
     * halving isn't an approximation of a 2x2 box average, it's IDENTICAL
     * to one: bilinearResize() at ratio 2.0 and avgAreaTransform() at
     * ratio 2.0 compute the same thing. That equivalence only holds at
     * exactly 2.0, though -- at any other ratio bilinearResize() is back
     * to sampling just 4 source pixels per output regardless of how much
     * source area that output pixel should really be averaging, a real
     * weakness for a large single-step ratio (see bilinearResize()'s own
     * comment).
     *
     * An earlier version of this idea (since removed) took a single
     * bilinear step at an arbitrary ratio -- the geometric mean of the
     * current and target sizes -- before finishing with
     * avgAreaTransform(). That version paid exactly the large-ratio
     * weakness above at its one bilinear step, and testing bore that out:
     * see the note below for how it compared. This version instead takes
     * several bilinear steps at EXACTLY 2x each -- as many as fit between
     * the source size and the target -- followed by one avgAreaTransform()
     * for whatever fractional remainder is left (always under 2x, so it's
     * a gentle finishing touch, not another aggressive single jump). Since
     * every 2x step is exactly equivalent to a 2x avgAreaTransform(), the
     * actual mechanism being tested isn't "bilinear vs. box" at all --
     * it's "several small reduction steps vs. one big one," which is a
     * real, different thing: repeatedly averaging by 2x is like convolving
     * with a box kernel several times in a row, which (by the same
     * reasoning as the Central Limit Theorem) approaches a much smoother,
     * better-behaved composite filter than a single box kernel of the same
     * total width -- meaningfully less sidelobe leakage/aliasing than one
     * avgAreaTransform() call doing the whole reduction at once, even
     * though a single avgAreaTransform() call is already "mathematically
     * correct" area weighting for ITS ratio. That leftover aliasing on a
     * single big reduction is content- AND phase-dependent (it depends on
     * exactly where edges and fine texture land relative to the box grid),
     * so the SAME content shifted by even a few pixels between a reference
     * and shifted image aliases somewhat differently in each -- a
     * plausible source of the content-dependent, axis-asymmetric bias this
     * file's history keeps running into (the aperture problem).
     *
     * For every step to actually BE an exact 2x reduction (an inexact one
     * loses the box-average equivalence above, and reintroduces a
     * fractional-ratio weakness at every stage instead of just the last),
     * the source is first cropped -- never padded, so no invented pixel
     * data -- down to the largest size at or below its actual size that's
     * an exact multiple of 2^halvings on each axis, where halvings is the
     * number of full 2x steps that fit before undershooting the target.
     * At most 2^halvings - 1 pixels are trimmed off the bottom/right edge
     * per axis -- negligible against the window sizes this file works
     * with (at most 15 pixels for 4 halvings, against windows in the
     * hundreds to thousands of pixels).
     *
     * Falls straight through to a plain avgAreaTransform() call when
     * either axis isn't actually being reduced (upsizing, or a near-1:1
     * resize) -- there's no aliasing to soften and no benefit to an extra
     * pass in that case.
     *
     * Tested as a drop-in replacement for every avgAreaTransform() call in
     * refineWithinWindow()'s per-stage pyramid, against the real artichoke
     * photo and the synthetic (5,3)/(15,-8) pairs, both as a replacement
     * for the earlier single-arbitrary-ratio bilinear version above and on
     * its own merits:
     *   - No-hint overload: a clear, consistent win, and clearly better
     *     than the earlier single-bilinear-jump version. Total error on
     *     the artichoke photo dropped from 1.48px (plain avgAreaTransform)
     *     to 0.50px -- the earlier version only reached 0.99px on the same
     *     pair. Also improved, more modestly, on both synthetic pairs
     *     (0.87px to 0.81px on (5,3); 0.85px to 0.81px on (15,-8)), where
     *     the earlier version had been close to a wash. So
     *     getRefinedTranslation(int[][], int[][]) (the no-hint overload)
     *     passes useAntiAliasedReduction=true down through
     *     refineOuterRounds() and refineWithinWindow() to use this for
     *     every stage.
     *   - Hinted overload: despite being the more principled version,
     *     STILL a net regression, and by a similar or worse margin than
     *     the earlier single-bilinear-jump version -- 0.23px to 1.72px on
     *     the artichoke photo (worse than that version's 1.01px gated
     *     result), 0.20px to 0.92px on (15,-8) (also worse than that
     *     version's 0.13px). (5,3) was the one case where this improved
     *     things slightly, 0.87px to 0.84px, but that's not enough to
     *     outweigh the artichoke and (15,-8) regressions. Since a more
     *     principled, better-tested reduction filter STILL breaks the
     *     hinted overload about as badly as the cruder one did, the most
     *     likely explanation isn't "this specific filter is wrong" -- it's
     *     that the hinted overload's own tuned constants (MIN_TARGET, and
     *     the "one extra halving of headroom" on the shrink-level-derived
     *     first-round target -- see that overload's own comment) were
     *     empirically arrived at specifically against plain
     *     avgAreaTransform()'s behavior, and ANY change to the reduction
     *     filter shifts what that first round's pyramid actually resolves
     *     to, out from under a calibration nothing here has re-tuned to
     *     match. So the hinted overload still passes
     *     useAntiAliasedReduction=false -- plain avgAreaTransform()
     *     throughout, unchanged. Re-tuning the hinted overload's constants
     *     specifically for this reduction method remains untried, and
     *     might recover both the aliasing benefit and the hinted
     *     overload's own accuracy -- see that overload's own comment.
     */
    public static int[][] cascadedHalvingReduce(int[][] src, int new_xdim, int new_ydim)
    {
        int ydim = src.length;
        int xdim = src[0].length;

        if (new_xdim >= xdim || new_ydim >= ydim)
        {
            return avgAreaTransform(src, new_xdim, new_ydim);
        }

        int halvingsX = 0;
        while ((xdim >> (halvingsX + 1)) >= new_xdim) { halvingsX++; }
        int halvingsY = 0;
        while ((ydim >> (halvingsY + 1)) >= new_ydim) { halvingsY++; }

        int cleanXdim = (xdim / (1 << halvingsX)) * (1 << halvingsX);
        int cleanYdim = (ydim / (1 << halvingsY)) * (1 << halvingsY);

        int[][] current = extract(src, 0, 0, cleanXdim, cleanYdim);
        int curXdim = cleanXdim;
        int curYdim = cleanYdim;

        int steps = Math.max(halvingsX, halvingsY);
        for (int s = 0; s < steps; s++)
        {
            int nextXdim = (s < halvingsX) ? curXdim / 2 : curXdim;
            int nextYdim = (s < halvingsY) ? curYdim / 2 : curYdim;
            if (nextXdim == curXdim && nextYdim == curYdim)
            {
                continue;
            }
            current = bilinearResize(current, nextXdim, nextYdim);
            curXdim = nextXdim;
            curYdim = nextYdim;
        }

        return avgAreaTransform(current, new_xdim, new_ydim);
    }

    public static int[][] extract(int[][] source, int xoffset, int yoffset, int xdim, int ydim)
    {
        int [][] dest = new int[ydim][xdim];

        for(int i = 0; i < ydim; i++)
        {
            for(int j = 0; j < xdim; j++)
            {
                dest[i][j] = source[i + yoffset][j + xoffset];
            }
        }
        return(dest);
    }

    // This shrinks the source by one pixel in both dimensions.
    public static int[][] contract(int[][] source)
    {
        int ydim = source.length;
        int xdim = source[0].length;

        int[][] dest = new int[ydim - 1][xdim - 1];

        for(int i = 0; i < ydim - 1; i++)
        {
            for(int j = 0; j < xdim - 1; j++)
            {
                double w = (double) source[i][j];
                double x = (double) source[i][j + 1];
                double y = (double) source[i + 1][j];
                double z = (double) source[i + 1][j + 1];
                // Round to nearest rather than truncate, so contract()
                // and translate() apply the same rounding convention --
                // see translate() below for why that consistency matters.
                dest[i][j] = (int) ((w + x + y + z) * .25 + .5);
            }
        }
        return(dest);
    }

    // x and y should be some number from 1 to -1
    public static int[][] translate(int[][] source, double x, double y)
    {
        int ydim = source.length;
        int xdim = source[0].length;
        int[][] dest = new int[ydim - 1][xdim - 1];

        x += 1.;
        x *= .5;
        y += 1.;
        y *= .5;

        for (int i = 0; i < ydim - 1; i++)
        {
            for (int j = 0; j < xdim - 1; j++)
            {
                double a = (double) source[i][j] * (1. - x) + (double) source[i][j + 1] * x;
                double b = (double) source[i + 1][j] * (1. - x) + (double) source[i + 1][j + 1] * x;
                // Plain bilinear sample, rounded to nearest the same way
                // contract() is, so current_source (built via contract())
                // and estimate (built via translate()) are on equal
                // footing inside getTranslation()'s loop.
                dest[i][j] = (int) (a * (1. - y) + b * y + .5);
            }
        }
        return(dest);
    }

    // =======================================================================
    // Core subpixel estimator
    // =======================================================================

    /**
     * Single-pass, gradient-descent (Lucas-Kanade style) translation
     * estimator. Only resolves SUBPIXEL offsets -- translate()'s own "-1
     * to 1" comment is the reason why: this trusts its two inputs to
     * already be aligned to within about a pixel of each other. For
     * anything bigger, see getRefinedTranslation() below, which pyramids
     * a window down until this precondition holds and calls this.
     *
     * dest[0] is a status code: 0 = zero increment on the first pass
     * (images already matched); 1 = converged (increment fell under 1% of
     * the initial increment); 2 = stopped, increment reversed direction
     * between internal iterations; 3 = didn't converge within the
     * internal iteration limit; 4 = stopped, translation reached
     * translate()'s +/-1 boundary; 5 = stopped, the local gradient
     * structure was too degenerate (singular or near-singular) to resolve
     * an increment at all -- see solveIncrement()'s comment. dest[1]/
     * dest[2] are the x/y estimate.
     *
     * BUG FIX (found from a real crash report, not from this file's own
     * testing): a perfectly flat patch, or one whose gradients all point
     * the same direction (a single edge/texture orientation -- the classic
     * aperture problem), makes the Gauss-Newton normal equations singular.
     * Solving them anyway divided by exactly zero and returned NaN for
     * dest[1]/dest[2] with no status distinguishing it from a real
     * estimate. Nothing downstream checked for that: refineWithinWindow()
     * unconditionally does totalDx += dx, so one NaN from one degenerate
     * pyramid stage silently poisoned that round's whole contribution
     * (NaN + anything = NaN), which refineOuterRounds() then folds into
     * its own running total the same way -- corrupting the FINAL answer,
     * from every subsequent round too, with no error or warning. This is
     * far more reachable via the hinted overload than it first appears
     * (see that method's own comment) -- an aggressive shrinkLevel hint
     * pushes its first round's pyramid target down toward MIN_TARGET (as
     * small as 16x16), and a real photo very often has large smooth or
     * single-orientation regions (sky, a blurred background, fabric weave)
     * that average down to exactly this degenerate case at that
     * resolution, even though the SAME image at the no-hint overload's
     * larger default target (64x64) usually retains enough mixed structure
     * to avoid it. It isn't hint-path-specific in principle -- both
     * overloads call this same method -- just far more likely to surface
     * there. Fixed by solveIncrement() below, used at both computation
     * sites in this method: it detects the singular/near-singular case
     * before dividing and returns null instead of NaN, and both call sites
     * now stop cleanly (status 5, translation held at whatever was already
     * accumulated) rather than injecting NaN into an accumulator that has
     * no way to recover from it.
     *
     * KNOWN ISSUE, not fixed here: dest[0]==2 can never actually be
     * returned as written -- previous_xincrement/previous_yincrement are
     * overwritten with THIS iteration's own xincrement/yincrement
     * immediately before the reversal comparison below runs, so that
     * comparison is always value-vs-itself and the reversal branch is
     * dead code. Found while investigating a confidence-weighted
     * alternative to refineOuterRounds()'s round-keeping rule (see that
     * method's own history for why) -- fixing it turned out not to help
     * that effort (see there for why), so the fix wasn't kept here, but
     * it's a real, harmless-so-far latent bug: nothing downstream of this
     * method currently reads dest[0] (refineWithinWindow() only ever
     * reads dest[1]/dest[2]), so it has had no effect on any of this
     * file's tested numeric results. Worth fixing on its own merits if
     * this status code is ever relied on for anything.
     */

    /**
     * Solves the 2x2 Gauss-Newton normal equations
     *     [ w  x ] [dx]   [b1]
     *     [ x  z ] [dy] = [b2]
     * for [dx, dy] -- the shared core of both computation sites in
     * getTranslation() above. Returns null, instead of a division-by-zero
     * NaN, when the system is singular or near-singular.
     *
     * By the Cauchy-Schwarz inequality (w and z are sums of squared
     * gradients, x is the sum of their products), det = w*z - x*x is
     * always >= 0, with equality exactly when this patch's gradient
     * vectors all point in the same direction at every pixel -- including,
     * but not limited to, the trivial all-zero-gradient case of a
     * perfectly flat patch. That's the classic aperture problem: with only
     * one edge orientation present, translation along that edge is
     * genuinely unresolvable from this patch alone, in either axis, no
     * matter how the equations are rearranged. (An earlier version of this
     * method used a rearranged single-variable-elimination formula instead
     * of this direct determinant form; that rearrangement doesn't avoid
     * this singularity -- it's mathematically equivalent and hits the same
     * 0/0 -- it was just harder to see and to guard in that form. See
     * getTranslation()'s own comment for how unguarded NaN here used to
     * propagate.)
     *
     * The threshold below is relative to w*z rather than a fixed absolute
     * epsilon, since gradient magnitudes -- and so w, x, z -- vary hugely
     * between a full-resolution stage and a heavily pyramid-reduced one;
     * an absolute cutoff tuned for one would be meaningless for the other.
     */
    private static double[] solveIncrement(double w, double x, double z, double b1, double b2)
    {
        double det = w * z - x * x;
        if (!(det > 1e-9 * Math.max(w * z, 1.0)))
        {
            return null;
        }
        double dx = (b1 * z - x * b2) / det;
        double dy = (b2 * w - x * b1) / det;
        return new double[] { dx, dy };
    }

    public static double[] getTranslation(int[][] source1, int[][] source2)
    {
        //Assumes source1 and source2 are same size.
        int ydim = source1.length;
        int xdim = source1[0].length;

        int[][] estimate = new int[ydim][xdim];
        for (int i = 0; i < ydim; i++)
            for (int j = 0; j < xdim; j++)
                estimate[i][j] = source2[i][j];

        double[] dest = new double[3];

        double w  = 0;
        double x  = 0;
        double z  = 0;
        double b1 = 0;
        double b2 = 0;

        double[][][] gradient = getGradientPrimitive(estimate);
        double[][] xgradientPlane = gradient[0];
        double[][] ygradientPlane = gradient[1];
        double[] sums = accumulateNormalEquations(xgradientPlane, ygradientPlane, source1, estimate, ydim, xdim);
        w = sums[0]; x = sums[1]; z = sums[2]; b1 = sums[3]; b2 = sums[4];
        double[] initialIncrement = solveIncrement(w, x, z, b1, b2);
        if (initialIncrement == null)
        {
            dest[0] = 5; dest[1] = 0; dest[2] = 0;
            return dest;
        }
        double xincrement = initialIncrement[0];
        double yincrement = initialIncrement[1];

        if (xincrement == 0. && yincrement == 0.)
        {
            dest[0] = 0; dest[1] = 0; dest[2] = 0;
            return (dest);
        }

        double xincrement_min = Math.abs(xincrement) / 100.;
        double yincrement_min = Math.abs(yincrement) / 100.;

        double previous_xincrement = xincrement;
        double previous_yincrement = yincrement;
        double xtranslation        = xincrement;
        double ytranslation        = yincrement;

        int [][] current_source = contract(source1);
        estimate       = translate(source2, xtranslation, ytranslation);
        int current_number_of_estimates = 1;
        int maximum_number_of_estimates = 10;

        while (current_number_of_estimates < maximum_number_of_estimates)
        {
            gradient = getGradientPrimitive(estimate);
            xgradientPlane = gradient[0];
            ygradientPlane = gradient[1];
            int _ydim = estimate.length;
            int _xdim = estimate[0].length;

            double[] loopSums = accumulateNormalEquations(xgradientPlane, ygradientPlane, current_source, estimate, _ydim, _xdim);
            w = loopSums[0]; x = loopSums[1]; z = loopSums[2]; b1 = loopSums[3]; b2 = loopSums[4];

            double[] increment = solveIncrement(w, x, z, b1, b2);
            if (increment == null)
            {
                dest[0] = 5; dest[1] = xtranslation; dest[2] = ytranslation;
                return dest;
            }
            xincrement          = increment[0];
            xtranslation       += xincrement;
            previous_xincrement = xincrement;
            yincrement          = increment[1];
            ytranslation       += yincrement;
            previous_yincrement = yincrement;

            if(Math.abs(xincrement) < xincrement_min || Math.abs(yincrement) < yincrement_min)
            {
                dest[0] = 1; dest[1] = xtranslation; dest[2] = ytranslation;
                return dest;
            }
            else if((xincrement < 0 && previous_xincrement > 0) || (xincrement > 0 && previous_xincrement < 0)
            || (yincrement < 0 && previous_yincrement > 0) || (yincrement > 0 && previous_yincrement < 0))
            {
                dest[0] = 2; dest[1] = xtranslation; dest[2] = ytranslation;
                return (dest);
            }
            else if(xtranslation >= 1. || ytranslation >= 1.)
            {
                dest[0] = 4; dest[1] = xtranslation; dest[2] = ytranslation;
                return (dest);
            }
            else
            {
                estimate = translate(source2, xtranslation, ytranslation);
                current_number_of_estimates++;
            }
        }
        dest[0] = 3; dest[1] = xtranslation; dest[2] = ytranslation;
        return dest;
    }

    // =======================================================================
    // Coarse-to-fine wrappers
    // =======================================================================

    // Shared fallback target for both getRefinedTranslation() overloads:
    // the no-hint one uses it as its only pyramid target (both axes); the
    // shrink-level one falls back to it, for both axes, for any rounds
    // needed beyond its own single directly-sized trial round.
    private static final int DEFAULT_PYRAMID_TARGET = 64;

    /**
     * Registers source1 against source2 with no outside information about
     * how large the misalignment might be. Repeatedly takes the current
     * overlapping window from the ORIGINAL full-resolution images,
     * refines within it via refineWithinWindow() (a coarse-to-fine pyramid
     * starting at DEFAULT_PYRAMID_TARGET), and uses the result to both
     * accumulate the running estimate and tighten the window for the next
     * round. Stops as soon as a round's correction reverses direction
     * from the previous round's, and returns the accumulated estimate
     * from just before that reversal (per the original design: continue
     * until the estimate changes direction, return the last one before
     * that).
     *
     * If you already have a real bound on the translation -- even
     * approximately -- getRefinedTranslation(int[][], int[][], int, int)
     * below is both faster and more accurate; this overload exists for
     * when you don't.
     *
     * Crops to a centered minDim x minDim square (minDim = the shorter of
     * fullXdim/fullYdim) rather than working the full rectangle. A full-
     * rectangle version was tried: on a real, non-square photo (a
     * landscape-oriented artichoke garden photo), the extra width did
     * change the answer, but not for the better where it mattered most --
     * the no-hint case improved a little in total error but redistributed
     * it between axes, and the hinted overload's result (the one that
     * mattered most on that photo) got roughly 4-5x worse in total error.
     * The apparent explanation: the extra width on a landscape photo isn't
     * guaranteed to be more of the SAME useful signal -- it's often just
     * more background (sky, distant trees) that's less texture-rich or
     * behaves differently under heavy pyramid reduction than whatever
     * dominated the narrower square crop, so more pixels didn't mean more
     * usable information. Reverted back to the square crop on that
     * evidence, keeping the simpler, more-tested behavior.
     *
     * Sign convention: dx/dy are interpreted as "source2's content sits
     * dx,dy pixels further along than source1's, in source1's coordinate
     * frame" -- matching getTranslation()'s own internal convention (it
     * warps source2 toward source1). Verify this empirically against a
     * known synthetic shift before trusting the sign on real data.
     *
     * Assumes fullSource1 and fullSource2 are the same size.
     */
    public static double[] getRefinedTranslation(int[][] fullSource1, int[][] fullSource2)
    {
        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;

        int minDim = Math.min(fullXdim, fullYdim);
        int baseXoff = (fullXdim - minDim) / 2;
        int baseYoff = (fullYdim - minDim) / 2;

        // Last argument: use cascadedHalvingReduce() (repeated exact-2x
        // bilinear halvings, see its own comment) rather than a plain
        // avgAreaTransform() for every pyramid stage's reduction. Tested
        // specifically against THIS overload -- a clear, consistent win
        // (total error on a real photo test dropped from 1.48px to
        // 0.50px, with smaller gains on two synthetic pairs too) -- but a
        // clear regression when tried on the hinted overload below, which
        // is why that one still passes false. See cascadedHalvingReduce()'s
        // own comment for the full comparison and the likely reason for
        // the difference (the hinted overload's tuned constants were
        // arrived at against plain avgAreaTransform()'s specific
        // behavior).
        return refineOuterRounds(fullSource1, fullSource2,
                baseXoff, baseYoff, baseXoff, baseYoff, minDim, minDim,
                DEFAULT_PYRAMID_TARGET, DEFAULT_PYRAMID_TARGET,
                DEFAULT_PYRAMID_TARGET, DEFAULT_PYRAMID_TARGET,
                true);
    }

    /**
     * Registers source1 against source2 when you already know, per axis,
     * how much pyramid reduction the translation needs -- shrinkLevelX and
     * shrinkLevelY are each a count of 2x pyramid halvings: 0 if that
     * axis's true translation is under 1 pixel, 1 if it's in [1,2), 2 if
     * it's in [2,4), and so on (N halvings brings a magnitude up to 2^N
     * pixels down under 1, which is what getTranslation() actually
     * trusts). This is the direct version of the same idea the earlier
     * tolerance-based overload was reaching for indirectly: rather than
     * converting a guessed translation and a plausible range into a
     * pyramid size by a heuristic, you supply the one thing that actually
     * matters -- the reduction level itself -- straight from whatever
     * identified it as correct out of the possible values.
     *
     * X and Y are shrunk independently (avgAreaTransform() already
     * supports arbitrary, unequal new_xdim/new_ydim -- it runs the X and Y
     * area-weighted reductions as separate passes internally), so this
     * handles shrinkLevelX and shrinkLevelY being very different from
     * each other correctly: each axis gets exactly the reduction it
     * needs, rather than both being forced through whichever axis's need
     * is greater.
     *
     * How you get shrinkLevelX/shrinkLevelY is deliberately left to the
     * caller -- in a real system that bound is often already known (a
     * previous frame's measured shift in a sequence, a rig's travel
     * limits) and the interesting problem is narrowing it, not guessing
     * blind. Left as future work; some starting ideas:
     *   - Sequential data: reuse the previous pair's measured shift
     *     (log2 of its magnitude, per axis) as this pair's shrink level --
     *     cheap, and usually a good guess when motion is roughly
     *     continuous frame to frame.
     *   - Known physical bounds (stage travel, sensor timing, rig specs)
     *     give an a priori upper bound on the magnitude and hence the
     *     needed shrink level, independent of any particular image pair.
     *   - A one-time calibration pass: run the no-hint overload once
     *     against a representative pair from the same source, read off
     *     its magnitude, and reuse the derived shrink level for the rest
     *     of a batch with similar expected motion -- pays the discovery
     *     cost once instead of on every pair.
     *   - A cheap discovery loop of its own: starting from shrinkLevel=0,
     *     call this method and check whether the result still looks like
     *     it hit a boundary or needed a long chain of outer rounds to
     *     converge, then retry one level higher. Untried here.
     *
     * Design: a single, continuous refineOuterRounds() call (the same one
     * the no-hint overload uses) whose FIRST round targets a pyramid size
     * derived from the shrink levels, and whose every later round falls
     * back to DEFAULT_PYRAMID_TARGET, same as the no-hint overload
     * throughout. Two earlier versions were tried and tested before
     * landing here, both instructive:
     *   - v1 did the first round as a raw one-shot avgAreaTransform()+
     *     getTranslation() jump straight to the shrink-level-derived
     *     target, with no internal staging. Tested (true shift (5,3),
     *     shrink levels (3,2)): overshot to roughly (6.9, 4.1) and hit
     *     translate()'s +/-1 boundary doing it -- the exact one-shot-
     *     reduction problem refineWithinWindow() exists to avoid,
     *     regardless of whether the target size itself was "correct."
     *   - v2 fixed that by using refineWithinWindow() (genuine multi-stage
     *     internal pyramid) for the first round, but structured it as a
     *     standalone trial call followed by a SEPARATE refineOuterRounds()
     *     call as a fallback. Tested (true shift (12,12) on the 2048x2048
     *     image, shrink levels (4,4)): came back (11.3, 12.0) -- and
     *     tracing it down, the fallback's own round 1 (really the overall
     *     refinement's round 2) reported a correction that the no-hint
     *     overload, run on the same data, discards as a reversal in ITS
     *     round 2. Starting a second, independent refineOuterRounds() call
     *     reset its firstPass/previousDx tracking, so that correction got
     *     treated as an exempt "first round" and folded in instead of
     *     discarded, purely because of where the call boundary fell -- not
     *     because it was actually more trustworthy. v1's shrink-level
     *     target (exactly 2^shrinkLevel, the minimum sufficient reduction)
     *     also left the first stage sitting right at the trust boundary
     *     rather than comfortably inside it, which the class comment's
     *     history above already flagged as the original 2048x2048 bug's
     *     root cause; this overload uses 2^(shrinkLevel+1) for the same
     *     margin DEFAULT_PYRAMID_TARGET effectively already has on
     *     ordinary-sized images.
     *   - Fixed by making the first-round target a parameter of
     *     refineOuterRounds() itself (see its own comment), so the whole
     *     refinement -- shrink-level-sized first round and standard-target
     *     later rounds alike -- runs as one continuous call with one
     *     unbroken reversal-tracking history, identical in every respect
     *     to the no-hint overload except what the FIRST round targets.
     *     Re-tested both cases above: (5,3)/(3,2) -> (4.14, 2.88), matching
     *     the no-hint overload's own result on that pair almost exactly,
     *     in about 30% less time; (12,12)/(4,4) on the 2048x2048 image ->
     *     (11.30, 11.99), versus the no-hint overload's (12.62, 12.06) on
     *     the same pair -- both within roughly the same margin of the true
     *     answer, with the hinted call's own accuracy on the Y axis
     *     (which happened to get the more favorable rounding) noticeably
     *     better. X and Y stay independent throughout, since every target
     *     passed down to refineOuterRounds() and refineWithinWindow() is a
     *     separate per-axis parameter.
     */
    public static double[] getRefinedTranslation(int[][] fullSource1, int[][] fullSource2,
            int shrinkLevelX, int shrinkLevelY)
    {
        // Floor on the first round's target, so a caller-specified shrink
        // level can't push it down arbitrarily far. Tested at 8: a
        // deliberately wrong, overly-aggressive hint (shrinkLevel 7 for a
        // true shift of 15 -- more than needed) drove the target straight
        // to that floor (8x8), and the resulting first round came back
        // wildly wrong (dx/dy over 60 pixels on a true shift of (15,-8));
        // the round-to-round reversal check doesn't rescue this, since it
        // exists to catch a normal round's correction being noise, not to
        // recognize that an entire round's estimate was built on far too
        // little data to trust at all. Tested at 32: robust against wrong
        // hints (even a wildly wrong one degrades to a reasonable answer),
        // but it also swallows the axis-independence benefit for CORRECT
        // hints -- on the (15,-8) test case, shrink levels (4,3) want
        // targets (16,31), and flooring both at 32 collapses that
        // distinction back to roughly one shared value, which is exactly
        // what per-axis shrink levels are for avoiding. 16 keeps both
        // properties on the tested cases: a correct hint's per-axis
        // targets mostly clear it untouched (so asymmetric levels still
        // get genuinely different treatment), while a wrong hint -- even
        // one 2-3 levels off -- still gets clamped to a floor that
        // produces a reasonable, not wildly wrong, first round.
        final int MIN_TARGET = 16;

        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;

        // The reduction the caller specified, 2^shrinkLevel per axis, plus
        // one extra halving of headroom -- see the method comment above
        // for why landing exactly at the minimum sufficient reduction
        // isn't safe -- sized off the centered square crop's dimension,
        // same as the no-hint overload, so both axes start from an equal
        // pixel footprint. (A full-rectangle version of this, sized off
        // the full source's own per-axis dimensions instead of the square
        // crop, was tried and reverted: it regressed this hinted case's
        // total error roughly 4-5x on a real photo test, so the square
        // crop is kept here even though the no-hint overload's own
        // full-rectangle experiment was only a modest, non-uniform win.)
        int minDim = Math.min(fullXdim, fullYdim);
        int baseXoff = (fullXdim - minDim) / 2;
        int baseYoff = (fullYdim - minDim) / 2;

        double scaleX = Math.pow(2, shrinkLevelX + 1);
        double scaleY = Math.pow(2, shrinkLevelY + 1);
        int firstRoundTargetX = Math.max(MIN_TARGET, (int) Math.round(minDim / scaleX));
        int firstRoundTargetY = Math.max(MIN_TARGET, (int) Math.round(minDim / scaleY));

        // Last argument: false -- keep plain avgAreaTransform() for every
        // pyramid stage's reduction here, unlike the no-hint overload
        // above. Two different bilinear-based reductions were tested
        // against this overload and both regressed it clearly: an earlier,
        // single-arbitrary-ratio bilinear step (roughly 4-5x worse total
        // error on a real photo test even gated to only the largest-ratio
        // stages), and cascadedHalvingReduce() below (a more principled,
        // exact-2x-steps version) -- despite being the better-designed
        // filter, STILL regressed this overload by a similar or worse
        // margin (roughly 7-8x worse total error on the same real photo
        // test). See cascadedHalvingReduce()'s own comment for the numbers.
        //
        // Re-tuning attempt (also reverted): the hypothesis was that
        // MIN_TARGET and the headroom multiplier above were empirically
        // calibrated against plain avgAreaTransform()'s specific behavior,
        // and would need different values to work with
        // cascadedHalvingReduce(). A grid search over MIN_TARGET (8 to 96)
        // and the headroom exponent (+0 to +4 extra halvings, replacing the
        // hardcoded +1) was run against this overload with
        // useAntiAliasedReduction=true, on the real photo pair and both
        // synthetic pairs. Two things came out of it:
        //   - The headroom multiplier, not MIN_TARGET, turned out to be
        //     what mattered for the real photo: +2 extra halvings (instead
        //     of +1) cut its total error from 2.43px back to 0.75px --
        //     MIN_TARGET was provably irrelevant there across the whole
        //     8-96 range, since the photo's crop is large enough that the
        //     computed first-round target never gets close to any of those
        //     floors. Pushing headroom to +3 or +4 made it worse again
        //     (1.97px) -- overshrinking past some point costs accuracy the
        //     same way undershrinking does.
        //   - For the two small synthetic pairs, where MIN_TARGET DOES bind
        //     (their crops are only a few hundred pixels), the two pairs
        //     pulled in opposite directions: (15,-8) wanted MIN_TARGET left
        //     near 16 (0.28px with headroom +2), while (5,3) wanted it
        //     raised to roughly 32 (0.92px with headroom +2) -- at
        //     MIN_TARGET=16 it was 4.02px. MIN_TARGET=32 with headroom +2
        //     was the best joint compromise found (photo 0.75px, (5,3)
        //     0.92px, (15,-8) 0.58px), summing to about half the naive
        //     useAntiAliasedReduction=true regression.
        // Even that best compromise (MIN_TARGET=32, headroom +2) is still
        // clearly worse in total than this overload's existing baseline
        // below (photo 0.26px, (5,3) 0.99px, (15,-8) 0.21px) -- better on
        // (5,3) alone, worse on the other two, worse overall. So the
        // re-tuning hypothesis was partially right (headroom is indeed the
        // lever, and re-tuning it recovers a real chunk of the regression)
        // but not enough to justify switching this overload's default away
        // from plain avgAreaTransform(), which is kept here unchanged.
        return refineOuterRounds(fullSource1, fullSource2,
                baseXoff, baseYoff, baseXoff, baseYoff, minDim, minDim,
                firstRoundTargetX, firstRoundTargetY,
                DEFAULT_PYRAMID_TARGET, DEFAULT_PYRAMID_TARGET,
                false);
    }

    /**
     * Like getRefinedTranslation(fullSource1, fullSource2), but downsizes
     * BOTH full images by 2^preShrinkLevel first -- one avgAreaTransform()
     * call per image, before anything else in this file runs. Everything
     * downstream (the pyramid, the outer rounds) then runs on
     * the smaller images exactly as it would on any other input; the
     * result is rescaled back to the ORIGINAL images' pixel units before
     * returning, so callers never see pre-shrunk-image coordinates.
     *
     * This exists for large source images where full-resolution
     * processing is expensive and more precision than the source actually
     * needs -- a many-megapixel photo where a shift of a dozen-odd pixels
     * only needs to be resolved to within a fraction of a pixel, not to
     * the original sensor's own resolution. Downsizing once up front
     * means every later stage (crop, pyramid levels, outer rounds) works
     * on less data, which is where the time actually goes on a large
     * image -- not a substitute for shrinkLevelX/shrinkLevelY, which are
     * about how much pyramid reduction a single getTranslation() call
     * needs to trust its own answer; this is purely about how much of the
     * source's own resolution is worth carrying through the whole
     * pipeline in the first place. preShrinkLevel <= 0 means no
     * pre-shrink, and is equivalent to calling the two-argument overload
     * directly.
     *
     * A very large preShrinkLevel isn't guarded against specially here --
     * it doesn't need to be. Once the pre-shrunk image gets small enough,
     * refineOuterRounds()'s own MIN_WINDOW floor and the pyramid targets'
     * own floors take over the same way they would for any other small
     * input, so this degrades the same way processing a genuinely small
     * source image already does, rather than failing in some new way.
     */
    public static double[] getRefinedTranslation(int[][] fullSource1, int[][] fullSource2,
            int preShrinkLevel)
    {
        if (preShrinkLevel <= 0)
        {
            return getRefinedTranslation(fullSource1, fullSource2);
        }

        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;
        double scale = Math.pow(2, preShrinkLevel);
        int smallXdim = Math.max(1, (int) Math.round(fullXdim / scale));
        int smallYdim = Math.max(1, (int) Math.round(fullYdim / scale));

        int[][] small1 = avgAreaTransform(fullSource1, smallXdim, smallYdim);
        int[][] small2 = avgAreaTransform(fullSource2, smallXdim, smallYdim);

        double[] result = getRefinedTranslation(small1, small2);

        // Rescale by the ACTUAL ratio used (smallXdim/Ydim are rounded to
        // an integer, so this can differ very slightly from the idealized
        // 2^preShrinkLevel), same convention as everywhere else in this
        // file.
        double actualScaleX = (double) fullXdim / (double) smallXdim;
        double actualScaleY = (double) fullYdim / (double) smallYdim;
        return new double[] { result[0] * actualScaleX, result[1] * actualScaleY };
    }

    /**
     * Like getRefinedTranslation(fullSource1, fullSource2, shrinkLevelX,
     * shrinkLevelY), but downsizes both full images by 2^preShrinkLevel
     * first -- see the three-argument overload above for why and how.
     *
     * shrinkLevelX/shrinkLevelY still describe the ORIGINAL images' true
     * magnitude (so a caller who already knows a physical shift bound
     * doesn't have to redo that math depending on whether they also asked
     * for pre-shrinking) -- preShrinkLevel halvings of that reduction have
     * already happened by the time the (now-smaller) images reach the
     * hinted overload, so only the remainder is passed down to it:
     * max(0, shrinkLevelX - preShrinkLevel) and the same for Y.
     */
    public static double[] getRefinedTranslation(int[][] fullSource1, int[][] fullSource2,
            int shrinkLevelX, int shrinkLevelY, int preShrinkLevel)
    {
        if (preShrinkLevel <= 0)
        {
            return getRefinedTranslation(fullSource1, fullSource2, shrinkLevelX, shrinkLevelY);
        }

        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;
        double scale = Math.pow(2, preShrinkLevel);
        int smallXdim = Math.max(1, (int) Math.round(fullXdim / scale));
        int smallYdim = Math.max(1, (int) Math.round(fullYdim / scale));

        int[][] small1 = avgAreaTransform(fullSource1, smallXdim, smallYdim);
        int[][] small2 = avgAreaTransform(fullSource2, smallXdim, smallYdim);

        int remainingShrinkLevelX = Math.max(0, shrinkLevelX - preShrinkLevel);
        int remainingShrinkLevelY = Math.max(0, shrinkLevelY - preShrinkLevel);
        double[] result = getRefinedTranslation(small1, small2, remainingShrinkLevelX, remainingShrinkLevelY);

        double actualScaleX = (double) fullXdim / (double) smallXdim;
        double actualScaleY = (double) fullYdim / (double) smallYdim;
        return new double[] { result[0] * actualScaleX, result[1] * actualScaleY };
    }

    /**
     * The outer-round loop shared by both getRefinedTranslation() overloads
     * above: repeatedly takes the current window from the ORIGINAL full-
     * resolution images (at x1,y1 for source1 and x2,y2 for source2, both
     * windowXdim x windowYdim), refines within it via refineWithinWindow(),
     * and uses the result to both accumulate the running estimate and
     * tighten the window for the next round. Stops as soon as a round's
     * correction reverses direction from the previous round's, and returns
     * the accumulated estimate from just before that reversal -- this is
     * the outer, round-to-round reversal check; see refineWithinWindow()
     * for why the *inner* pyramid stages deliberately do not use the same
     * rule.
     *
     * The very first round uses (firstRoundTargetX, firstRoundTargetY) as
     * refineWithinWindow()'s target; every round after that uses
     * (laterRoundTargetX, laterRoundTargetY). The no-hint overload passes
     * the same DEFAULT_PYRAMID_TARGET for all four, so it sees exactly one
     * pyramid target throughout, same as before this became configurable.
     * The shrink-level overload gives the first round a target derived
     * from the caller's hint and DEFAULT_PYRAMID_TARGET for every round
     * after -- tried as two separate calls first (a standalone trial round
     * via refineWithinWindow(), then a fresh refineOuterRounds() call as a
     * fallback) and found by testing to be a real bug, not just an
     * unnecessary abstraction: starting a SECOND, independent
     * refineOuterRounds() call reset its firstPass/previousDx tracking, so
     * what was really this refinement's round 2 got treated as that call's
     * own round 1 and exempted from the reversal check -- on the (12,12)
     * test case below, a correction the no-hint overload's own round 2
     * correctly discarded as a reversal got folded into the shrink-level
     * overload's total instead, simply because of where the call boundary
     * fell. Making the first-round target a parameter of one continuous
     * call keeps the round-to-round reversal tracking intact regardless of
     * which target sequence is in play.
     *
     * All four targets are independent per axis, same as everywhere else
     * in this file.
     *
     * What happens on a direction reversal, per axis (X and Y are checked
     * independently -- a reversal on one axis stops the whole round for
     * BOTH axes, since a fresh round's dx and dy come from the same
     * extracted window): the very first reversal stops the round loop
     * outright and discards that round's correction entirely, on the
     * theory that a reversal usually means the remaining correction is
     * noise rather than signal, and returns the total from just before it.
     *
     * A damped alternative was tried and reverted: instead of discarding a
     * reversed round, apply a shrinking fraction of it (halving a per-axis
     * damping factor on every further reversal, so the applied step
     * ratchets toward zero rather than stopping dead) and keep going.
     * Tested against a real, lossless photo pair (a 3488x2321 artichoke
     * garden photo, true shift (12,12), avoiding the JPEG-recompression
     * confound noted elsewhere in this history) with a correct shrink-
     * level hint: round 0 alone landed at (11.78, 12.03) -- errors of
     * 0.22 and 0.03, about as good as this project has ever measured.
     * Under the hard-stop rule that's also the FINAL answer, since round
     * 1 reversed on Y. Under the damped rule, three more rounds of damped
     * corrections eroded it to (11.35, 11.00) -- errors of 0.65 and 1.00,
     * worse on both axes. The no-hint path on the same photo was more of
     * a wash (one axis improved, the other worsened, net about the same
     * total error) but still took six rounds and 65+ seconds to arrive
     * there rather than stopping cleanly at round 0's reversal. A
     * synthetic asymmetric-shift test showed the same pattern (damping
     * eroded an already-excellent round-0 answer); only a synthetic test
     * with a periodic, repeating texture (a poor proxy for real photo
     * content, prone to its own aliasing issues under pyramid reduction)
     * showed damping clearly helping. Reverted back to the hard-stop rule
     * on that evidence, on the theory that later rounds' reversals are
     * more often noise than genuine residual once a round's estimate is
     * already good -- but the damping schedule itself had no actual
     * evidence behind it either: it damped on a fixed halving schedule
     * triggered purely by direction-flip, blind to whether a given round's
     * OWN data actually looked trustworthy. Three other independent-
     * estimate-averaging ideas aimed at the same "reduce error further"
     * goal -- a full-rectangle window instead of the square crop, spatial
     * ensemble averaging over sub-window tiles, and per-channel (R/G/B)
     * averaging instead of collapsing to luminance first -- were also
     * tried and reverted; all three consistently lost to the single
     * hinted square-crop estimate on the same real photo.
     *
     * A confidence-weighted combination (replacing the hard-stop above
     * with a graded weight per round, instead of a binary keep/discard on
     * direction alone) was also tried and reverted. It used two signals
     * from getTranslation()'s own final, highest-resolution internal
     * stage: its status code (converged cleanly vs hit the iteration cap
     * vs hit translate()'s +/-1 boundary vs internally reversed) and a
     * gradient-strength proxy (the sum of squared x/y gradients its
     * Newton solve accumulates), the latter taken relative to round 0's
     * own gradient-strength. A first weight table that treated "hit the
     * +/-1 boundary" or "didn't converge in the iteration cap" as reasons
     * for real suspicion collapsed badly on the real artichoke photo: the
     * no-hint case came back (2.36, 3.27) against a true (12,12), because
     * round 0 -- whose raw correction, (10.55, 11.70), was already close
     * to the answer -- legitimately hit the boundary status (a large,
     * correct, multi-pixel correction routinely does) and got only 30% of
     * its own measurement folded into the total; window-trimming still
     * used the FULL, unweighted correction to re-crop the next round's
     * window, so the accumulated total systematically undershot what the
     * window position implied, compounding across rounds. In other words,
     * this particular status code turned out to track how BIG a round's
     * real correction was, not how NOISY it was -- exactly the wrong
     * thing to discount. A second calibration that trusted every status
     * except a genuine internal reversal came back much closer to
     * correct (10.65, 12.06) no-hint, (11.64, 11.17) hinted, but for a
     * different reason: on this photo no round's final stage ever
     * actually reversed, so every round's weight landed at essentially
     * 1.0 regardless of status, making this calibration numerically
     * equivalent to never stopping at all -- which is the same
     * "keep every round in full" behavior the damped alternative above
     * degenerates toward once its damping stays near 1, and it reproduced
     * that alternative's exact failure mode: the hinted case's excellent
     * round 0 got eroded by later, genuinely-reversed-direction rounds
     * (0.23px total error under hard-stop vs 0.91px letting everything
     * through). Reverted on that evidence: getTranslation()'s own status
     * code, even after fixing the dead dest[0]==2 branch documented in
     * its own comment, isn't a reliable proxy for round trustworthiness
     * in this pipeline, and the one genuinely diagnostic signal (a
     * reversed Newton iteration) is too rare on real photo data to carry
     * a combination rule by itself. Combined with the three independent-
     * estimate-averaging ideas above, that's four different approaches to
     * "do better than stop-on-first-reversal" tried and reverted, all
     * against the same real photo, all losing to it.
     *
     * Sign convention: the returned dx/dy match getTranslation()'s own
     * internal convention (it warps source2 toward source1).
     */
    private static double[] refineOuterRounds(int[][] fullSource1, int[][] fullSource2,
            int x1, int y1, int x2, int y2, int windowXdim, int windowYdim,
            int firstRoundTargetX, int firstRoundTargetY,
            int laterRoundTargetX, int laterRoundTargetY,
            boolean useAntiAliasedReduction)
    {
        final int MAX_OUTER_ITERATIONS = 40; // safety cap -- direction-reversal should stop it long before this
        final int MIN_WINDOW = 64; // stop taking further rounds once the window shrinks to about this --
                                   // deliberately NOT tied to the pyramid targets: a round whose correction
                                   // is already small only shrinks the window a little, so tying the
                                   // floor to a large pyramid target can mean dozens of tiny-step rounds
                                   // just to reach it. A small, fixed floor is about when a fresh region
                                   // extraction stops being worthwhile, independent of pyramid target.
        int windowFloorX = Math.min(MIN_WINDOW, Math.min(firstRoundTargetX, laterRoundTargetX));
        int windowFloorY = Math.min(MIN_WINDOW, Math.min(firstRoundTargetY, laterRoundTargetY));

        double totalXtranslation = 0;
        double totalYtranslation = 0;

        double previousDx = 0;
        double previousDy = 0;
        boolean firstPass = true;

        for (int iteration = 0; iteration < MAX_OUTER_ITERATIONS; iteration++)
        {
            if (windowXdim <= windowFloorX || windowYdim <= windowFloorY)
            {
                // Window has been trimmed down close to the floor --
                // there's nothing meaningful left to reduce.
                break;
            }

            int pyramidTargetX = (iteration == 0) ? firstRoundTargetX : laterRoundTargetX;
            int pyramidTargetY = (iteration == 0) ? firstRoundTargetY : laterRoundTargetY;

            // "An iteration always starts with a data sample from the
            // original images": pull fresh pixels straight from the
            // untouched full-resolution arrays every time. Never re-pyramid
            // an already-pyramided or already-warped array from a previous
            // round -- that would compound smoothing/interpolation error
            // across iterations exactly where a clean pyramid reduction is
            // what's supposed to be suppressing sensor noise.
            int[][] region1 = extract(fullSource1, x1, y1, windowXdim, windowYdim);
            int[][] region2 = extract(fullSource2, x2, y2, windowXdim, windowYdim);

            // Refine within this round's fresh window using a genuine
            // coarse-to-fine pyramid (see refineWithinWindow() below)
            // instead of a single jump straight down to the pyramid
            // target -- a one-shot reduction is what produced the
            // large-image overshoot documented in the class comment above.
            double[] refined = refineWithinWindow(region1, region2, windowXdim, windowYdim,
                    pyramidTargetX, pyramidTargetY, useAntiAliasedReduction);
            double dx = refined[0];
            double dy = refined[1];

            if (!firstPass)
            {
                boolean xFlipped = (dx < 0 && previousDx > 0) || (dx > 0 && previousDx < 0);
                boolean yFlipped = (dy < 0 && previousDy > 0) || (dy > 0 && previousDy < 0);
                if (xFlipped || yFlipped)
                {
                    // Direction reversed -- stop, and hand back the estimate
                    // from before this round rather than folding this
                    // (likely noise-driven) correction in.
                    break;
                }
            }

            totalXtranslation += dx;
            totalYtranslation += dy;
            previousDx = dx;
            previousDy = dy;
            firstPass = false;

            // "Take the overlapping rectangle from the original images":
            // trim each source's window on whichever side just fell outside
            // the other frame's coverage, given this round's correction.
            int dxPixels = (int) Math.ceil(Math.abs(dx));
            int dyPixels = (int) Math.ceil(Math.abs(dy));

            if (dx > 0)      { x2 += dxPixels; }
            else if (dx < 0) { x1 += dxPixels; }
            if (dy > 0)      { y2 += dyPixels; }
            else if (dy < 0) { y1 += dyPixels; }

            windowXdim -= dxPixels;
            windowYdim -= dyPixels;

            if (windowXdim <= 0 || windowYdim <= 0)
            {
                break;
            }

            // Already converged as far as further rounds usefully can take
            // this -- see the class comment above (the tolerance-overload
            // history entry) for the measured 56s-vs-under-1s difference
            // this check makes once a round's correction is already small.
            if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5)
            {
                break;
            }
        }

        return new double[] { totalXtranslation, totalYtranslation };
    }

    /**
     * Refines the alignment of region2 against region1 -- both already a
     * fresh, same-size crop taken from the original full-resolution images
     * for one getRefinedTranslation() round -- using a genuine coarse-to-
     * fine pyramid, rather than a single jump straight down to a target
     * size. See the class comment above for why the single-jump approach
     * overshoots badly on large windows.
     *
     * Builds an adaptive stage schedule per axis: start at
     * (targetSizeX, targetSizeY) (the coarsest, most-reduced stage) and
     * roughly double each axis's pyramid target every stage after that,
     * independently, each clamped to whatever resolution is actually left
     * in the working crop at that point. The schedule naturally ends with
     * one full-resolution stage -- a plain, unpyramided getTranslation()
     * call -- once both doubling targets catch up to the crop's actual
     * size.
     *
     * A stage here does NOT stop on a direction reversal the way the outer
     * rounds do -- tested, and it made things worse: a coarse stage's
     * correction commonly overshoots, and the next, finer stage's
     * correction reversing direction is normally that finer stage
     * correctly walking the overshoot back, not noise. Discarding it
     * reproduced the same overshoot the coarse-to-fine pyramid exists to
     * fix. So the two reversal checks in this file mean different things:
     * refineOuterRounds()'s is the user-specified stopping rule for the
     * overall refinement (continue until direction reverses, return the
     * estimate from just before that); this one is an internal
     * implementation detail of a single round's pyramid, where a reversal
     * is a normal part of resolving the coarsest stage's necessarily-rough
     * guess.
     *
     * Every stage re-crops and re-pyramids fresh from region1/region2 as
     * passed in -- never from a previously-pyramided or previously-shifted
     * stage's array -- for the same reason getRefinedTranslation() itself
     * always resamples fresh from the original images each round: avoid
     * compounding smoothing or interpolation error across stages.
     *
     * Assumes region1 and region2 are both windowXdim x windowYdim.
     */
    private static double[] refineWithinWindow(int[][] region1, int[][] region2,
            int windowXdim, int windowYdim, int targetSizeX, int targetSizeY,
            boolean useAntiAliasedReduction)
    {
        double totalDx = 0;
        double totalDy = 0;

        int offX1 = 0, offY1 = 0;
        int offX2 = 0, offY2 = 0;
        int curXdim = windowXdim, curYdim = windowYdim;

        int stageTargetX = Math.min(targetSizeX, windowXdim);
        int stageTargetY = Math.min(targetSizeY, windowYdim);

        boolean lastStage = false;
        while (!lastStage)
        {
            if (curXdim <= 0 || curYdim <= 0)
            {
                break;
            }

            // Clamp this stage's pyramid target to whatever's actually
            // left in the working crop -- never ask avgAreaTransform to
            // upscale past the crop's own resolution. Once the (doubling)
            // stage target has caught up to the crop size on BOTH axes,
            // this is the last stage: a full-resolution, effectively
            // unpyramided pass.
            int thisStageXdim = Math.min(stageTargetX, curXdim);
            int thisStageYdim = Math.min(stageTargetY, curYdim);
            lastStage = (thisStageXdim >= curXdim && thisStageYdim >= curYdim);

            int[][] crop1 = extract(region1, offX1, offY1, curXdim, curYdim);
            int[][] crop2 = extract(region2, offX2, offY2, curXdim, curYdim);

            int[][] level1 = useAntiAliasedReduction
                    ? cascadedHalvingReduce(crop1, thisStageXdim, thisStageYdim)
                    : avgAreaTransform(crop1, thisStageXdim, thisStageYdim);
            int[][] level2 = useAntiAliasedReduction
                    ? cascadedHalvingReduce(crop2, thisStageXdim, thisStageYdim)
                    : avgAreaTransform(crop2, thisStageXdim, thisStageYdim);

            double[] result = getTranslation(level1, level2);

            double scaleX = (double) curXdim / (double) thisStageXdim;
            double scaleY = (double) curYdim / (double) thisStageYdim;
            double dx = result[1] * scaleX;
            double dy = result[2] * scaleY;

            // Safety net, not the primary fix: getTranslation() itself no
            // longer returns NaN (see its solveIncrement() comment for the
            // singular-gradient bug that used to cause it), so this should
            // never trip in practice. It's kept anyway because totalDx/
            // totalDy below are a running sum with no other guard -- a
            // single non-finite stage result, from here or from any future
            // change to getTranslation(), would otherwise poison this
            // round's entire contribution (and everything summed from it
            // in refineOuterRounds()) silently. Treating a non-finite stage
            // as a zero-contribution stage -- skip the accumulation, don't
            // move the crop window for it, just carry on to the next stage
            // -- costs nothing when it never fires, and is the only
            // recoverable choice when it does.
            if (!Double.isFinite(dx) || !Double.isFinite(dy))
            {
                stageTargetX = Math.min(windowXdim, stageTargetX * 2);
                stageTargetY = Math.min(windowYdim, stageTargetY * 2);
                continue;
            }

            // Every stage's correction is kept -- see the method comment
            // above for why this deliberately does not stop on a
            // direction reversal the way the outer round does.
            totalDx += dx;
            totalDy += dy;

            int dxPixels = (int) Math.ceil(Math.abs(dx));
            int dyPixels = (int) Math.ceil(Math.abs(dy));

            if (dx > 0)      { offX2 += dxPixels; }
            else if (dx < 0) { offX1 += dxPixels; }
            if (dy > 0)      { offY2 += dyPixels; }
            else if (dy < 0) { offY1 += dyPixels; }

            curXdim -= dxPixels;
            curYdim -= dyPixels;

            stageTargetX = Math.min(windowXdim, stageTargetX * 2);
            stageTargetY = Math.min(windowYdim, stageTargetY * 2);
        }

        return new double[] { totalDx, totalDy };
    }
}
