package nz.co.electricbolt.xt.cpu;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

public class EMS {

    private static final int PAGE_SIZE = 0x4000;
    private static final int PAGE_FRAME_PAGES = 4;
    private static final int PAGE_FRAME_SEGMENT = 0xE000;
    private static final int PAGE_FRAME_SEGMENT_STEP = 0x0400;

    private final byte[] emsMemory;
    private final BitSet freePhysicalPages;
    private final Map<Integer, HandleInfo> handles;
    private final int[] pageFrameMappings;
    private int nextHandle;

    private static EMS instance;

    public EMS(int totalMemoryKB) {
        int totalPages = (totalMemoryKB * 1024) / PAGE_SIZE;
        emsMemory = new byte[totalPages * PAGE_SIZE];
        freePhysicalPages = new BitSet(totalPages);
        freePhysicalPages.set(0, totalPages);
        handles = new HashMap<>();
        pageFrameMappings = new int[PAGE_FRAME_PAGES];
        for (int i = 0; i < PAGE_FRAME_PAGES; i++) {
            pageFrameMappings[i] = -1;
        }
        nextHandle = 1;
    }

    public int getPageFrameSegment() {
        return PAGE_FRAME_SEGMENT;
    }

    public int getVersion() {
        return 0x0400;
    }

    public int getTotalPages() {
        return emsMemory.length / PAGE_SIZE;
    }

    public int getFreePages() {
        return freePhysicalPages.cardinality();
    }

    public int allocatePages(int pages, int[] handleOut) {
        if (pages <= 0) return 0x80;
        if (freePhysicalPages.cardinality() < pages) return 0x87;
        int handle = nextHandle++;
        int[] physicalPages = new int[pages];
        int found = 0;
        for (int i = 0; i < freePhysicalPages.size() && found < pages; i++) {
            if (freePhysicalPages.get(i)) {
                physicalPages[found] = i;
                found++;
            }
        }
        for (int i = 0; i < pages; i++) {
            freePhysicalPages.clear(physicalPages[i]);
        }
        HandleInfo info = new HandleInfo();
        info.pages = pages;
        info.physicalPages = physicalPages;
        handles.put(handle, info);
        handleOut[0] = handle;
        return 0;
    }

    public int freePages(int handle) {
        HandleInfo info = handles.remove(handle);
        if (info == null) return 0x83;
        for (int i = 0; i < info.pages; i++) {
            freePhysicalPages.set(info.physicalPages[i]);
        }
        for (int i = 0; i < PAGE_FRAME_PAGES; i++) {
            if (pageFrameMappings[i] == handle) {
                pageFrameMappings[i] = -1;
            }
        }
        return 0;
    }

    public int getHandlePages(int handle, int[] pagesOut) {
        HandleInfo info = handles.get(handle);
        if (info == null) return 0x83;
        pagesOut[0] = info.pages;
        return 0;
    }

    public int mapPage(int handle, int logicalPage, int physicalPageFrame) {
        // EMS error codes: 0x8B = invalid physical page, 0x8A = invalid logical page, 0x83 = invalid handle.
        if (physicalPageFrame < 0 || physicalPageFrame >= PAGE_FRAME_PAGES) return 0x8B;
        HandleInfo info = handles.get(handle);
        if (info == null) return 0x83;
        // Logical page 0xFFFF unmaps the physical page (LIM EMS convention).
        if (logicalPage == 0xFFFF) {
            pageFrameMappings[physicalPageFrame] = -1;
            return 0;
        }
        if (logicalPage < 0 || logicalPage >= info.pages) return 0x8A;
        int physicalPage = info.physicalPages[logicalPage];
        pageFrameMappings[physicalPageFrame] = physicalPage;
        return 0;
    }

    public int unmapPage(int physicalPageFrame) {
        if (physicalPageFrame < 0 || physicalPageFrame >= PAGE_FRAME_PAGES) return 0x8B;
        pageFrameMappings[physicalPageFrame] = -1;
        return 0;
    }

    public boolean isPageFrameSegment(int segment) {
        int diff = segment - PAGE_FRAME_SEGMENT;
        if (diff < 0) return false;
        if (diff >= PAGE_FRAME_PAGES * PAGE_FRAME_SEGMENT_STEP) return false;
        return (diff % PAGE_FRAME_SEGMENT_STEP) == 0;
    }

    /**
     * Returns true if the page-frame slot for the given segment currently has a handle page mapped.
     * Memory.writeByte/writeWord must fall through to real RAM (not silently drop the write) when this
     * is false, matching the read-side behaviour in EMS.readByte/Memory.readByte.
     */
    public boolean isPageFrameMapped(int segment) {
        int diff = segment - PAGE_FRAME_SEGMENT;
        if (diff < 0 || diff >= PAGE_FRAME_PAGES * PAGE_FRAME_SEGMENT_STEP) return false;
        int slot = diff / PAGE_FRAME_SEGMENT_STEP;
        return pageFrameMappings[slot] != -1;
    }

    public int readByte(int linearAddress) {
        int frameOffset = (linearAddress - (PAGE_FRAME_SEGMENT << 4)) & 0xFFFFF;
        int pageFrameIndex = frameOffset / PAGE_SIZE;
        if (pageFrameIndex < 0 || pageFrameIndex >= PAGE_FRAME_PAGES) {
            return -1;
        }
        int physicalPage = pageFrameMappings[pageFrameIndex];
        if (physicalPage == -1) {
            return -1;
        }
        int pageOffset = frameOffset % PAGE_SIZE;
        int addr = physicalPage * PAGE_SIZE + pageOffset;
        if (addr < 0 || addr >= emsMemory.length) {
            return -1;
        }
        return emsMemory[addr] & 0xFF;
    }

    public void writeByte(int linearAddress, int value) {
        int frameOffset = (linearAddress - (PAGE_FRAME_SEGMENT << 4)) & 0xFFFFF;
        int pageFrameIndex = frameOffset / PAGE_SIZE;
        if (pageFrameIndex < 0 || pageFrameIndex >= PAGE_FRAME_PAGES) {
            return;
        }
        int physicalPage = pageFrameMappings[pageFrameIndex];
        if (physicalPage == -1) {
            return;
        }
        int pageOffset = frameOffset % PAGE_SIZE;
        int addr = physicalPage * PAGE_SIZE + pageOffset;
        if (addr < 0 || addr >= emsMemory.length) {
            return;
        }
        emsMemory[addr] = (byte) (value & 0xFF);
    }

    /**
     * Reads a byte from the backing store of a handle's logical page (independent of the page-frame mapping).
     * Used by the Move/Exchange Memory Region function (AH=57h). Returns -1 if handle/page/offset is invalid.
     */
    public int readLogical(int handle, int logicalPage, int offset) {
        int addr = logicalAddress(handle, logicalPage, offset);
        if (addr < 0) return -1;
        return emsMemory[addr] & 0xFF;
    }

    /**
     * Writes a byte to the backing store of a handle's logical page (independent of the page-frame mapping).
     * Used by the Move/Exchange Memory Region function (AH=57h). Returns false if handle/page/offset is invalid.
     */
    public boolean writeLogical(int handle, int logicalPage, int offset, int value) {
        int addr = logicalAddress(handle, logicalPage, offset);
        if (addr < 0) return false;
        emsMemory[addr] = (byte) (value & 0xFF);
        return true;
    }

    /**
     * Returns true if the given handle currently exists.
     */
    public boolean handleExists(int handle) {
        return handles.containsKey(handle);
    }

    /**
     * Resolves (handle, logicalPage, offset) to a flat index into {@code emsMemory}, or -1 if out of range.
     * {@code offset} may span beyond a single 16 KB page; logical pages of a handle are treated as a
     * contiguous run for the purposes of a Move/Exchange region, matching real EMM behaviour.
     */
    private int logicalAddress(int handle, int logicalPage, int offset) {
        HandleInfo info = handles.get(handle);
        if (info == null) return -1;
        int flat = logicalPage * PAGE_SIZE + offset;
        if (flat < 0) return -1;
        int page = flat / PAGE_SIZE;
        int pageOffset = flat % PAGE_SIZE;
        if (page < 0 || page >= info.pages) return -1;
        int physicalPage = info.physicalPages[page];
        return physicalPage * PAGE_SIZE + pageOffset;
    }

    private static class HandleInfo {
        int pages;
        int[] physicalPages;
    }

    public static void init(int totalMemoryKB) {
        instance = new EMS(totalMemoryKB);
    }

    public static EMS getInstance() {
        return instance;
    }

}
