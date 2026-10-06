package app.rike.offline;
import java.lang.reflect.*;
import java.util.concurrent.*;
import android.os.Looper;
import org.robolectric.Shadows;
final class TestWork {
    static Object get(MainActivity a,String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    static void drain(MainActivity a)throws Exception{for(int i=0;i<6;i++){((ExecutorService)get(a,"crypto")).submit(()->{}).get(30,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();}}
    static void flush(MainActivity a)throws Exception{VaultWriter w=(VaultWriter)get(a,"writer");if(w!=null)w.flushSoon();drain(a);}
    static org.json.JSONObject draft(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod("draft",String.class);m.setAccessible(true);return (org.json.JSONObject)m.invoke(a,name);}
    static android.view.View named(android.view.View v,String label){
        android.view.View action=findNamed(v,label,true);return action==null?findNamed(v,label,false):action;
    }
    private static android.view.View findNamed(android.view.View v,String label,boolean actionOnly){
        boolean matches=label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())||v instanceof android.widget.TextView&&label.contentEquals(((android.widget.TextView)v).getText());
        if(matches&&(!actionOnly||v.isClickable()))return v;
        if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++){android.view.View found=findNamed(((android.view.ViewGroup)v).getChildAt(i),label,actionOnly);if(found!=null)return found;}return null;
    }
    static void navigate(MainActivity a,int section){
        if(section<4){named(a.getWindow().getDecorView(),new String[]{"今日","记录","日记","数据"}[section]).performClick();return;}
        named(a.getWindow().getDecorView(),"设置").performClick();
        if(section==4)named(a.getWindow().getDecorView(),"备份").performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    static void deleteFirst(MainActivity a){
        android.view.View root=a.getWindow().getDecorView();android.view.View action=named(root,"删除打卡记录");if(action==null)action=named(root,"删除这篇日记");action.performClick();Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    static <T> java.util.List<T> views(android.view.View v,Class<T> kind){
        java.util.List<T> r=new java.util.ArrayList<>();if(kind.isInstance(v))r.add(kind.cast(v));if(v instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)v).getChildCount();i++)r.addAll(views(((android.view.ViewGroup)v).getChildAt(i),kind));return r;
    }
    static void layout(MainActivity a)throws Exception {
        android.view.View root=(android.view.View)get(a,"root");float scale=a.getResources().getDisplayMetrics().density;
        int width=Math.round(360*scale),height=Math.round(744*scale);
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(height,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
    }
    static void touch(android.view.View source,int action,float rawX,float rawY) {
        long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now,action,rawX,rawY,0);
        source.dispatchTouchEvent(event);event.recycle();
    }
    static android.view.View drag(MainActivity a,String from,String to,boolean commit)throws Exception {
        layout(a);android.view.View source=named(a.getWindow().getDecorView(),from),target=named(a.getWindow().getDecorView(),to);
        int[] xy=new int[2];source.getLocationOnScreen(xy);touch(source,android.view.MotionEvent.ACTION_DOWN,xy[0]+source.getWidth()/2f,xy[1]+source.getHeight()/2f);source.performLongClick();layout(a);
        target.getLocationOnScreen(xy);float x=xy[0]+target.getWidth()/2f,y=xy[1]+target.getHeight()/2f;
        touch(source,android.view.MotionEvent.ACTION_MOVE,x,y);layout(a);touch(source,commit?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_CANCEL,x,y);drain(a);return source;
    }
    static void chooseCalendarMonth(android.app.AlertDialog calendar,java.time.YearMonth month){
        named(calendar.getWindow().getDecorView(),"选择日期年月").performClick();android.app.AlertDialog picker=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        views(picker.getWindow().getDecorView(),android.widget.NumberPicker.class).get(0).setValue(month.getYear());named(picker.getWindow().getDecorView(),month.getMonthValue()+" 月").performClick();
    }
}
