package com.vanvatcorporation.doubleclips;

import java.nio.ByteBuffer;

/** Plain-Java checks of {@link OpticalFlowMath}: prints "ok" or fails. */
public final class OpticalFlowMathSelfTest {

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }

    private static ByteBuffer frame(int w, int h, java.util.function.IntBinaryOperator lumaAt) {
        ByteBuffer b = ByteBuffer.allocate(w * h * 4);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int v = Math.max(0, Math.min(255, lumaAt.applyAsInt(x, y)));
            b.put((byte) v).put((byte) v).put((byte) v).put((byte) 255);
        }
        return b;
    }

    public static void main(String[] args) {
        // the grid: frames at 0, 1/60, 2/60 ...
        check(OpticalFlowMath.snapToGrid(0.0, 60) == 0.0, "snap 0");
        check(Math.abs(OpticalFlowMath.snapToGrid(0.0249, 60) - 1.0 / 60) < 1e-12, "snap inside the first frame");
        check(Math.abs(OpticalFlowMath.snapToGrid(1.0 / 60, 60) - 1.0 / 60) < 1e-12, "snap exactly on a frame stays");
        check(Math.abs(OpticalFlowMath.snapToGrid(0.0333, 30) - 1.0 / 30) < 1e-12 || OpticalFlowMath.snapToGrid(0.0333, 30) == 0.0, "snap 30");
        check(OpticalFlowMath.snapToGrid(-0.4, 60) == -0.4, "before the clip: untouched");
        check(OpticalFlowMath.snapToGrid(0.77, 0) == 0.77 && OpticalFlowMath.snapToGrid(0.77, Double.NaN) == 0.77, "bad fps: untouched");

        check(OpticalFlowMath.modeFor(0.0) == OpticalFlowMath.Mode.FIRST && OpticalFlowMath.modeFor(0.5) == OpticalFlowMath.Mode.MIX
                && OpticalFlowMath.modeFor(0.99) == OpticalFlowMath.Mode.SECOND, "modes");

        // the pyramid: 1920x1080 for an export is 480 wide at mip 2, four levels (1080 >> 5 = 33 still has 24 pixels)
        int[] p = OpticalFlowMath.plan(1920, 1080, OpticalFlowMath.EXPORT_FLOW_SIDE);
        check(p[0] == 2 && p[1] == 4, "plan 1080p: " + p[0] + "," + p[1]);
        p = OpticalFlowMath.plan(640, 360, OpticalFlowMath.EXPORT_FLOW_SIDE);
        check(p[0] == 1 && p[1] == 3, "plan 640x360: " + p[0] + "," + p[1]); // the harness test case
        p = OpticalFlowMath.plan(1280, 720, OpticalFlowMath.PREVIEW_FLOW_SIDE);
        check(p[0] == 2 && p[1] == 3, "plan 1280x720 preview: " + p[0] + "," + p[1]);
        p = OpticalFlowMath.plan(64, 36, OpticalFlowMath.EXPORT_FLOW_SIDE);
        check(p[0] == 0 && p[1] == 1, "plan tiny: " + p[0] + "," + p[1]);
        check(OpticalFlowMath.mipSize(1080, 2) == 270 && OpticalFlowMath.mipSize(3, 5) == 1, "mip sizes");

        // scene cut: a pan of a textured picture is not a cut, a different picture is
        int w = 640, h = 360;
        ByteBuffer a = frame(w, h, (x, y) -> 128 + (int) (100 * Math.sin(x * 0.05) * Math.cos(y * 0.07)));
        ByteBuffer panned = frame(w, h, (x, y) -> 128 + (int) (100 * Math.sin((x + 20) * 0.05) * Math.cos((y + 6) * 0.07)));
        ByteBuffer other = frame(w, h, (x, y) -> ((x / 40 + y / 40) % 2 == 0) ? 230 : 25);
        ByteBuffer dim = frame(w, h, (x, y) -> (128 + (int) (100 * Math.sin(x * 0.05) * Math.cos(y * 0.07))) * 9 / 10);
        check(!OpticalFlowMath.isSceneCut(a, panned, w, h), "a pan is not a cut");
        check(!OpticalFlowMath.isSceneCut(a, dim, w, h), "a slight fade is not a cut");
        check(OpticalFlowMath.isSceneCut(a, frame(w, h, (x, y) -> 20), w, h), "to a dark frame is a cut");
        check(OpticalFlowMath.isSceneCut(frame(w, h, (x, y) -> 235), other, w, h), "a white page to a checkerboard is a cut");
        System.out.println("ok");
    }
}
