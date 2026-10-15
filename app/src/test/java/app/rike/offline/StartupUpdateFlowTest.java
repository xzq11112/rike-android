package app.rike.offline;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupUpdateFlowTest {
    private static UpdateChecker.Release release(String version){return new UpdateChecker.Release(version,"synthetic-android-asset");}
    private static UpdateChecker.Release take(StartupUpdateFlow f){return f.takePrompt(true,true,false,false,false);}
    @Test public void oncePerOpeningAndNoRetryOnAutomaticFailure() {
        StartupUpdateFlow f=new StartupUpdateFlow();f.enter();assertTrue(f.beginAutomaticCheck());
        f.completed(f.session(),null,"1.0.0","",false);assertNull(take(f));assertFalse(f.beginAutomaticCheck());
        f.stop(false);f.enter();assertTrue(f.beginAutomaticCheck());
    }
    @Test public void rotationsBrowserPickerAndBiometricReturnsKeepSession() {
        StartupUpdateFlow f=new StartupUpdateFlow();f.enter();assertTrue(f.beginAutomaticCheck());long opening=f.session();
        f.stop(true);f.enter();assertEquals(opening,f.session());assertFalse(f.beginAutomaticCheck());
        f.externalDeparture();f.stop(false);f.enter();assertEquals(opening,f.session());assertFalse(f.beginAutomaticCheck());
        // A pause without onStop (such as biometric authentication) never changes the opening.
        f.enter();assertEquals(opening,f.session());assertFalse(f.beginAutomaticCheck());
        f.externalDeparture();f.departureFailed();f.stop(false);f.enter();assertTrue(f.beginAutomaticCheck());
    }
    @Test public void skippedTagSurvivesNewFlowHigherVersionAndManualCheckRemainAvailable() {
        String persisted="v2.0.0";StartupUpdateFlow f=new StartupUpdateFlow();f.enter();
        f.completed(f.session(),release("2.0.0"),"1.0.0",persisted,false);assertNull(take(f));
        f.completed(f.session(),release("2.0.1"),"1.0.0",persisted,false);assertEquals("2.0.1",take(f).version);
        f.manualCheck();f.completed(f.session(),release("2.0.0"),"1.0.0",persisted,true);assertNotNull(take(f));
    }
    @Test public void onlyNewerAndroidReleasePromptsAndPromptIsConsumedOnce() {
        StartupUpdateFlow f=new StartupUpdateFlow();f.enter();long opening=f.session();
        for(UpdateChecker.Release r:new UpdateChecker.Release[]{release("1.0.0"),release("0.9.9"),new UpdateChecker.Release("2.0.0",null)}) {
            f.completed(opening,r,"1.0.0","",false);assertNull(take(f));
        }
        f.completed(opening,release("2.0.0"),"1.0.0","",false);assertNotNull(take(f));assertNull(take(f));
        f.completed(opening,release("2.0.0"),"1.0.0","",false);assertNull(take(f));
        f.manualCheck();f.completed(opening,release("2.0.0"),"1.0.0","",true);assertNotNull(take(f));
    }
    @Test public void lockedBusyEditingAndOtherDialogDelayWithoutConsuming() {
        StartupUpdateFlow f=new StartupUpdateFlow();f.enter();f.completed(f.session(),release("2.0.0"),"1.0.0","",false);
        assertNull(f.takePrompt(false,true,false,false,false));assertNull(f.takePrompt(true,false,false,false,false));
        assertNull(f.takePrompt(true,true,true,false,false));assertNull(f.takePrompt(true,true,false,true,false));
        assertNull(f.takePrompt(true,true,false,false,true));assertNotNull(take(f));
    }
    @Test public void lockAndOldOpeningDiscardCallbacks() {
        StartupUpdateFlow f=new StartupUpdateFlow();f.enter();long old=f.session();
        f.completed(old,release("2.0.0"),"1.0.0","",false);f.cancel();assertNull(take(f));
        f.stop(false);f.completed(old,release("2.0.0"),"1.0.0","",false);assertNull(take(f));
        f.enter();f.completed(old,release("2.0.0"),"1.0.0","",false);assertNull(take(f));
    }
}
