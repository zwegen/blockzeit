package de.blockzeit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Original line icons, not font glyphs or remote image assets. */
final class WeatherMetricIcon {
    enum Kind { WIND, DROP, SNOW, MIXED }
    private WeatherMetricIcon() { }
    static Bitmap draw(int pixels, Kind kind) {
        return draw(pixels, kind, 0xffe6e8eb);
    }
    /** Explicit caller palette; widget callers keep the original neutral default. */
    static Bitmap draw(int pixels, Kind kind, int color) {
        int side = Math.max(1, pixels);
        Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.scale(side / 100f, side / 100f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        if (kind == Kind.WIND) {
            path.moveTo(9, 33); path.lineTo(65, 33); path.cubicTo(91, 33, 89, 9, 72, 11);
            path.moveTo(9, 52); path.lineTo(78, 52); path.cubicTo(97, 52, 97, 79, 79, 79);
            path.moveTo(9, 73); path.lineTo(49, 73); path.cubicTo(66, 73, 66, 91, 52, 91);
        } else if (kind == Kind.SNOW) {
            snowflake(path, 50, 50, 37);
        } else if (kind == Kind.MIXED) {
            // One bitmap / unchanged icon slot, with both original precipitation glyphs.
            path.moveTo(28, 12); path.cubicTo(23, 29, 10, 43, 10, 56);
            path.cubicTo(10, 84, 48, 84, 48, 56); path.cubicTo(48, 43, 33, 29, 28, 12); path.close();
            snowflake(path, 70, 65, 24);
        } else {
            path.moveTo(50, 9); path.cubicTo(42, 29, 20, 47, 20, 65);
            path.cubicTo(20, 104, 80, 104, 80, 65); path.cubicTo(80, 47, 58, 29, 50, 9); path.close();
        }
        canvas.drawPath(path, paint);
        return bitmap;
    }
    private static void snowflake(Path path, float x, float y, float radius) {
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 3 * i;
            float dx = (float) Math.sin(angle), dy = (float) Math.cos(angle);
            float tipX = x + dx * radius, tipY = y + dy * radius;
            path.moveTo(x, y); path.lineTo(tipX, tipY);
            float branchX = x + dx * radius * .62f, branchY = y + dy * radius * .62f;
            for (int sign : new int[]{-1, 1}) {
                path.moveTo(branchX, branchY);
                path.lineTo(branchX + (dx + sign * dy) * radius * .24f,
                        branchY + (dy - sign * dx) * radius * .24f);
            }
        }
    }
}
