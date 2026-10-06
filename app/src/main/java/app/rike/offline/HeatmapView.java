package app.rike.offline;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;
import java.util.function.Consumer;

/** One measured/drawn View per grid; days stay virtual accessible children. */
final class HeatmapView extends View {
    final List<PracticeHeatmap.Day> days;
    final int columns,offset,rows;
    final boolean calendar;
    private final Consumer<PracticeHeatmap.Day> click;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect=new RectF();
    private final float density;
    private boolean dark;
    private String markerLabel;
    private int focused=-1,hovered=-1,touched=-1;
    HeatmapView(Context c,List<PracticeHeatmap.Day> days,int columns,boolean calendar,boolean dark,Consumer<PracticeHeatmap.Day> click){
        super(c);this.days=Collections.unmodifiableList(new ArrayList<>(days));this.columns=columns;this.calendar=calendar;this.dark=dark;this.click=click;
        offset=calendar?days.get(0).date.getDayOfWeek().getValue()-1:0;rows=(offset+days.size()+columns-1)/columns;density=c.getResources().getDisplayMetrics().density;
        setSaveEnabled(false);setClickable(click!=null);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription((calendar?"月历":"全年")+"练习热力图，"+days.size()+" 天");
    }
    void theme(boolean dark){this.dark=dark;invalidate();}
    void markers(String label){markerLabel=label;setContentDescription("选择日期，有"+label+"的日期会着色");}
    int cellColor(int index){
        long minutes=days.get(index).minutes;
        return markerLabel!=null&&minutes>0?(dark?0xffd16b5e:0xffa13d32):PracticeHeatmap.color(minutes,dark);
    }
    private float height(){return (calendar?48:14)*density;}
    @Override protected void onMeasure(int w,int h){setMeasuredDimension(MeasureSpec.getSize(w),resolveSize(Math.round(rows*height()),h));}
    private void bounds(int index,RectF b){int slot=index+offset;float width=getWidth()/(float)columns;int row=slot/columns,col=slot%columns;b.set(col*width+density,row*height()+density,(col+1)*width-density,(row+1)*height()-density);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);paint.setTextSize(13*getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);
        for(int i=0;i<days.size();i++){
            bounds(i,rect);paint.setStyle(Paint.Style.FILL);paint.setColor(cellColor(i));canvas.drawRoundRect(rect,2*density,2*density,paint);
            if(calendar){boolean marked=days.get(i).minutes>0;paint.setColor(markerLabel!=null&&marked?(dark?0xff181714:Color.WHITE):marked?0xff292621:dark?0xffeee8de:0xff292621);canvas.drawText(String.valueOf(days.get(i).date.getDayOfMonth()),rect.centerX(),rect.centerY()-(paint.ascent()+paint.descent())/2,paint);}
            if(i==focused){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*density);paint.setColor(dark?Color.WHITE:Color.BLACK);canvas.drawRoundRect(rect,2*density,2*density,paint);}
        }
    }
    private int hit(float x,float y){if(x<0||x>=getWidth()||y<0||y>=getHeight())return -1;int index=(int)(y/height())*columns+(int)(x/(getWidth()/(float)columns))-offset;return index>=0&&index<days.size()?index:-1;}
    @Override public boolean onTouchEvent(MotionEvent e){
        if(click==null)return false;
        if(e.getAction()==MotionEvent.ACTION_DOWN){touched=hit(e.getX(),e.getY());return touched>=0;}
        if(e.getAction()==MotionEvent.ACTION_UP){int index=hit(e.getX(),e.getY());if(index==touched&&index>=0){focused=index;performClick();}touched=-1;return true;}
        if(e.getAction()==MotionEvent.ACTION_CANCEL){touched=-1;return true;}return true;
    }
    @Override public boolean performClick(){if(click==null)return false;super.performClick();if(focused>=0){click.accept(days.get(focused));event(focused,AccessibilityEvent.TYPE_VIEW_CLICKED);}return true;}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent e){
        if(key==android.view.KeyEvent.KEYCODE_DPAD_CENTER||key==android.view.KeyEvent.KEYCODE_ENTER){if(focused<0)focused=0;return performClick();}
        int move=key==android.view.KeyEvent.KEYCODE_DPAD_LEFT?-1:key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT?1:key==android.view.KeyEvent.KEYCODE_DPAD_UP?-columns:key==android.view.KeyEvent.KEYCODE_DPAD_DOWN?columns:0;
        if(move!=0){focused=Math.max(0,Math.min(days.size()-1,(focused<0?0:focused)+move));invalidate();return true;}return super.onKeyDown(key,e);
    }
    private String label(int i){PracticeHeatmap.Day d=days.get(i);return markerLabel==null?d.date+"，实际练习 "+d.minutes+" 分钟":d.date+(d.minutes>0?"，有":"，无")+markerLabel;}
    private void event(int i,int type){AccessibilityManager m=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);if(!m.isEnabled()||getParent()==null)return;AccessibilityEvent e=AccessibilityEvent.obtain(type);e.setPackageName(getContext().getPackageName());e.setClassName("android.widget.Button");e.setSource(this,i);e.setContentDescription(label(i));getParent().requestSendAccessibilityEvent(this,e);}
    @Override public boolean dispatchHoverEvent(MotionEvent e){
        AccessibilityManager m=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(m.isTouchExplorationEnabled()){int index=e.getAction()==MotionEvent.ACTION_HOVER_EXIT?-1:hit(e.getX(),e.getY());if(index!=hovered){int old=hovered;hovered=index;if(index>=0)event(index,AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);if(old>=0)event(old,AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);}return index>=0;}
        return super.dispatchHoverEvent(e);
    }
    @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){return provider;}
    private final AccessibilityNodeProvider provider=new AccessibilityNodeProvider(){
        @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){
            if(id==HOST_VIEW_ID){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(HeatmapView.this);onInitializeAccessibilityNodeInfo(n);for(int i=0;i<days.size();i++)n.addChild(HeatmapView.this,i);return n;}
            if(id<0||id>=days.size())return null;
            AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setSource(HeatmapView.this,id);n.setParent(HeatmapView.this);n.setPackageName(getContext().getPackageName());n.setClassName(click==null?"android.widget.TextView":"android.widget.Button");n.setContentDescription(label(id));n.setEnabled(true);n.setClickable(click!=null);n.setFocusable(true);n.setAccessibilityFocused(focused==id);if(click!=null)n.addAction(AccessibilityNodeInfo.ACTION_CLICK);n.addAction(focused==id?AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
            RectF f=new RectF();bounds(id,f);Rect r=new Rect();f.roundOut(r);n.setBoundsInParent(r);int[] xy=new int[2];getLocationOnScreen(xy);r.offset(xy[0],xy[1]);n.setBoundsInScreen(r);Rect visible=new Rect();n.setVisibleToUser(isShown()&&getGlobalVisibleRect(visible)&&Rect.intersects(r,visible));return n;
        }
        @Override public boolean performAction(int id,int action,Bundle args){
            if(id==HOST_VIEW_ID)return HeatmapView.this.performAccessibilityAction(action,args);if(id<0||id>=days.size())return false;
            if(action==AccessibilityNodeInfo.ACTION_CLICK){focused=id;return performClick();}
            if(action==AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS){if(focused>=0)event(focused,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);focused=id;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);invalidate();return true;}
            if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS){if(focused==id){focused=-1;event(id,AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED);invalidate();}return true;}return false;
        }
        @Override public AccessibilityNodeInfo findFocus(int focus){return focused<0?null:createAccessibilityNodeInfo(focused);}
        @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String query,int id){List<AccessibilityNodeInfo> result=new ArrayList<>();for(int i=0;i<days.size();i++)if(label(i).contains(query))result.add(createAccessibilityNodeInfo(i));return result;}
    };
}
