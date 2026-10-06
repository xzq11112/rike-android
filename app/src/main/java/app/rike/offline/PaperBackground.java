package app.rike.offline;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** The original website's paper background, drawn locally without image assets. */
final class PaperBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean dark;
    PaperBackground(float density, boolean dark) { this.density = density; this.dark = dark; }
    void theme(boolean value) { dark = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds(); canvas.drawColor(dark ? 0xff151411 : 0xfff5f0e6);
        paint.setShader(new RadialGradient(bounds.width() * .12f, bounds.height() * .08f, 384 * density,
            dark ? 0x16d16b5e : 0x0ba13d32, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint);
        paint.setShader(new RadialGradient(bounds.width() * .92f, bounds.height() * .88f, 480 * density,
            dark ? 0x07eae0d1 : 0x0a3a3127, Color.TRANSPARENT, Shader.TileMode.CLAMP));
        canvas.drawRect(bounds, paint); paint.setShader(null); paint.setStrokeWidth(density);
        paint.setColor(dark ? 0x05eee5d8 : 0x044b4135);
        for (float x = bounds.left; x < bounds.right; x += 5 * density) canvas.drawLine(x, bounds.top, x, bounds.bottom, paint);
        for (float y = bounds.top; y < bounds.bottom; y += 5 * density) canvas.drawLine(bounds.left, y, bounds.right, y, paint);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
