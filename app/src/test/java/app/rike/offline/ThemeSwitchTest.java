package app.rike.offline;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class ThemeSwitchTest {
    private static Object get(Object a,String n)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(a);}
    private static void set(Object a,String n,Object v)throws Exception{Field f=a.getClass().getDeclaredField(n);f.setAccessible(true);f.set(a,v);}
    private static void call(Object a,String n)throws Exception{Method m=a.getClass().getDeclaredMethod(n);m.setAccessible(true);m.invoke(a);}
    private static List<View> tree(View v){List<View> list=new ArrayList<>();list.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)list.addAll(tree(((ViewGroup)v).getChildAt(i)));return list;}
    private static Button button(View root,String label){for(View v:tree(root))if(v instanceof Button&&(label.contentEquals(((Button)v).getText())||label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;return null;}
    private static class CountingStore extends VaultStore {
        int writes;
        CountingStore(MainActivity a){super(a);}
        @Override public void write(byte[] bytes)throws IOException{writes++;super.write(bytes);}
    }
    @Test public void togglesOnlyColorsKeepsGridsInputFocusAndScrollWithoutVaultWrite()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject data=Records.empty();
        data.getJSONArray("practiceTypes").put(new JSONObject().put("id","t").put("name","SYNTHETIC-TYPE"));
        data.getJSONObject("drafts").put("checkIn",new JSONObject().put("typeId","t").put("manual","17").put("note","SYNTHETIC-NOTE"));
        VaultCrypto.Created key=VaultCrypto.create("012345".toCharArray());CountingStore store=new CountingStore(a);
        store.write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
        set(a,"store",store);set(a,"session",key.session);set(a,"data",data);call(a,"showApp");
        View root=(View)get(a,"root");List<View> before=tree(root);EditText input=null;ScrollView scroll=null;HeatmapView cell=null;
        for(View v:before){if(v instanceof EditText&&"17".contentEquals(((EditText)v).getText()))input=(EditText)v;if(v instanceof ScrollView)scroll=(ScrollView)v;if(v instanceof HeatmapView)cell=(HeatmapView)v;}
        assertNotNull(input);assertNotNull(scroll);assertNull(cell);input.requestFocus();input.setSelection(1);scroll.scrollTo(0,180);
        int position=scroll.getScrollY(),writes=store.writes;int themeCount=((Map<?,?>)get(a,"themeUpdates")).size();
        int ink=input.getCurrentTextColor();byte[] disk=store.read();
        TestWork.named(root,"切换夜间").performClick();
        assertSame(root,get(a,"root"));assertEquals(before,tree(root));assertEquals(position,scroll.getScrollY());assertTrue(input.hasFocus());assertEquals(1,input.getSelectionStart());assertEquals("17",input.getText().toString());
        assertTrue(root.getBackground() instanceof PaperBackground);assertEquals(0xffeee8de,input.getCurrentTextColor());assertNotEquals(ink,input.getCurrentTextColor());
        assertTrue(button(root,"SYNTHETIC-TYPE").isSelected());assertNotNull(TestWork.named(root,"切换日间"));assertEquals(themeCount,((Map<?,?>)get(a,"themeUpdates")).size());assertEquals(writes,store.writes);assertArrayEquals(disk,store.read());
        TestWork.named(root,"切换日间").performClick();assertEquals(before,tree(root));assertEquals(ink,input.getCurrentTextColor());assertEquals(position,scroll.getScrollY());
        TestWork.navigate(a,3);View stats=(View)get(a,"root");List<View> grids=tree(stats);List<HeatmapView> maps=TestWork.views(stats,HeatmapView.class);assertEquals(2,maps.size());int[] original={maps.get(0).cellColor(0),maps.get(1).cellColor(0)};int priorWrites=store.writes;
        TestWork.named(stats,"切换夜间").performClick();assertSame(stats,get(a,"root"));assertEquals(grids,tree(stats));for(int i=0;i<2;i++)assertNotEquals(original[i],maps.get(i).cellColor(0));assertEquals(priorWrites,store.writes);
        TestWork.named(stats,"切换日间").performClick();for(int i=0;i<2;i++)assertEquals(original[i],maps.get(i).cellColor(0));assertEquals(priorWrites,store.writes);
        c.pause().stop().destroy();
    }
}
