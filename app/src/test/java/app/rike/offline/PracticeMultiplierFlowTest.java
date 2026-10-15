package app.rike.offline;

import android.app.AlertDialog;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28) @LooperMode(LooperMode.Mode.PAUSED)
public class PracticeMultiplierFlowTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
    @Test public void stationaryLongPressSavesMultiplierAndSixtyRawMinutesBecomeFortyTwo()throws Exception {
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=TestWork.offline(c.get());
        try {
            JSONObject data=Records.empty();Records.upsert(data,"practiceType",new JSONObject().put("id","synthetic").put("name","SYNTHETIC"));
            VaultCrypto.Created key=VaultCrypto.create("synthetic-multiplier-password".toCharArray());
            new VaultStore(a).write(VaultCrypto.encrypt(key.session,data.toString().getBytes(StandardCharsets.UTF_8)));
            set(a,"session",key.session);set(a,"data",data);Method show=MainActivity.class.getDeclaredMethod("showApp");show.setAccessible(true);show.invoke(a);TestWork.layout(a);
            View practice=TestWork.named(a.getWindow().getDecorView(),"SYNTHETIC");assertNotNull(practice);
            int[] xy=new int[2];practice.getLocationOnScreen(xy);float x=xy[0]+practice.getWidth()/2f,y=xy[1]+practice.getHeight()/2f;
            TestWork.touch(practice,MotionEvent.ACTION_DOWN,x,y);practice.performLongClick();TestWork.touch(practice,MotionEvent.ACTION_UP,x,y);
            AlertDialog edit=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(edit);assertTrue(edit.isShowing());
            java.util.List<EditText> fields=TestWork.views(edit.getWindow().getDecorView(),EditText.class);assertEquals(2,fields.size());fields.get(1).setText("0.7");edit.getButton(AlertDialog.BUTTON_POSITIVE).performClick();TestWork.drain(a);
            assertFalse(edit.isShowing());assertEquals(0.7,Records.multiplier(((JSONObject)TestWork.get(a,"data")).getJSONArray("practiceTypes").getJSONObject(0)),0.000001);
            TestWork.named(a.getWindow().getDecorView(),"完成练习类型编辑").performClick();
            ((EditText)TestWork.named(a.getWindow().getDecorView(),"任意分钟数")).setText("60");TestWork.named(a.getWindow().getDecorView(),"保存练习").performClick();TestWork.drain(a);
            JSONObject saved=((JSONObject)TestWork.get(a,"data")).getJSONArray("checkIns").getJSONObject(0);
            assertEquals(60,saved.getInt("durationMinutes"));assertEquals(0.7,Records.multiplier(saved),0.000001);assertEquals(4200,Records.effectiveHundredths(saved));
            assertNotNull(TestWork.named(a.getWindow().getDecorView(),"42 分钟"));
            VaultCrypto.Opened disk=VaultCrypto.open(new VaultStore(a).read(),"synthetic-multiplier-password".toCharArray());
            try{JSONObject stored=new JSONObject(new String(disk.plaintext,StandardCharsets.UTF_8));assertEquals(4200,Records.effectiveHundredths(stored.getJSONArray("checkIns").getJSONObject(0)));}finally{java.util.Arrays.fill(disk.plaintext,(byte)0);disk.session.close();}
        } finally {c.pause().stop().destroy();}
    }
}
