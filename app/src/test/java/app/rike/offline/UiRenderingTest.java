package app.rike.offline;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.EditText;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Synthetic screen captures for visual review, not a claim of device acceptance. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class UiRenderingTest {
    private static void set(MainActivity a,String name,Object value)throws Exception{
        Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);
    }
    private static void call(MainActivity a,String name)throws Exception{
        Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);
    }
    private static void capture(MainActivity a,String name)throws Exception{
        TestWork.layout(a);View root=(View)TestWork.get(a,"root");
        assertTrue(root.getWidth()>0&&root.getHeight()>0);
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File dir=new File(System.getProperty("rike.screenshots","build/reports/ui"),BuildConfig.DEBUG?"debug":"release");
        assertTrue(dir.isDirectory()||dir.mkdirs());
        try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}
        finally{bitmap.recycle();}
    }
    @Test public void renderFirstUseAndCorePages()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a=TestWork.offline(c.get());
        try{
            capture(a,"01-first-use");
            JSONObject data=Records.empty();
            for(String name:new String[]{"静坐","站桩","诵读","行走"})Records.upsert(data,"practiceType",new JSONObject().put("id",name).put("name",name));
            for(int minutes:new int[]{5,10,15,20,30,60})Records.upsert(data,"durationPreset",new JSONObject().put("id","m"+minutes).put("minutes",minutes));
            String today=LocalDate.now().toString();
            Records.upsert(data,"checkIn",new JSONObject().put("id","synthetic-check").put("practiceDate",today).put("practiceTypeId","静坐").put("practiceTypeName","静坐").put("durationMinutes",30).put("note","示例练习备注"));
            VaultCrypto.Created keys=VaultCrypto.create("synthetic-render-password".toCharArray());
            new VaultStore(a).write(VaultCrypto.encrypt(keys.session,data.toString().getBytes(StandardCharsets.UTF_8)));
            set(a,"session",keys.session);set(a,"data",data);call(a,"showApp");capture(a,"02-today");
            TestWork.named(a.getWindow().getDecorView(),"补记日期或添加备注").performClick();capture(a,"03-today-details");
            TestWork.navigate(a,1);capture(a,"04-records");
            TestWork.navigate(a,2);EditText content=TestWork.views(a.getWindow().getDecorView(),EditText.class).get(0);
            content.setText("示例日记：今天练习了三十分钟。\n慢慢记录，不急着下结论。");TestWork.flush(a);capture(a,"05-journal-draft");
            TestWork.named(a.getWindow().getDecorView(),"保存感悟").performClick();TestWork.drain(a);capture(a,"06-journal-saved");
            TestWork.navigate(a,3);capture(a,"07-statistics");
            TestWork.navigate(a,5);capture(a,"08-settings");
            TestWork.navigate(a,4);capture(a,"09-backups");
            TestWork.navigate(a,0);TestWork.named(a.getWindow().getDecorView(),"切换夜间").performClick();capture(a,"10-today-dark");
            TestWork.named(a.getWindow().getDecorView(),"锁定").performClick();capture(a,"11-unlock-dark");
        }finally{c.pause().stop().destroy();}
    }
}
