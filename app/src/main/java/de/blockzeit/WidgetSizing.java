package de.blockzeit;

/** A complete host size range in dp; never mix a partial report with a previous range. */
final class WidgetSizing {
    private static final WidgetSizing DEFAULT = new WidgetSizing(280, 280, 280, 280);
    final int minWidth, minHeight, maxWidth, maxHeight;

    private WidgetSizing(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        this.minWidth = minWidth; this.minHeight = minHeight;
        this.maxWidth = maxWidth; this.maxHeight = maxHeight;
    }
    static WidgetSizing valid(int minWidth, int minHeight, int maxWidth, int maxHeight) {
        if (minWidth <= 0 || minHeight <= 0 || maxWidth < minWidth || maxHeight < minHeight) return null;
        return new WidgetSizing(minWidth, minHeight, maxWidth, maxHeight);
    }
    static WidgetSizing resolve(WidgetSizing reported, WidgetSizing previous) {
        return reported != null ? reported : previous != null ? previous : DEFAULT;
    }
    int portraitDiameter() { return diameter(minWidth, maxHeight); }
    int landscapeDiameter() { return diameter(maxWidth, minHeight); }
    private static int diameter(int width, int height) {
        // Preserve the original geometry limits, including legitimate user shrink operations.
        return Math.max(80, Math.min(600, Math.min(width, height)));
    }
    String encode() { return minWidth + "," + minHeight + "," + maxWidth + "," + maxHeight; }
    static WidgetSizing restore(String encoded) {
        if (encoded == null) return null;
        String[] values = encoded.split(",", -1);
        if (values.length != 4) return null;
        try {
            return valid(Integer.parseInt(values[0]), Integer.parseInt(values[1]),
                    Integer.parseInt(values[2]), Integer.parseInt(values[3]));
        } catch (NumberFormatException ignored) { return null; }
    }
}
