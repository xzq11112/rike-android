package app.rike.offline;

/** Public update metadata only; retained across rotation, never vault state or an Activity. */
final class StartupUpdateFlow {
    private boolean newSession=true, attempted, externalDeparture, prompted;
    private long session;
    private UpdateChecker.Release pending;
    void enter() {
        if(newSession){session++;attempted=false;prompted=false;pending=null;newSession=false;}
        externalDeparture=false;
    }
    void externalDeparture(){externalDeparture=true;}
    void departureFailed(){externalDeparture=false;}
    void stop(boolean changingConfiguration) {
        if(!changingConfiguration&&!externalDeparture)newSession=true;
    }
    boolean beginAutomaticCheck() {
        if(attempted||newSession)return false;
        attempted=true;return true;
    }
    void manualCheck(){attempted=true;prompted=false;pending=null;}
    long session(){return session;}
    void cancel(){pending=null;}
    void completed(long ticket,UpdateChecker.Release release,String installed,String skipped,boolean manual) {
        if(ticket!=session||newSession||prompted)return;
        if(release!=null&&release.isAndroidUpdate(installed)&&(manual||!release.tag.equals(skipped)))pending=release;
    }
    UpdateChecker.Release takePrompt(boolean foreground,boolean unlocked,boolean busy,boolean editing,boolean otherDialog) {
        if(!foreground||!unlocked||busy||editing||otherDialog)return null;
        UpdateChecker.Release result=pending;pending=null;if(result!=null)prompted=true;return result;
    }
}
