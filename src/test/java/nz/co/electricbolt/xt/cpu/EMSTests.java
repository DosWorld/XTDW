package nz.co.electricbolt.xt.cpu;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the EMS data-access path: mapping a logical page into the page-frame window, writing through the
 * window, remapping, and reading the data back. Covers the bugs reported in ems-xt.md (Bug 2 / Bug 3) at the
 * EMS-core level, plus the logical-page accessors used by INT 67h AH=57h (Bug 1).
 */
public class EMSTests {

    private static final int PAGE_SIZE = 0x4000;
    private static final int FRAME_SEG = 0xE000;

    @Test
    public void mapValidHandleSucceeds() {
        EMS ems = new EMS(64); // 4 pages of 16 KB
        int[] handle = new int[1];
        assertEquals(0, ems.allocatePages(2, handle));
        // AL=physical 0, BX=logical 0, DX=handle.
        assertEquals(0, ems.mapPage(handle[0], 0, 0));
        assertEquals(0, ems.mapPage(handle[0], 1, 1));
    }

    @Test
    public void invalidHandleReturns83() {
        EMS ems = new EMS(64);
        assertEquals(0x83, ems.mapPage(999, 0, 0));
    }

    @Test
    public void invalidPhysicalPageReturns8B() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(1, handle);
        assertEquals(0x8B, ems.mapPage(handle[0], 0, 99));
    }

    @Test
    public void invalidLogicalPageReturns8A() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(1, handle); // only logical page 0 exists
        assertEquals(0x8A, ems.mapPage(handle[0], 5, 0));
    }

    @Test
    public void writeThroughFrameAndReadBackAcrossRemap() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        assertEquals(0, ems.allocatePages(2, handle));

        // Map logical page 0 -> physical frame 0, write 16 bytes through the window at E000:0000.
        assertEquals(0, ems.mapPage(handle[0], 0, 0));
        int frameBase = FRAME_SEG << 4;
        for (int i = 0; i < 16; i++) {
            ems.writeByte(frameBase + i, 0x40 + i);
        }

        // Map a different logical page into the same physical frame, then map page 0 back.
        assertEquals(0, ems.mapPage(handle[0], 1, 0));
        assertEquals(0, ems.mapPage(handle[0], 0, 0));

        // Data must persist (Bug 2/3: the window is backed by the handle's pages, not raw RAM).
        for (int i = 0; i < 16; i++) {
            assertEquals(0x40 + i, ems.readByte(frameBase + i), "byte " + i);
        }
    }

    @Test
    public void differentLogicalPagesAreIndependent() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(2, handle);
        int frameBase = FRAME_SEG << 4;

        ems.mapPage(handle[0], 0, 0);
        ems.writeByte(frameBase, 0xAA);
        ems.mapPage(handle[0], 1, 0);
        ems.writeByte(frameBase, 0xBB);

        ems.mapPage(handle[0], 0, 0);
        assertEquals(0xAA, ems.readByte(frameBase));
        ems.mapPage(handle[0], 1, 0);
        assertEquals(0xBB, ems.readByte(frameBase));
    }

    @Test
    public void frameOffsetIntoSecondPageSlot() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(2, handle);
        // Map two distinct logical pages into two distinct physical frame slots.
        ems.mapPage(handle[0], 0, 0);
        ems.mapPage(handle[0], 1, 1);
        int frameBase = FRAME_SEG << 4;

        // Slot 1 is at offset PAGE_SIZE within the 64 KB window.
        ems.writeByte(frameBase + PAGE_SIZE, 0x99);
        assertEquals(0x99, ems.readByte(frameBase + PAGE_SIZE));
        // Slot 0 byte 0 must be untouched.
        ems.writeByte(frameBase, 0x11);
        assertEquals(0x11, ems.readByte(frameBase));
        assertEquals(0x99, ems.readByte(frameBase + PAGE_SIZE));
    }

    @Test
    public void logicalAccessorsForMoveRegion() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(2, handle);

        // Write spanning across the logical page boundary (offset into page 1 via flat addressing).
        assertTrue(ems.writeLogical(handle[0], 0, 10, 0x55));
        assertTrue(ems.writeLogical(handle[0], 1, 20, 0x66));
        assertEquals(0x55, ems.readLogical(handle[0], 0, 10));
        assertEquals(0x66, ems.readLogical(handle[0], 1, 20));

        // Invalid handle/page.
        assertEquals(-1, ems.readLogical(999, 0, 0));
        assertFalse(ems.writeLogical(handle[0], 5, 0, 0));
    }

    @Test
    public void logicalAccessIsVisibleThroughMappedFrame() {
        EMS ems = new EMS(64);
        int[] handle = new int[1];
        ems.allocatePages(1, handle);

        // Write via logical accessor (what AH=57h move-to-EMS does), read via mapped frame.
        ems.writeLogical(handle[0], 0, 0x100, 0x7E);
        ems.mapPage(handle[0], 0, 0);
        assertEquals(0x7E, ems.readByte((FRAME_SEG << 4) + 0x100));
    }
}
