package com.vanvatcorporation.doubleclips;

/** Plain-Java checks of {@link ClipProcessingTracker}: {@code java ...ClipProcessingTrackerSelfTest} prints "ok" or fails. */
public final class ClipProcessingTrackerSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        ClipProcessingTracker t = new ClipProcessingTracker();
        int[] fired = {0};
        t.addListener(() -> fired[0]++);
        Object a = new Object(), b = new Object();

        check(t.clipLabel(a) == null && t.badgeTitle() == null && t.runningCount() == 0, "idle");
        ClipProcessingTracker.Job j = t.begin(a, "Motion blur");
        check("Applying Motion blur... 0.0%".equals(t.clipLabel(a)), "label at 0: " + t.clipLabel(a));
        check("Applying 1 feature...".equals(t.badgeTitle()), "badge one");
        j.progress(0.526);
        check("Applying Motion blur... 52.6%".equals(t.clipLabel(a)), "label at 52.6: " + t.clipLabel(a));
        check("Processing 52.6%".equals(t.propertiesLabel(a)), "properties label");
        check(t.clipLabel(b) == null, "another clip is untouched");

        ClipProcessingTracker.Job k = t.begin(a, "Optical flow");
        k.progress(0.5);
        check("Applying 2 features... 51.3%".equals(t.clipLabel(a)), "two features: " + t.clipLabel(a));
        check("Applying 2 features...".equals(t.badgeTitle()), "badge two");

        // the same clip and feature again replaces the older job
        ClipProcessingTracker.Job j2 = t.begin(a, "Motion blur");
        check(t.runningCount() == 2 && t.find(a, "Motion blur") == j2, "replaced");
        j.progress(0.9); // the replaced job's owner may still report: nothing breaks
        j2.progress(2.0);
        check(j2.progress() == 1.0, "clamped to 1");
        j2.finish();
        check(t.runningCount() == 1 && t.lastFinished() == null, "one still running: no 'applied' yet");
        k.finish();
        check(t.runningCount() == 0 && t.lastFinished() == k && t.clipLabel(a) == null, "all done");
        k.progress(0.1);
        check(k.progress() == 1.0 && k.state() == ClipProcessingTracker.State.DONE, "an ended job ignores progress");

        ClipProcessingTracker.Job f = t.begin(b, "Reverse");
        f.fail("no disk space");
        check(f.state() == ClipProcessingTracker.State.FAILED && "no disk space".equals(f.message()) && t.runningCount() == 0, "failed");
        t.begin(b, "Reverse");
        t.drop(b);
        check(t.runningCount() == 0, "dropped");
        check("100%".equals(ClipProcessingTracker.percent(1.0)) && "0.0%".equals(ClipProcessingTracker.percent(-1)), "percent");

        // a broken listener doesn't stop the others
        t.addListener(() -> { throw new IllegalStateException("boom"); });
        int before = fired[0];
        t.begin(a, "x").finish();
        check(fired[0] > before, "listeners still told");
        System.out.println("ok");
    }
}
