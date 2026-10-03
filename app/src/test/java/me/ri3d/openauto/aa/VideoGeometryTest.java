package me.ri3d.openauto.aa;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class VideoGeometryTest {

    private static Session.Config cfg(int res, int areaW, int areaH) {
        Session.Config c = new Session.Config();
        c.setResolution(res);
        int[] m = VideoGeometry.margins(c.videoWidth, c.videoHeight, areaW, areaH);
        c.marginWidth = m[0];
        c.marginHeight = m[1];
        return c;
    }

    @Test
    public void marginsMatchTheDisplayShape() {
        // same shape: nothing to crop
        assertArrayEquals(new int[]{0, 0}, VideoGeometry.margins(800, 480, 800, 480));
        assertArrayEquals(new int[]{0, 0}, VideoGeometry.margins(1280, 720, 1920, 1080));
        // a 1024x600 radio is slightly wider than 5:3
        assertArrayEquals(new int[]{0, 12}, VideoGeometry.margins(800, 480, 1024, 600));
        // the real ADAYO head unit: 1920x720 (8:3)
        assertArrayEquals(new int[]{0, 180}, VideoGeometry.margins(800, 480, 1920, 720));
        assertArrayEquals(new int[]{0, 240}, VideoGeometry.margins(1280, 720, 1920, 720));
        assertArrayEquals(new int[]{0, 360}, VideoGeometry.margins(1920, 1080, 1920, 720));
        // a 20:9-ish phone in landscape (2833x1344 beside the camera cutout): 800 x 380 content
        assertArrayEquals(new int[]{0, 100}, VideoGeometry.margins(800, 480, 2833, 1344));
        // a 4:3 display loses columns instead
        assertArrayEquals(new int[]{160, 0}, VideoGeometry.margins(800, 480, 1024, 768));
        // extreme shapes are capped at half the frame; unknown area means no margins
        assertArrayEquals(new int[]{0, 240}, VideoGeometry.margins(800, 480, 4000, 400));
        assertArrayEquals(new int[]{0, 0}, VideoGeometry.margins(800, 480, 0, 0));
    }

    @Test
    public void theVideoViewNeverLeavesTheScreen() {
        // matching margins: the content view is the whole display
        assertArrayEquals(new int[]{1920, 720}, VideoGeometry.fit(cfg(480, 1920, 720), 1920, 720));
        assertArrayEquals(new int[]{1920, 720}, VideoGeometry.fit(cfg(1080, 1920, 720), 1920, 720));
        // no margins on an 8:3 display ("Bars" mode): 5:3 picture centred with bars left and right
        assertArrayEquals(new int[]{1200, 720}, VideoGeometry.fit(new Session.Config(), 1920, 720));
        // margins computed for another shape still fit inside, never larger than the area
        Session.Config stale = cfg(480, 2833, 1344);
        int[] v = VideoGeometry.fit(stale, 1920, 720);
        assertArrayEquals(new int[]{1516, 720}, v);
    }

    /**
     * Measured against a real phone: with a 110 px vertical margin on 800x480 a tap sent as (765, 330)
     * hit the control drawn at frame position (765, 385), so touch is content-relative. The view shows
     * exactly the content, so a view position maps linearly onto content coordinates.
     */
    @Test
    public void touchIsReportedInContentCoordinates() {
        Session.Config c = new Session.Config();
        c.marginHeight = 110; // content 800x370
        int[] p = new int[2];
        VideoGeometry.toContent(c, 800, 370, 765, 330, p);
        assertArrayEquals(new int[]{765, 330}, p);
        VideoGeometry.toContent(c, 1600, 740, 1530, 660, p); // same spot on a view twice the size
        assertArrayEquals(new int[]{765, 330}, p);
        VideoGeometry.toContent(c, 1600, 740, 5000, -3, p); // out of range is clamped
        assertArrayEquals(new int[]{799, 0}, p);
    }
}
