package app.rike.offline;

import android.view.View;
import android.view.ViewGroup;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class OptionFlowLayoutTest {
    @Test public void loneLastRowItemKeepsItsWidthAndLongItemFitsSmallScreens() {
        OptionFlowLayout flow=new OptionFlowLayout(RuntimeEnvironment.getApplication());
        for(int i=0;i<3;i++){View item=new View(flow.getContext());flow.addView(item,new ViewGroup.LayoutParams(120,48));}
        flow.measure(View.MeasureSpec.makeMeasureSpec(270,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));flow.layout(0,0,270,flow.getMeasuredHeight());
        assertEquals(flow.getChildAt(0).getTop(),flow.getChildAt(1).getTop());assertTrue(flow.getChildAt(2).getTop()>flow.getChildAt(1).getTop());assertEquals(120,flow.getChildAt(2).getWidth());assertEquals(flow.getChildAt(0).getWidth(),flow.getChildAt(2).getWidth());assertTrue(flow.getChildAt(2).getRight()<flow.getWidth());
        android.widget.Button longName=new android.widget.Button(flow.getContext());longName.setText("很长的练习类型名称很长的练习类型名称");flow.addView(longName);
        flow.measure(View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));flow.layout(0,0,160,flow.getMeasuredHeight());assertTrue(longName.getRight()<=flow.getWidth());assertTrue(longName.getHeight()>=48);
    }
}
