package app.rike.offline;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Quiet, solid background shared by onboarding and the unlocked app. */
final class PaperBackground extends Drawable {
    private boolean dark;
    PaperBackground(float density, boolean dark) { this.dark = dark; }
    void theme(boolean value) { dark = value; invalidateSelf(); }
    @Override public void draw(Canvas canvas) {
        canvas.drawColor(dark ? 0xff151411 : 0xfff5f0e6);
    }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
