package app.rike.offline;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;

/** Content-sized buttons wrap like the website. Moving a button never detaches
 * its touch target, so the same long press can continue directly into a drag. */
final class OptionFlowLayout extends ViewGroup {
    private final List<View> order = new ArrayList<>();
    private final int gap;
    private View moving;
    private List<View> before;
    private float offsetX, offsetY, dragX, dragY;
    OptionFlowLayout(Context context) {
        super(context);
        gap = Math.round(8 * getResources().getDisplayMetrics().density);
        setSaveEnabled(false);
        setClipChildren(false);
        setClipToPadding(false);
    }
    @Override public void onViewAdded(View child) { super.onViewAdded(child); order.add(child); }
    @Override public void onViewRemoved(View child) { super.onViewRemoved(child); order.remove(child); }
    @Override protected LayoutParams generateDefaultLayoutParams() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
        int x = 0, y = 0, line = 0;
        for (View child : order) {
            if (child.getVisibility() == GONE) continue;
            LayoutParams parameters=child.getLayoutParams();
            child.measure(getChildMeasureSpec(widthSpec,getPaddingLeft()+getPaddingRight(),parameters.width),getChildMeasureSpec(MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED),0,parameters.height));
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > 0 && x + width > available) { y += line + gap; x = 0; line = 0; }
            x += width + gap; line = Math.max(line, height);
        }
        setMeasuredDimension(resolveSize(MeasureSpec.getSize(widthSpec), widthSpec), resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = getWidth() - getPaddingLeft() - getPaddingRight(), x = 0, y = getPaddingTop(), line = 0;
        for (View child : order) {
            if (child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > 0 && x + width > available) { y += line + gap; x = 0; line = 0; }
            int oldX = child.getLeft(), oldY = child.getTop();
            boolean laidOut = child.isLaidOut();
            child.layout(getPaddingLeft() + x, y, getPaddingLeft() + x + width, y + height);
            if (moving != null && child != moving && laidOut && (oldX != child.getLeft() || oldY != child.getTop())) {
                float visualX = oldX + child.getTranslationX(), visualY = oldY + child.getTranslationY();
                child.animate().cancel();
                child.setTranslationX(visualX - child.getLeft()); child.setTranslationY(visualY - child.getTop());
                child.animate().translationX(0).translationY(0).setDuration(120).start();
            }
            x += width + gap; line = Math.max(line, height);
        }
        positionMoving();
    }
    void beginDrag(View child, float rawX, float rawY) {
        cancelDrag();
        moving = child; before = new ArrayList<>(order);
        int[] location = new int[2]; getLocationOnScreen(location);
        offsetX = rawX - location[0] - child.getLeft(); offsetY = rawY - location[1] - child.getTop();
        dragX = child.getLeft(); dragY = child.getTop();
        child.animate().cancel(); child.setElevation(gap); child.setAlpha(0.9f); child.bringToFront();
        getParent().requestDisallowInterceptTouchEvent(true);
    }
    boolean isDragging() { return moving != null; }
    void dragTo(float rawX, float rawY) {
        if (moving == null) return;
        int[] location = new int[2]; getLocationOnScreen(location);
        float x = rawX - location[0], y = rawY - location[1];
        dragX = x - offsetX; dragY = y - offsetY;
        for (View target : new ArrayList<>(order)) {
            if (target == moving || !(target.getTag() instanceof String)) continue;
            if (x >= target.getLeft() - gap / 2f && x < target.getRight() + gap / 2f && y >= target.getTop() - gap / 2f && y < target.getBottom() + gap / 2f) {
                int destination = order.indexOf(target);
                order.remove(moving); order.add(destination, moving); requestLayout(); break;
            }
        }
        positionMoving();
    }
    private void positionMoving() {
        if (moving != null) { moving.setTranslationX(dragX - moving.getLeft()); moving.setTranslationY(dragY - moving.getTop()); }
    }
    List<String> finishDrag(boolean commit) {
        if (moving == null) return null;
        boolean changed = !order.equals(before);
        if (!commit) { order.clear(); order.addAll(before); }
        moving.setTranslationX(0); moving.setTranslationY(0); moving.setElevation(0); moving.setAlpha(1);
        for (View child : order) { child.animate().cancel(); child.setTranslationX(0); child.setTranslationY(0); }
        moving = null; before = null;
        getParent().requestDisallowInterceptTouchEvent(false); requestLayout();
        if (!commit || !changed) return null;
        List<String> ids = new ArrayList<>();
        for (View child : order) if (child.getTag() instanceof String) ids.add((String) child.getTag());
        return ids;
    }
    void cancelDrag() { finishDrag(false); }
}
