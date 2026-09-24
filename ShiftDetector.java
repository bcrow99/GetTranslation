import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import javax.imageio.ImageIO;

/**
 * A second coarse-to-fine wrapper around TranslateMapper.getTranslation(),
 * built on the same low-level primitives TranslateMapper.getRefinedTranslation()
 * uses (getTranslation() itself, extract(), avgAreaTransform()) but with a
 * deliberately different, simpler search strategy -- see the class-level
 * comparison below for how the two differ and why both exist.
 *
 * ==========================================================================
 * THE SEARCH: independent X/Y levels, one getTranslation() call per
 * (levelX, levelY) pair, exact power-of-two shrink factors per axis
 * ==========================================================================
 *
 * Unlike the original version of this class (which tracked a single,
 * isotropic "level" and always cropped a SQUARE segment sized off the
 * image's minor axis), this tracks levelX and levelY independently, and the
 * working segment is sized off each axis's OWN full dimension -- so it is
 * NOT generally square, and at (levelX, levelY) == (0, 0) it's the entire
 * image, full width and height, not a cropped square. This mirrors how
 * getTranslation()'s own status now works (see below) and how
 * getRefinedTranslation()'s hinted overload takes independent
 * shrinkLevelX/shrinkLevelY -- "like getTranslation," per axis, all the way
 * through, rather than forcing both axes to move together the way the
 * original single-level version did.
 *
 * A level pair (levelX, levelY) means: take a rectangle whose width is the
 * largest multiple of 2^levelX that fits within the full image width, and
 * whose height is the largest multiple of 2^levelY that fits within the
 * full image height, each centered on its own axis (offset = (fullDim -
 * side) / 2). That rectangle is extracted fresh from the ORIGINAL
 * full-resolution source images every time (never from a previously-shrunk
 * level -- avoids compounding averaging error, same reasoning
 * refineWithinWindow()'s own comment gives for always re-cropping from the
 * original), then shrunk by exactly 2^levelX horizontally and 2^levelY
 * vertically via avgAreaTransform() (an exact, evenly-divisible reduction
 * per axis, since each side was chosen to be a multiple of its own axis's
 * shrink factor). (levelX, levelY) == (0, 0) needs no special-casing for
 * the RECTANGLE math (2^0 == 1 imposes no divisibility constraint), but IS
 * special-cased to skip the avgAreaTransform() call entirely, since
 * shrinking by exactly 1x in both directions is a no-op.
 *
 * getTranslation() is then called once on that level pair's rectangle.
 * Since the TranslateMapper.getTranslation() status codes now distinguish
 * which axis (if any) actually reached translate()'s +/-1 boundary --
 * status 4 (X only), 5 (Y only), 6 (both) -- this class can react to
 * EXACTLY the axis that needs it, rather than inferring it indirectly:
 *
 *   - status 4 (only X crossed the boundary): the window's current X
 *     resolution is too fine for the true X shift. Bump levelX (shrink X
 *     more) and try again; levelY is untouched.
 *   - status 5 (only Y crossed the boundary): the mirror image of 4 --
 *     bump levelY only.
 *   - status 6 (both crossed): bump both levelX and levelY.
 *   - status 3 (indeterminate -- the local gradient structure was too
 *     degenerate to resolve, e.g. a flat or single-orientation patch): this
 *     status doesn't identify an axis at all (it's a property of the whole
 *     2x2 normal-equations system, not something separable per axis), so
 *     there's no analogous "which axis" signal to act on. As before: try
 *     ONE step finer on BOTH axes together (levelX-1, levelY-1, each
 *     clamped at 0) before falling back to the normal shrink-more
 *     progression -- flat/degenerate patches were found, during the
 *     original single-axis version's development, to become MORE likely
 *     under heavier shrinking, not less, so restoring detail is the fix
 *     rather than reducing it further. If that single finer attempt also
 *     fails to produce a clean subpixel estimate (indeterminate again, or
 *     a boundary crossing on either axis now that less shrinking made the
 *     true shift relatively larger), or if levelX and levelY are both
 *     already 0 (no finer pair exists), abandon the finer attempt and fall
 *     through to bumping BOTH levelX and levelY by one -- from the
 *     ORIGINAL pair, not the failed finer one. This keeps both levels
 *     strictly non-decreasing across outer-loop iterations (bounded, see
 *     below) even though one single finer side-attempt is allowed per
 *     indeterminate result.
 *   - status 2 (did not converge within getTranslation()'s internal
 *     iteration limit): like 3 and 6, this doesn't identify a specific
 *     axis either, so in searchCoarsening() it's treated the same way --
 *     as a "keep going" signal. Bump both levelX and levelY and try again,
 *     on the theory (borne out while comparing this class against
 *     getTranslation() directly on real test images) that continuing to
 *     shrink can still turn a stalled iteration into a clean convergence,
 *     or a better one, rather than settling for the first sub-pixel
 *     estimate that merely clears the status bar. This retry is bounded by
 *     its own, SHALLOWER floor -- STATUS_2_MIN_WORKING_SIZE, illustrated at
 *     64 below -- rather than the same MIN_WORKING_SIZE=8 floor used for
 *     3/4/5/6: a status-2 result is already a usable (if inconclusive)
 *     estimate, so there's less reason to keep pushing it all the way down
 *     to the absolute floor the way a boundary crossing or an
 *     indeterminate result is pushed. Once bumping either axis would take
 *     its working size below that shallower floor, the current status-2
 *     attempt is accepted as final -- understood as inconclusive, per
 *     getTranslation()'s own doc comment, not as a failure. This
 *     status-2-driven retry applies ONLY to searchCoarsening(); the
 *     last-resort searchFiner() fallback (see below) still accepts a
 *     status-2 result immediately, the same as 0 and 1, since by the time
 *     that path runs, the coarsening progression has already exhausted its
 *     own attempts to do better and there's no coarser direction left
 *     worth returning to.
 *   - status 0 or 1: a genuine estimate this class is willing to accept
 *     and return immediately, no further shrinking attempted.
 *
 *   Whichever status ultimately gets accepted, dest[1]/dest[2] are scaled
 *   by 2^levelX / 2^levelY respectively (the two axes' independent shrink
 *   factors at the level pair that was actually accepted) to convert back
 *   to original-pixel units.
 *
 * The search is bounded per axis: MIN_WORKING_SIZE is a floor on each
 * axis's OWN working size (fullXdim >> levelX, fullYdim >> levelY) --
 * shrinking whichever axis is being bumped past where its own working size
 * would fall under this floor makes the whole level pair invalid, at which
 * point the coarsening progression gives up (see STATUS_EXHAUSTED below).
 * MIN_WORKING_SIZE=8 is a reasonable floor by inspection (this codebase's
 * other pyramid floors -- getRefinedTranslation()'s hinted overload's
 * MIN_TARGET=16 among them -- are all in the same rough neighborhood), not
 * something separately tuned against real data the way MIN_TARGET was.
 * STATUS_2_MIN_WORKING_SIZE=64 is a second, shallower floor that gates
 * ONLY the status-2 retry described above -- an illustrative example value
 * per initial testing, not an empirically-tuned one either, chosen simply
 * to be well above MIN_WORKING_SIZE so a status-2 result gets some room to
 * improve with more shrinking without being pushed as far down as 3/4/5/6
 * are.
 *
 * A hinted starting level pair is first CLAMPED, independently per axis, to
 * the largest level that's still valid for that axis's own full dimension
 * -- so a wildly-oversized hint on one axis (or both) can never put the
 * search in an already-invalid starting state. If even level 0 is invalid
 * for an axis (that axis's full dimension is itself smaller than
 * MIN_WORKING_SIZE), the search gives up immediately (STATUS_EXHAUSTED) --
 * there's no usable level for that axis at all, hinted or not.
 *
 * Beyond that up-front clamp, the coarsening progression can still exhaust
 * -- for instance if every level from the (clamped) start up to the floor
 * genuinely can't resolve the true shift. As a last resort, the search
 * then tries levels BELOW the clamped starting pair, down to (0, 0),
 * stepping levelX and levelY down together in lockstep (each clamped at 0
 * once it gets there) rather than exploring the full 2D grid of
 * combinations below the start -- a deliberate simplification for what's
 * meant to be a rare last-resort path, not an exhaustive search; revisit if
 * it turns out to matter in practice. Only reachable when the clamped start
 * had room below it (clampedX > 0 or clampedY > 0); the no-hint entry point
 * always starts at (0, 0), so it never has anywhere lower to fall back to,
 * and STATUS_EXHAUSTED from the coarsening progression is final for it, same
 * as before. STATUS_EXHAUSTED (7) is a ShiftDetector-specific code, chosen
 * to sit right after TranslateMapper.getTranslation()'s own highest status
 * (6) rather than overlapping it, distinct from any of getTranslation()'s
 * own 0-6 so a caller can tell "gave up searching" apart from any of
 * getTranslation()'s own outcomes.
 *
 * ==========================================================================
 * HOW THIS DIFFERS FROM getRefinedTranslation()
 * ==========================================================================
 *
 * Both wrap the same core estimator and exist for the same basic reason
 * (getTranslation() alone only resolves sub-pixel offsets), but:
 *
 *   - getRefinedTranslation() accumulates a running total across multiple
 *     OUTER ROUNDS against a shrinking window, each round itself an inner
 *     coarse-to-fine PYRAMID of several stages, stopping when a round's
 *     correction reverses direction. ShiftDetector takes a single
 *     getTranslation() reading per level pair and trusts it outright -- no
 *     accumulation, no reversal-based stopping rule, no inner pyramid
 *     within a level.
 *   - getRefinedTranslation()'s hinted overload sizes its working window
 *     off a centered SQUARE crop (minDim x minDim) for both the no-hint and
 *     hinted cases -- its own comment notes that a full-rectangle version,
 *     sized off the source's own per-axis dimensions, was tried there and
 *     reverted (regressed accuracy roughly 4-5x on a real photo test in
 *     that method's own multi-round accumulating pyramid). ShiftDetector's
 *     working rectangle is instead sized off each axis's own FULL
 *     dimension independently, by design, per this rework -- a different
 *     choice for a structurally different (single-shot, non-accumulating)
 *     algorithm, not a claim that the same tradeoff getRefinedTranslation()
 *     found necessarily applies here too.
 *
 * Sign/scale convention matches getTranslation() and getRefinedTranslation()
 * throughout: dx/dy mean "source2's content sits dx,dy pixels further along
 * than source1's, in source1's coordinate frame." Assumes fullSource1 and
 * fullSource2 are the same size.
 *
 * ==========================================================================
 * getShiftRefined(): an experimental, CHAINED alternative to getShift()
 * ==========================================================================
 *
 * getShift() above takes one independent getTranslation() reading per level
 * pair and either accepts it outright or discards it entirely before trying
 * a different level -- nothing a discarded attempt found is ever reused.
 * getShiftRefined() instead chains levels together: whatever (dx, dy) comes
 * back at the current level is folded into a running total AND used to warp
 * the working "shifted" image toward the "reference" one (via
 * shiftBilinear() below) BEFORE shrinking to the next level -- so a level
 * that doesn't converge, or the correction from every level before it, is
 * never thrown away the way it is above. This is a direct answer to the
 * open question raised while comparing this class against
 * getRefinedTranslation() empirically: that method's own accumulating,
 * never-discard-a-round design is a real, structural reason it was
 * outperforming getShift() on the hinted comparison.
 *
 * shiftBilinear() is a deliberately NEW, general-purpose "shift this image
 * by (dx, dy) pixels, same size out" operator -- NOT a reuse of
 * TranslateMapper.translate(). translate() remaps its x,y argument through
 * a "-1 to 1" -> "0 to 1" blend and is calibrated specifically to pair with
 * contract() inside getTranslation()'s own internal iteration; this
 * project's own history already found, while building synthetic test
 * pairs earlier, that reusing translate() as a general "apply this shift"
 * tool bakes in a confusing offset unrelated to the actual shift. At
 * (dx, dy) = (0, 0), shiftBilinear() is a no-op, with no such offset.
 *
 * Status handling (deliberately provisional -- see the "definitely a
 * question mark" note below -- and currently exercised only against test
 * pairs shifted equally on both axes, so an asymmetric-shift regression in
 * here wouldn't necessarily show up yet):
 *
 *   - 0 or 1: real convergence. Stop, return the accumulated total.
 *   - 2: getTranslation() didn't converge within its own internal
 *     iteration cap. Folded into the total and returned anyway, rather
 *     than pushed toward more shrinking the way getShift()'s
 *     searchCoarsening() now does -- getTranslation() still returns a
 *     usable value even at its iteration cap, and by the time the chain
 *     reaches this level, the accumulated total already reflects every
 *     earlier level's correction, so this level is very likely refining an
 *     estimate already in the right neighborhood rather than starting
 *     blind. Returning status 2 unchanged IS the flag -- callers already
 *     know (see describeStatus()) that it means "usable, not fully
 *     converged."
 *   - 3: singular/indeterminate. Unlike searchCoarsening()'s one-step-
 *     finer peek, there's no retry here: by construction, this chain
 *     already passed through every finer level on its way to the current
 *     one, so a finer retry would just repeat work already done. Treated
 *     as a failure for now (STATUS_EXHAUSTED) -- a real gap, left
 *     deliberately unresolved until there's a concrete case that needs it.
 *   - 4, 5, or 6: fold this level's estimate into the total, warp the
 *     working "shifted" image by it, and shrink whichever axis (or both)
 *     the status names before trying again. X and Y still bump
 *     independently here, same as getShift() -- the warp step is
 *     necessarily a single joint operation over both axes at once (an
 *     image doesn't have an "X-only" warp), but that's a data-refinement
 *     detail, not a reason to collapse the level search itself back to one
 *     shared level.
 *
 * Definitely a question mark: getTranslation()'s own status codes were not
 * designed with this chained use in mind, and may need elaborating once
 * this is tested against asymmetric shifts -- for instance, a single
 * getTranslation() call currently can't report "X converged, Y hit the
 * boundary" even in principle (see getTranslation()'s own convergence
 * check, which is an OR across both axes' increments and is evaluated
 * before the boundary check), so a real per-axis-asymmetric case might
 * expose a joint status as too coarse for this chain to use well. Left
 * alone deliberately until equal-shift testing is solid and there's an
 * actual asymmetric case in hand to design against, rather than guessing
 * at the shape of that change now.
 */
public class ShiftDetector
{
    // Floor on each axis's OWN working size (post-shrink) before that axis
    // can no longer be bumped further -- see the class comment above for
    // why this specific number is a reasonable-by-inspection choice rather
    // than an empirically-tuned one.
    private static final int MIN_WORKING_SIZE = 8;

    // Shallower floor that gates ONLY searchCoarsening()'s status-2 "keep
    // going" retry (see the class comment above) -- distinct from, and well
    // above, MIN_WORKING_SIZE. 64 is an illustrative example value from
    // initial testing, not empirically tuned.
    private static final int STATUS_2_MIN_WORKING_SIZE = 64;

    // ShiftDetector-specific status, returned instead of a getTranslation()
    // status when the level search exhausts itself (see the class comment's
    // "bounded" paragraph) without ever landing a clean estimate. Chosen to
    // sit right after getTranslation()'s own highest status code (6) so the
    // two vocabularies never overlap.
    public static final int STATUS_EXHAUSTED = 7;

    /**
     * No-hint entry point: starts the level search at (levelX, levelY) ==
     * (0, 0) -- full resolution, the entire image (not a cropped square).
     * See the class comment for the full algorithm.
     */
    public static double[] getShift(int[][] fullSource1, int[][] fullSource2)
    {
        return search(fullSource1, fullSource2, 0, 0);
    }

    /**
     * Hinted entry point: starts the level search at the given
     * (shrinkLevelX, shrinkLevelY) -- independent per-axis counts of 2x
     * halvings, same meaning as getRefinedTranslation()'s
     * shrinkLevelX/shrinkLevelY -- instead of (0, 0). Negative hints are
     * clamped to 0, and each axis's hint is separately clamped down to the
     * largest level still valid for that axis's own full dimension (see the
     * class comment) before the search begins, so an oversized or
     * mismatched hint on either axis can't start the search already
     * out-of-range. From (the clamped) there, the search behaves exactly
     * like the no-hint overload.
     */
    public static double[] getShift(int[][] fullSource1, int[][] fullSource2, int shrinkLevelX, int shrinkLevelY)
    {
        return search(fullSource1, fullSource2, Math.max(0, shrinkLevelX), Math.max(0, shrinkLevelY));
    }

    /**
     * No-hint entry point for the experimental chained search -- see the
     * class comment's "getShiftRefined()" section above for the algorithm.
     */
    public static double[] getShiftRefined(int[][] fullSource1, int[][] fullSource2)
    {
        return searchRefining(fullSource1, fullSource2, 0, 0);
    }

    /** Hinted counterpart of getShiftRefined() above -- same clamping as getShift()'s hinted overload. */
    public static double[] getShiftRefined(int[][] fullSource1, int[][] fullSource2, int shrinkLevelX, int shrinkLevelY)
    {
        return searchRefining(fullSource1, fullSource2, Math.max(0, shrinkLevelX), Math.max(0, shrinkLevelY));
    }

    private static boolean levelValid(int fullDim, int level)
    {
        return (fullDim >> level) >= MIN_WORKING_SIZE;
    }

    // The largest level <= level that's still valid for fullDim, or -1 if
    // even level 0 isn't (fullDim itself is smaller than MIN_WORKING_SIZE,
    // so no level on this axis will ever work).
    private static int clampToValid(int fullDim, int level)
    {
        if (!levelValid(fullDim, 0))
        {
            return -1;
        }
        while (level > 0 && !levelValid(fullDim, level))
        {
            level--;
        }
        return level;
    }

    private static double[] search(int[][] fullSource1, int[][] fullSource2, int startLevelX, int startLevelY)
    {
        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;

        int clampedX = clampToValid(fullXdim, startLevelX);
        int clampedY = clampToValid(fullYdim, startLevelY);
        if (clampedX < 0 || clampedY < 0)
        {
            return new double[] { STATUS_EXHAUSTED, 0, 0 };
        }

        double[] result = searchCoarsening(fullSource1, fullSource2, fullXdim, fullYdim, clampedX, clampedY);
        if ((int) result[0] != STATUS_EXHAUSTED)
        {
            return result;
        }

        // Last resort: the normal coarsening progression exhausted without
        // landing a clean estimate. Try levels BELOW the clamped starting
        // pair, in lockstep, down to (0, 0) -- see the class comment for
        // why lockstep rather than a full 2D search. Only meaningful when
        // there's actually room below the start.
        if (clampedX > 0 || clampedY > 0)
        {
            double[] finer = searchFiner(fullSource1, fullSource2, fullXdim, fullYdim, clampedX, clampedY);
            if ((int) finer[0] != STATUS_EXHAUSTED)
            {
                return finer;
            }
        }
        return new double[] { STATUS_EXHAUSTED, 0, 0 };
    }

    // The primary progression: levelX/levelY increase (independently, per
    // the status code that comes back) from the given starting pair until a
    // clean estimate is found or either axis's working size would fall
    // below MIN_WORKING_SIZE.
    private static double[] searchCoarsening(int[][] fullSource1, int[][] fullSource2,
            int fullXdim, int fullYdim, int startLevelX, int startLevelY)
    {
        int levelX = startLevelX;
        int levelY = startLevelY;
        while (true)
        {
            if (!levelValid(fullXdim, levelX) || !levelValid(fullYdim, levelY))
            {
                return new double[] { STATUS_EXHAUSTED, 0, 0 };
            }

            double[] attempt = attemptAtLevels(fullSource1, fullSource2, fullXdim, fullYdim, levelX, levelY);
            int status = (int) attempt[0];

            if (status == 3) // indeterminate -- see class comment
            {
                if (levelX > 0 || levelY > 0)
                {
                    int finerX = Math.max(0, levelX - 1);
                    int finerY = Math.max(0, levelY - 1);
                    double[] finer = attemptAtLevels(fullSource1, fullSource2, fullXdim, fullYdim, finerX, finerY);
                    int finerStatus = (int) finer[0];
                    if (finerStatus != 3 && finerStatus != 4 && finerStatus != 5 && finerStatus != 6)
                    {
                        return scaleResult(finer, finerX, finerY);
                    }
                }
                levelX++;
                levelY++;
                continue;
            }
            else if (status == 4) // only X crossed the boundary
            {
                levelX++;
                continue;
            }
            else if (status == 5) // only Y crossed the boundary
            {
                levelY++;
                continue;
            }
            else if (status == 6) // both crossed
            {
                levelX++;
                levelY++;
                continue;
            }
            else if (status == 2) // iteration limit -- see class comment
            {
                // Doesn't identify an axis either, so bump both, same as
                // 3's fallback and 6 -- but gated by the shallower
                // STATUS_2_MIN_WORKING_SIZE floor rather than
                // MIN_WORKING_SIZE, so this doesn't get pushed as far as
                // 3/4/5/6 are. Once bumping either axis would violate that
                // shallower floor, accept this status-2 attempt as final.
                boolean canBumpX = (fullXdim >> (levelX + 1)) >= STATUS_2_MIN_WORKING_SIZE;
                boolean canBumpY = (fullYdim >> (levelY + 1)) >= STATUS_2_MIN_WORKING_SIZE;
                if (canBumpX && canBumpY)
                {
                    levelX++;
                    levelY++;
                    continue;
                }
                return scaleResult(attempt, levelX, levelY);
            }
            else // 0 or 1 -- a genuine estimate, accepted immediately
            {
                return scaleResult(attempt, levelX, levelY);
            }
        }
    }

    // The last-resort fallback progression: levelX/levelY decrease together
    // (in lockstep, each clamped at 0 once it gets there) from the clamped
    // starting pair down to (0, 0) -- full resolution. Only reached once
    // searchCoarsening() has already exhausted the other direction, so
    // every status other than a clean 0/1/2 just means "try the next finer
    // pair" -- there's no coarser direction left worth returning to, since
    // that whole side of the search already failed.
    private static double[] searchFiner(int[][] fullSource1, int[][] fullSource2,
            int fullXdim, int fullYdim, int startLevelX, int startLevelY)
    {
        int levelX = startLevelX;
        int levelY = startLevelY;
        while (levelX > 0 || levelY > 0)
        {
            levelX = Math.max(0, levelX - 1);
            levelY = Math.max(0, levelY - 1);

            double[] attempt = attemptAtLevels(fullSource1, fullSource2, fullXdim, fullYdim, levelX, levelY);
            int status = (int) attempt[0];
            if (status != 3 && status != 4 && status != 5 && status != 6)
            {
                return scaleResult(attempt, levelX, levelY);
            }
        }
        return new double[] { STATUS_EXHAUSTED, 0, 0 };
    }

    private static double[] scaleResult(double[] raw, int levelX, int levelY)
    {
        double scaleX = 1 << levelX;
        double scaleY = 1 << levelY;
        return new double[] { raw[0], raw[1] * scaleX, raw[2] * scaleY };
    }

    // Extracts the (levelX, levelY) rectangle (width = largest multiple of
    // 2^levelX fitting within fullXdim, height = largest multiple of
    // 2^levelY fitting within fullYdim, each centered on its own axis) fresh
    // from the original full-resolution sources, shrinks it by exactly
    // 2^levelX horizontally and 2^levelY vertically (skipped entirely when
    // both are 0, where 2^0 == 1 makes shrinking a no-op by definition), and
    // returns getTranslation()'s raw, unscaled result for that level pair --
    // scaling to original-pixel units happens in scaleResult(), once a
    // level pair's result is actually accepted.
    private static double[] attemptAtLevels(int[][] fullSource1, int[][] fullSource2,
            int fullXdim, int fullYdim, int levelX, int levelY)
    {
        int multX = 1 << levelX;
        int multY = 1 << levelY;
        int sideX = (fullXdim / multX) * multX;
        int sideY = (fullYdim / multY) * multY;
        int offX = (fullXdim - sideX) / 2;
        int offY = (fullYdim - sideY) / 2;

        int[][] rect1 = TranslateMapper.extract(fullSource1, offX, offY, sideX, sideY);
        int[][] rect2 = TranslateMapper.extract(fullSource2, offX, offY, sideX, sideY);

        if (multX == 1 && multY == 1)
        {
            return TranslateMapper.getTranslation(rect1, rect2);
        }

        int workingX = sideX / multX;
        int workingY = sideY / multY;
        int[][] work1 = TranslateMapper.avgAreaTransform(rect1, workingX, workingY);
        int[][] work2 = TranslateMapper.avgAreaTransform(rect2, workingX, workingY);
        return TranslateMapper.getTranslation(work1, work2);
    }

    // =========================================================================
    // getShiftRefined(): chained search -- see the class comment's
    // "getShiftRefined()" section for the algorithm and the current,
    // deliberately provisional status-handling rules.
    //
    // Kept fully separate from attemptAtLevels()/search() above rather than
    // refactored to share code with them: this chain keeps its working pair
    // alive and mutates it (warp, then shrink) across the whole search,
    // where every method above always re-extracts fresh from the ORIGINAL
    // full-resolution images on every attempt. Duplicating the small
    // rectangle-sizing computation here is a deliberate, low-risk trade --
    // it leaves the already-tested getShift() path untouched while this
    // one is still experimental.
    // =========================================================================

    private static double[] searchRefining(int[][] fullSource1, int[][] fullSource2, int startLevelX, int startLevelY)
    {
        int fullYdim = fullSource1.length;
        int fullXdim = fullSource1[0].length;

        int levelX = clampToValid(fullXdim, startLevelX);
        int levelY = clampToValid(fullYdim, startLevelY);
        if (levelX < 0 || levelY < 0)
        {
            return new double[] { STATUS_EXHAUSTED, 0, 0 };
        }

        int[][][] initial = extractAndShrink(fullSource1, fullSource2, fullXdim, fullYdim, levelX, levelY);
        int[][] work1 = initial[0];
        int[][] work2 = initial[1];

        double totalX = 0;
        double totalY = 0;

        // Safety cap only -- MIN_WORKING_SIZE already bounds this to
        // roughly log2(min(fullXdim, fullYdim)) iterations, same reasoning
        // as the class comment's earlier note on why getShift() can't loop
        // forever. Mirrors refineOuterRounds()'s own MAX_OUTER_ITERATIONS
        // safety cap in TranslateMapper.java.
        final int MAX_REFINE_STEPS = 40;

        for (int step = 0; step < MAX_REFINE_STEPS; step++)
        {
            double[] attempt = TranslateMapper.getTranslation(work1, work2);
            int status = (int) attempt[0];
            double dx = attempt[1];
            double dy = attempt[2];
            double scaleX = 1 << levelX;
            double scaleY = 1 << levelY;

            if (status == 0 || status == 1 || status == 2) // see class comment: 2 is used, flagged by its own code
            {
                totalX += dx * scaleX;
                totalY += dy * scaleY;
                return new double[] { status, totalX, totalY };
            }
            if (status == 3) // indeterminate -- no finer retry here, see class comment
            {
                return new double[] { STATUS_EXHAUSTED, totalX, totalY };
            }

            // 4, 5, or 6: fold this level's estimate into the total, warp
            // work2 toward work1 by it, then shrink whichever axis (or
            // both) the status names.
            totalX += dx * scaleX;
            totalY += dy * scaleY;

            boolean bumpX = (status == 4 || status == 6);
            boolean bumpY = (status == 5 || status == 6);

            int nextLevelX = bumpX ? levelX + 1 : levelX;
            int nextLevelY = bumpY ? levelY + 1 : levelY;
            if (!levelValid(fullXdim, nextLevelX) || !levelValid(fullYdim, nextLevelY))
            {
                return new double[] { STATUS_EXHAUSTED, totalX, totalY };
            }

            work2 = shiftBilinear(work2, dx, dy);
            int[][][] shrunk = halveAxes(work1, work2, bumpX, bumpY);
            work1 = shrunk[0];
            work2 = shrunk[1];
            levelX = nextLevelX;
            levelY = nextLevelY;
        }
        return new double[] { STATUS_EXHAUSTED, totalX, totalY };
    }

    // Same independent-per-axis rectangle extraction + shrink
    // attemptAtLevels() uses, but returns the working pair instead of
    // immediately calling getTranslation() on it -- searchRefining() needs
    // to keep mutating this same pair across the whole chain rather than
    // discarding it after one call.
    private static int[][][] extractAndShrink(int[][] fullSource1, int[][] fullSource2,
            int fullXdim, int fullYdim, int levelX, int levelY)
    {
        int multX = 1 << levelX;
        int multY = 1 << levelY;
        int sideX = (fullXdim / multX) * multX;
        int sideY = (fullYdim / multY) * multY;
        int offX = (fullXdim - sideX) / 2;
        int offY = (fullYdim - sideY) / 2;

        int[][] rect1 = TranslateMapper.extract(fullSource1, offX, offY, sideX, sideY);
        int[][] rect2 = TranslateMapper.extract(fullSource2, offX, offY, sideX, sideY);

        if (multX == 1 && multY == 1)
        {
            return new int[][][] { rect1, rect2 };
        }

        int workingX = sideX / multX;
        int workingY = sideY / multY;
        int[][] work1 = TranslateMapper.avgAreaTransform(rect1, workingX, workingY);
        int[][] work2 = TranslateMapper.avgAreaTransform(rect2, workingX, workingY);
        return new int[][][] { work1, work2 };
    }

    // Halves whichever of work1/work2's dimensions bumpX/bumpY select, by
    // exactly 2x via avgAreaTransform -- cropping at most 1 row/col first
    // if the current size is odd, so the reduction is always a clean,
    // exact 2x step (same reasoning TranslateMapper.cascadedHalvingReduce()
    // gives for why an exact ratio matters). Leaves an axis's dimension
    // alone entirely when its own flag is false, so X and Y still shrink
    // independently here.
    private static int[][][] halveAxes(int[][] work1, int[][] work2, boolean bumpX, boolean bumpY)
    {
        int curYdim = work1.length;
        int curXdim = work1[0].length;

        int cropX = bumpX ? (curXdim - (curXdim % 2)) : curXdim;
        int cropY = bumpY ? (curYdim - (curYdim % 2)) : curYdim;
        if (cropX != curXdim || cropY != curYdim)
        {
            work1 = TranslateMapper.extract(work1, 0, 0, cropX, cropY);
            work2 = TranslateMapper.extract(work2, 0, 0, cropX, cropY);
            curXdim = cropX;
            curYdim = cropY;
        }

        int newXdim = bumpX ? curXdim / 2 : curXdim;
        int newYdim = bumpY ? curYdim / 2 : curYdim;

        int[][] shrunk1 = TranslateMapper.avgAreaTransform(work1, newXdim, newYdim);
        int[][] shrunk2 = TranslateMapper.avgAreaTransform(work2, newXdim, newYdim);
        return new int[][][] { shrunk1, shrunk2 };
    }

    // General-purpose "shift src by (dx, dy) pixels, same dimensions out"
    // bilinear resampler, edge-clamped at the border -- see the class
    // comment for why this is a deliberately NEW operator rather than a
    // reuse of TranslateMapper.translate(). At (dx, dy) = (0, 0) this is a
    // no-op (dst == src), unlike translate(), which has no such identity
    // point due to its own "-1 to 1" -> "0 to 1" blend remapping.
    //
    // Sign convention matches getTranslation()'s: dst samples src at
    // (j + dx, i + dy), so content that sits dx,dy pixels further along in
    // src is pulled back to position (j, i) -- i.e. this warps a "shifted"
    // image back toward a "reference" by the same (dx, dy)
    // getTranslation() reports for that pair.
    private static int[][] shiftBilinear(int[][] src, double dx, double dy)
    {
        int ydim = src.length;
        int xdim = src[0].length;
        int[][] dst = new int[ydim][xdim];

        for (int i = 0; i < ydim; i++)
        {
            double srcY = i + dy;
            int y0 = (int) Math.floor(srcY);
            double fy = srcY - y0;
            int y0c = Math.max(0, Math.min(ydim - 1, y0));
            int y1c = Math.max(0, Math.min(ydim - 1, y0 + 1));

            for (int j = 0; j < xdim; j++)
            {
                double srcX = j + dx;
                int x0 = (int) Math.floor(srcX);
                double fx = srcX - x0;
                int x0c = Math.max(0, Math.min(xdim - 1, x0));
                int x1c = Math.max(0, Math.min(xdim - 1, x0 + 1));

                double top    = src[y0c][x0c] * (1. - fx) + src[y0c][x1c] * fx;
                double bottom = src[y1c][x0c] * (1. - fx) + src[y1c][x1c] * fx;
                double value  = top * (1. - fy) + bottom * fy;

                dst[i][j] = (int) Math.round(value);
            }
        }
        return dst;
    }

    // =========================================================================
    // Driver -- mirrors getTranslation.java's structure (same luminance
    // conversion, same image-size-mismatch check, same basic status-printing
    // shape) so the two are easy to compare side by side, but picks its two
    // images through a file dialog (java.awt.FileDialog, the same class and
    // usage pattern ImageTranslater.java's own Open menu item uses) instead
    // of command-line path arguments, and additionally saves its report to a
    // text file next to the chosen images rather than only printing to
    // stdout.
    //
    // Usage: java ShiftDetector [<shrinkLevelX> <shrinkLevelY>]
    // (0 or 2 numeric args -- no file paths; those come from the dialogs.)
    // =========================================================================

    public static void main(String[] args)
    {
        boolean haveHint = (args.length == 2);
        if (args.length != 0 && !haveHint)
        {
            System.err.println("Usage: java ShiftDetector [<shrinkLevelX> <shrinkLevelY>]");
            System.err.println("(No file path arguments -- a dialog will ask for the two images.)");
            System.exit(1);
        }
        int shrinkLevelX = 0, shrinkLevelY = 0;
        if (haveHint)
        {
            shrinkLevelX = (int) parseDoubleArg(args[0], "shrinkLevelX");
            shrinkLevelY = (int) parseDoubleArg(args[1], "shrinkLevelY");
        }

        String originalPath = chooseFile("Select the ORIGINAL (reference) image");
        if (originalPath == null)
        {
            System.exit(0);
            return;
        }
        String translatedPath = chooseFile("Select the TRANSLATED (shifted) image");
        if (translatedPath == null)
        {
            System.exit(0);
            return;
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

        int[][] lum1 = toLuminance(original);
        int[][] lum2 = toLuminance(translated);

        StringBuilder report = new StringBuilder();
        report.append("Images: ").append(width).append("x").append(height)
                .append("  (\"").append(originalPath).append("\" vs \"").append(translatedPath).append("\")\n\n");

        long t0 = System.currentTimeMillis();
        double[] noHint = getShift(lum1, lum2);
        long t1 = System.currentTimeMillis();
        report.append("ShiftDetector.getShift() [no hint, coarse-to-fine level search]:\n");
        report.append("    status = ").append((int) noHint[0]).append(" (").append(describeStatus((int) noHint[0])).append(")\n");
        report.append("    x = ").append(noHint[1]).append("   y = ").append(noHint[2])
                .append("   [").append(t1 - t0).append(" ms]\n");

        if (haveHint)
        {
            report.append("\n");
            long t2 = System.currentTimeMillis();
            double[] hinted = getShift(lum1, lum2, shrinkLevelX, shrinkLevelY);
            long t3 = System.currentTimeMillis();
            report.append("ShiftDetector.getShift() [hinted: shrinkLevelX=").append(shrinkLevelX)
                    .append(", shrinkLevelY=").append(shrinkLevelY).append("]:\n");
            report.append("    status = ").append((int) hinted[0]).append(" (").append(describeStatus((int) hinted[0])).append(")\n");
            report.append("    x = ").append(hinted[1]).append("   y = ").append(hinted[2])
                    .append("   [").append(t3 - t2).append(" ms]\n");
        }

        report.append("\n");
        long t4 = System.currentTimeMillis();
        double[] noHintRefined = getShiftRefined(lum1, lum2);
        long t5 = System.currentTimeMillis();
        report.append("ShiftDetector.getShiftRefined() [no hint, EXPERIMENTAL chained search]:\n");
        report.append("    status = ").append((int) noHintRefined[0]).append(" (").append(describeStatus((int) noHintRefined[0])).append(")\n");
        report.append("    x = ").append(noHintRefined[1]).append("   y = ").append(noHintRefined[2])
                .append("   [").append(t5 - t4).append(" ms]\n");

        if (haveHint)
        {
            report.append("\n");
            long t6 = System.currentTimeMillis();
            double[] hintedRefined = getShiftRefined(lum1, lum2, shrinkLevelX, shrinkLevelY);
            long t7 = System.currentTimeMillis();
            report.append("ShiftDetector.getShiftRefined() [hinted: shrinkLevelX=").append(shrinkLevelX)
                    .append(", shrinkLevelY=").append(shrinkLevelY).append(", EXPERIMENTAL chained search]:\n");
            report.append("    status = ").append((int) hintedRefined[0]).append(" (").append(describeStatus((int) hintedRefined[0])).append(")\n");
            report.append("    x = ").append(hintedRefined[1]).append("   y = ").append(hintedRefined[2])
                    .append("   [").append(t7 - t6).append(" ms]\n");
        }

        System.out.print(report);
        saveReport(originalPath, translatedPath, report.toString());
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

    // Saved next to the ORIGINAL image (same directory), named after both
    // input files, mirroring the outputDir/baseNameWithoutExtension pattern
    // ImageTranslater.java's own Save uses for its ref/shift crop pair.
    private static void saveReport(String originalPath, String translatedPath, String reportText)
    {
        File outputDir = new File(originalPath).getAbsoluteFile().getParentFile();
        String base1 = baseNameWithoutExtension(originalPath);
        String base2 = baseNameWithoutExtension(translatedPath);
        File outFile = new File(outputDir, base1 + "_vs_" + base2 + "_shift.txt");
        try (PrintWriter out = new PrintWriter(new FileWriter(outFile)))
        {
            out.print(reportText);
            System.out.println("\nSaved report to " + outFile.getPath());
        }
        catch (IOException e)
        {
            System.err.println("\nCould not write report to \"" + outFile.getPath() + "\": " + e.getMessage());
        }
    }

    private static String baseNameWithoutExtension(String path)
    {
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return (dot < 0) ? name : name.substring(0, dot);
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
            case 2: return "did not converge within the internal iteration limit (inconclusive, not necessarily a failure)";
            case 3: return "indeterminate: local gradient structure too degenerate to resolve (inconclusive)";
            case 4: return "stopped: only X reached translate()'s +/-1 pixel boundary";
            case 5: return "stopped: only Y reached translate()'s +/-1 pixel boundary";
            case 6: return "stopped: both X and Y reached translate()'s +/-1 pixel boundary";
            case 7: return "search exhausted: shrank past a usable size without a clean estimate";
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

    // Standard ITU-R BT.601 luma weights, rounded to nearest -- same as
    // getTranslation.java's own toLuminance(), duplicated here rather than
    // shared since both are small, standalone command-line drivers.
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
