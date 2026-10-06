package app.rike.offline;

import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class WebParityTest {
    private static void set(Object a,String name,Object value)throws Exception{Field f=a.getClass().getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    private static void call(Object a,String name)throws Exception{Method m=a.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
    private static String text(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s.append(text(((ViewGroup)v).getChildAt(i)));return s.toString();}
    private static Button button(View v,String name){if(v instanceof Button&&(name.contentEquals(((Button)v).getText())||name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button found=button(((ViewGroup)v).getChildAt(i),name);if(found!=null)return found;}return null;}
    private static List<EditText> inputs(View v){List<EditText> fields=new ArrayList<>();if(v instanceof EditText)fields.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)fields.addAll(inputs(((ViewGroup)v).getChildAt(i)));return fields;}
    @Test public void numericDefaultAndLetterToggleKeepPasswordUnchanged(){
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();View root=a.getWindow().getDecorView();
        EditText p=inputs(root).get(0);assertEquals(InputType.TYPE_CLASS_NUMBER,p.getInputType()&InputType.TYPE_MASK_CLASS);
        p.setText("012345");button(root,"使用字符键盘").performClick();
        assertEquals("012345",p.getText().toString());assertEquals(InputType.TYPE_CLASS_TEXT,p.getInputType()&InputType.TYPE_MASK_CLASS);
        p.setText("synthetic-long-password");button(root,"使用数字键盘").performClick();
        assertEquals("synthetic-long-password",p.getText().toString());assertNotNull(p.getTransformationMethod());c.pause().stop().destroy();
    }
    @Test public void legacyDreamDataIsRetainedButHiddenAndHeatmapsStayOnDataPage()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();JSONObject legacy=Records.empty();
        legacy.getJSONArray("practiceTypes").put(new JSONObject().put("id","t").put("name","SYNTHETIC-TYPE"));
        legacy.getJSONArray("durationPresets").put(new JSONObject().put("id","m").put("minutes",30));
        legacy.getJSONArray("checkIns").put(new JSONObject().put("id","c").put("practiceDate",LocalDate.now().toString()).put("practiceTypeName","SYNTHETIC-TYPE").put("durationMinutes",30));
        JSONObject dream=new JSONObject().put("id","d").put("sleepDate","2026-09-01").put("sleepPeriod","night").put("content","SYNTHETIC-HIDDEN-DREAM");
        legacy.getJSONArray("dreams").put(dream);
        legacy.getJSONArray("deletedRecords").put(new JSONObject().put("entity","dream").put("record",new JSONObject(dream.toString()).put("id","gone")));
        legacy.getJSONArray("auditLog").put(new JSONObject().put("entity","dream").put("action","create"));
        legacy.getJSONObject("drafts").put("dream",new JSONObject().put("content","SYNTHETIC-HIDDEN-DRAFT"));
        JSONObject data=Records.validate(legacy);String before=data.toString();VaultCrypto.Created key=VaultCrypto.create("012345".toCharArray());VaultStore store=new VaultStore(a);
        store.write(VaultCrypto.encrypt(key.session,before.getBytes(StandardCharsets.UTF_8)));set(a,"store",store);set(a,"session",key.session);set(a,"data",data);call(a,"showApp");
        String today=text(a.getWindow().getDecorView());assertFalse(today.contains("今日已记"));assertFalse(today.contains("练习热力图"));TestWork.navigate(a,3);today=text(a.getWindow().getDecorView());assertTrue(today.contains("练习热力图"));
        assertNotNull(TestWork.named(a.getWindow().getDecorView(),"选择统计月份"));assertTrue(today.contains("全年"));assertNull(button(a.getWindow().getDecorView(),"记梦"));
        // Monthly browsing and removed day actions have dedicated regression tests.
        TestWork.navigate(a,4);String backup=text(a.getWindow().getDecorView());assertFalse(backup.contains("SYNTHETIC-HIDDEN"));assertFalse(backup.contains("记梦"));
        VaultCrypto.Opened read=VaultCrypto.open(store.read(),"012345".toCharArray());assertEquals(before,new String(read.plaintext,StandardCharsets.UTF_8));
        read.session.close();assertEquals(1,data.getJSONArray("dreams").length());assertEquals(30,data.getJSONArray("durationPresets").getJSONObject(0).getInt("minutes"));c.pause().stop().destroy();
    }
}
