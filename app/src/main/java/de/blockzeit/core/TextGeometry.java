package de.blockzeit.core;

/** Pixel-space ink geometry; callers supply Android font metrics/bounds, not nominal view-box gaps. */
public final class TextGeometry {
    private TextGeometry() { }
    /** Shrink to the measured width budget; never enlarge text that already fits. */
    public static float fittedFont(float maximum, float measuredWidth, float availableWidth) {
        if (!Float.isFinite(maximum) || maximum <= 0 || !Float.isFinite(measuredWidth)
                || measuredWidth < 0 || !Float.isFinite(availableWidth) || availableWidth <= 0)
            throw new IllegalArgumentException("font geometry");
        return maximum * Math.min(1f, measuredWidth == 0 ? 1f : availableWidth / measuredWidth);
    }

    /** Compact 0.6%-diameter ink clearance, bounded to 1..3 dp and at least two physical pixels. */
    public static float gapPx(int diameter, float density) {
        return Math.max(2f, Math.max(density, Math.min(3f * density, .006f * diameter * density)));
    }
    public static float footerCenter(int diameter, float density, int mainAscent, int mainDescent,
            int mainInkBottom, int footerAscent, int footerDescent, int footerInkTop) {
        float px = diameter * density;
        // TextView does NOT center an oversized line: its vertical offset is then zero.
        float mainBottom = mainInkBottom + baselineOffset(Math.round(.20f * px), mainAscent, mainDescent);
        float footerTop = footerInkTop + baselineOffset(Math.round(.08f * px), footerAscent, footerDescent);
        // Round outward and add one physical pixel to cover independently rounded host view/baseline positions.
        float offset = (float) Math.ceil(mainBottom + gapPx(diameter, density) - footerTop) + 1f;
        return .50f + offset / (diameter * density);
    }
    /** Translate both measured ink rows together, preserving their existing separation. */
    public static float[] centeredRows(int diameter, float density, int mainAscent, int mainDescent,
            int mainInkTop, int footerAscent, int footerDescent, int footerInkBottom, float footerCenter) {
        float px = diameter * density;
        float top = .50f * px + baselineOffset(Math.round(.20f * px), mainAscent, mainDescent) + mainInkTop;
        float bottom = footerCenter * px + baselineOffset(Math.round(.08f * px), footerAscent, footerDescent) + footerInkBottom;
        float shift = .50f - (top + bottom) / (2f * px);
        return new float[] { .50f + shift, footerCenter + shift };
    }
    private static float baselineOffset(int height, int ascent, int descent) {
        int verticalOffset = Math.max(0, (height - (descent - ascent)) / 2);
        return verticalOffset - ascent - height / 2f;
    }
}
