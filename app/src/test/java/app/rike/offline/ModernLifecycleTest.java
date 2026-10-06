package app.rike.offline;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33) @LooperMode(LooperMode.Mode.PAUSED)
public class ModernLifecycleTest {
    @Test public void modernBackRegistrationIsRemovedOnDestroyAndWorkerSurvives()throws Exception{
        ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();MainActivity a=c.get();assertNotNull(TestWork.get(a,"modernBack"));c.pause().stop().destroy();assertFalse(((java.util.concurrent.ExecutorService)TestWork.get(a,"crypto")).isShutdown());assertNull(TestWork.get(a,"session"));
    }
}
