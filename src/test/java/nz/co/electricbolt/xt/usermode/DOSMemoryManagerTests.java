// DOSMemoryManagerTests.java
// XT Copyright © 2025; Electric Bolt Limited.

package nz.co.electricbolt.xt.usermode;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.dos.DOSMemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DOSMemoryManager — INT 21h functions 48h/49h/4Ah.
 *
 * The critical regression tested here is the BX=0 bug: when a program probes free memory
 * with INT 21h/48h BX=FFFFh, real DOS returns CF=1, AX=8, BX=largest free block.
 * XT was returning BX=0 because initialize() assigned all memory to the program and the
 * allocateMemory() loop only counts free (PID==0) blocks. releaseUnusedMemoryAfterLoad()
 * splits the MCB chain so the remainder becomes a free block.
 */
class DOSMemoryManagerTests {

    private static final short PSP_SEGMENT = (short) 0x0090;

    private CPU cpu;
    private DOSMemoryManager mgr;

    @BeforeEach
    void setUp() {
        cpu = new CPU(null);
        mgr = new DOSMemoryManager(cpu);
        mgr.initialize(PSP_SEGMENT);
    }

    // -------------------------------------------------------------------------
    // releaseUnusedMemoryAfterLoad — the probe-fix regression
    // -------------------------------------------------------------------------

    /**
     * After loading a typical EXE (SS=0x0146, SP=0x1FFE) the free block should be
     * non-zero. Before the fix this always returned BX=0 because no free block existed.
     */
    @Test
    void probeAfterRelease_returnNonZeroFreeBlock() {
        // Simulate a loaded EXE: SS=0x0146, SP=0x1FFE (8 KB stack)
        short ss = (short) 0x0146;
        short sp = (short) 0x1FFE;
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, ss, sp);

        // Probe: request 0xFFFF paragraphs — should fail with CF=1, AX=8, BX>0
        cpu.getReg().DS.setValue(PSP_SEGMENT);
        mgr.allocateMemory(cpu, (short) 0xFFFF);

        assertTrue(cpu.getReg().flags.isCarry(), "CF must be 1 on probe failure");
        assertEquals((short) 0x0008, cpu.getReg().AX.getValue(), "AX must be 8 (insufficient memory)");
        int bx = cpu.getReg().BX.getValue() & 0xFFFF;
        assertTrue(bx > 0, "BX must be > 0 after releaseUnusedMemoryAfterLoad (was " + bx + ")");
    }

    /**
     * The second step of the Oberon heap init: allocate exactly the BX returned by the
     * probe. This must succeed (CF=0) and return a non-zero segment.
     */
    @Test
    void probeAndAllocate_succeedsWithReturnedSize() {
        short ss = (short) 0x0146;
        short sp = (short) 0x1FFE;
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, ss, sp);

        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Step 1: probe
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        assertTrue(cpu.getReg().flags.isCarry());
        short maxFree = cpu.getReg().BX.getValue();
        assertTrue((maxFree & 0xFFFF) > 0, "probe must return non-zero max free");

        // Step 2: allocate exactly maxFree paragraphs
        mgr.allocateMemory(cpu, maxFree);
        assertFalse(cpu.getReg().flags.isCarry(), "allocation of probe-returned size must succeed");
        int seg = cpu.getReg().AX.getValue() & 0xFFFF;
        assertTrue(seg > 0, "returned segment must be non-zero");
    }

    /**
     * Without calling releaseUnusedMemoryAfterLoad the probe returns BX=0 (the old bug).
     * This test documents the pre-fix behaviour so a regression is immediately visible.
     */
    @Test
    void probeWithoutRelease_returnsBXZero() {
        // No releaseUnusedMemoryAfterLoad — entire memory is owned by the program
        cpu.getReg().DS.setValue(PSP_SEGMENT);
        mgr.allocateMemory(cpu, (short) 0xFFFF);

        assertTrue(cpu.getReg().flags.isCarry());
        assertEquals((short) 0x0000, cpu.getReg().BX.getValue(),
            "without release, BX must be 0 (no free blocks in chain)");
    }

    // -------------------------------------------------------------------------
    // Basic 48h / 49h / 4Ah round-trips
    // -------------------------------------------------------------------------

    @Test
    void allocateAndFree_singleBlock() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        mgr.allocateMemory(cpu, (short) 0x0100);
        assertFalse(cpu.getReg().flags.isCarry(), "allocate 256 paragraphs must succeed");
        short seg = cpu.getReg().AX.getValue();
        assertTrue((seg & 0xFFFF) > 0);

        mgr.freeMemory(cpu, seg);
        assertFalse(cpu.getReg().flags.isCarry(), "free must succeed");
    }

    @Test
    void allocateTwoBlocks_freeFirst_reallocates() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        mgr.allocateMemory(cpu, (short) 0x0080);
        assertFalse(cpu.getReg().flags.isCarry());
        short seg1 = cpu.getReg().AX.getValue();

        mgr.allocateMemory(cpu, (short) 0x0080);
        assertFalse(cpu.getReg().flags.isCarry());
        short seg2 = cpu.getReg().AX.getValue();

        assertNotEquals(seg1, seg2, "two allocations must return different segments");

        // Free the first block; it should be re-usable
        mgr.freeMemory(cpu, seg1);
        assertFalse(cpu.getReg().flags.isCarry());

        mgr.allocateMemory(cpu, (short) 0x0080);
        assertFalse(cpu.getReg().flags.isCarry(), "reallocation into freed block must succeed");
    }

    @Test
    void resizeShrink_makesMoreFreeMemory() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Probe free before shrink
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        int freeBefore = cpu.getReg().BX.getValue() & 0xFFFF;

        // Allocate a 0x0200-paragraph block
        mgr.allocateMemory(cpu, (short) 0x0200);
        assertFalse(cpu.getReg().flags.isCarry());
        short seg = cpu.getReg().AX.getValue();

        // Shrink to 0x0100
        cpu.getReg().ES.setValue(seg);
        mgr.resizeMemory(cpu, seg, (short) 0x0100);
        assertFalse(cpu.getReg().flags.isCarry(), "shrink must succeed");

        // Free that block
        mgr.freeMemory(cpu, seg);
        assertFalse(cpu.getReg().flags.isCarry());

        // Probe again — free should be at least as large as before
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        int freeAfter = cpu.getReg().BX.getValue() & 0xFFFF;
        assertTrue(freeAfter >= freeBefore,
            "free memory after shrink+free must be >= original free (" + freeAfter + " vs " + freeBefore + ")");
    }

    @Test
    void probeWithZero_returnsLargestBlock() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Probe with BX=0
        mgr.allocateMemory(cpu, (short) 0);
        assertTrue(cpu.getReg().flags.isCarry());
        assertEquals((short) 0x0008, cpu.getReg().AX.getValue());
        int bx = cpu.getReg().BX.getValue() & 0xFFFF;
        assertTrue(bx > 0);
    }

    @Test
    void fragmentedMemory_isMergedOnAllocate() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Allocate three blocks
        mgr.allocateMemory(cpu, (short) 0x0100);
        short seg1 = cpu.getReg().AX.getValue();
        mgr.allocateMemory(cpu, (short) 0x0100);
        short seg2 = cpu.getReg().AX.getValue();
        mgr.allocateMemory(cpu, (short) 0x0100);
        short seg3 = cpu.getReg().AX.getValue();

        // Free the middle one and the third one - they are adjacent
        mgr.freeMemory(cpu, seg2);
        mgr.freeMemory(cpu, seg3);

        // Now we have two adjacent free blocks of 0x100 each (+ 1 for MCB of seg3)
        // A probe should see them as merged
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        int bx = cpu.getReg().BX.getValue() & 0xFFFF;
        assertTrue(bx >= 0x0201, "Merged block must be at least 0x201 (got " + Integer.toHexString(bx) + ")");
    }

    @Test
    void resizeGrow_returnsMaxSizeAvailable() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Allocate a block
        mgr.allocateMemory(cpu, (short) 0x0100);
        short seg1 = cpu.getReg().AX.getValue();

        // Allocate another block to act as a barrier
        mgr.allocateMemory(cpu, (short) 0x0100);
        short seg2 = cpu.getReg().AX.getValue();

        // Free the barrier - now there is a free block after seg1
        mgr.freeMemory(cpu, seg2);

        // Try to grow seg1 to something much larger than current size + following free block
        mgr.resizeMemory(cpu, seg1, (short) 0xFFFF);
        assertTrue(cpu.getReg().flags.isCarry());
        int bx = cpu.getReg().BX.getValue() & 0xFFFF;
        // Should be 0x100 (current) + 1 (MCB) + 0x100 (free) = 0x201
        // OR more, depending on what else was free after seg2
        assertTrue(bx >= 0x0201, "BX must return maximum possible growth size (got " + Integer.toHexString(bx) + ")");
    }

    /**
     * Regression: a child loaded via INT 21h AH=4B00h (Exec) gets allocated the largest
     * free block. After the child shrinks that block down to its image+stack, a probe
     * with BX=FFFFh inside the child must return BX = paragraphs released, not BX=0.
     */
    @Test
    void postExecChildProbe_returnsFreedTail() {
        // Parent setup: cold-boot release.
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);

        // Exec path: probe + allocate the largest block for the child.
        cpu.getReg().DS.setValue(PSP_SEGMENT);
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        assertTrue(cpu.getReg().flags.isCarry());
        short largest = cpu.getReg().BX.getValue();
        int largestU = largest & 0xFFFF;
        assertTrue(largestU > 0);

        mgr.allocateMemory(cpu, largest);
        assertFalse(cpu.getReg().flags.isCarry(), "Exec must be able to allocate the largest block");
        short childPSP = cpu.getReg().AX.getValue();

        // Exec post-load: shrink the child's block to image+stack.
        int childParagraphs = 0x0200;
        assertTrue(childParagraphs < largestU);
        mgr.resizeMemory(cpu, childPSP, (short) childParagraphs);
        assertFalse(cpu.getReg().flags.isCarry(), "shrink must succeed");

        // Inside the child: probe must return BX = released tail (not 0).
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        assertTrue(cpu.getReg().flags.isCarry());
        assertEquals((short) 0x0008, cpu.getReg().AX.getValue());
        int bx = cpu.getReg().BX.getValue() & 0xFFFF;
        int expected = largestU - childParagraphs - 1; // minus one MCB
        assertEquals(expected, bx, "probe inside child must see the released tail");
    }

    /**
     * Regression: on child termination, every MCB owned by the child's PSP must be
     * freed — not just the child's PSP block. Without this fix, a child that
     * allocates a working heap via AH=48 leaks that heap when it exits, and
     * consecutive Execs of large children eventually exhaust conventional memory.
     */
    @Test
    void freeBlocksOwnedBy_freesAllChildAllocations() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        // Simulate child PSP allocation (large block).
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        short largest = cpu.getReg().BX.getValue();
        mgr.allocateMemory(cpu, largest);
        assertFalse(cpu.getReg().flags.isCarry());
        short childPSP = cpu.getReg().AX.getValue();
        // Mirror Exec.java: patch the PSP block's MCB owner to childPSP so that
        // termination's free-by-owner sweep reclaims it.
        cpu.getMemory().writeWord(new nz.co.electricbolt.xt.cpu.SegOfs((short)(childPSP - 1), (short) 0x0001), childPSP);

        // Child shrinks its PSP block, leaving room in the chain.
        mgr.resizeMemory(cpu, childPSP, (short) 0x0200);
        assertFalse(cpu.getReg().flags.isCarry());

        // Re-point currentPSP to the child for subsequent allocations to be owned by it.
        // The static getCurrentPSP() reads from TerminateProgram, which defaults to 0x0090.
        // We can't easily change it here, so simulate by directly stamping the MCB owner.
        // Instead: allocate two blocks while pretending to be the child by patching ownership.
        mgr.allocateMemory(cpu, (short) 0x0100);
        assertFalse(cpu.getReg().flags.isCarry());
        short child1 = cpu.getReg().AX.getValue();
        // Patch MCB owner to childPSP
        cpu.getMemory().writeWord(new nz.co.electricbolt.xt.cpu.SegOfs((short)(child1 - 1), (short) 0x0001), childPSP);

        mgr.allocateMemory(cpu, (short) 0x0100);
        assertFalse(cpu.getReg().flags.isCarry());
        short child2 = cpu.getReg().AX.getValue();
        cpu.getMemory().writeWord(new nz.co.electricbolt.xt.cpu.SegOfs((short)(child2 - 1), (short) 0x0001), childPSP);

        // Probe free memory before "termination".
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        int freeBeforeTerminate = cpu.getReg().BX.getValue() & 0xFFFF;

        // Terminate: free everything owned by childPSP.
        mgr.freeBlocksOwnedBy(childPSP);

        // Probe free memory after termination. It must include the PSP block,
        // both auxiliary allocations, and their MCB overhead.
        mgr.allocateMemory(cpu, (short) 0xFFFF);
        int freeAfterTerminate = cpu.getReg().BX.getValue() & 0xFFFF;

        // Sanity: more free memory after than before.
        assertTrue(freeAfterTerminate > freeBeforeTerminate,
            "free memory after termination must exceed free memory before (before=" + freeBeforeTerminate
                + " after=" + freeAfterTerminate + ")");

        // The merged free block must be at least as large as the original probe size.
        // (Originally we got `largest` paragraphs; after shrink+two extra allocs the same
        // territory should be reclaimed minus internal MCB overhead.)
        assertTrue(freeAfterTerminate >= (largest & 0xFFFF) - 4,
            "after termination, free block must be approximately the original largest block "
                + "(largest=" + (largest & 0xFFFF) + " after=" + freeAfterTerminate + ")");
    }

    /**
     * Regression for the reported bug: alternating Exec of two different children must
     * not leak. Simulate it by running the Exec->shrink->extra-alloc->terminate cycle
     * twice with different sizes and verify the second Exec succeeds.
     */
    @Test
    void consecutiveExec_doesNotLeak() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        cpu.getReg().DS.setValue(PSP_SEGMENT);

        for (int iter = 0; iter < 5; iter++) {
            int childImage = 0x0200 + iter * 0x40; // varying sizes

            mgr.allocateMemory(cpu, (short) 0xFFFF);
            short largest = cpu.getReg().BX.getValue();
            assertTrue((largest & 0xFFFF) > childImage,
                "iter " + iter + ": probe must report enough free memory (got " + (largest & 0xFFFF) + ")");

            mgr.allocateMemory(cpu, largest);
            assertFalse(cpu.getReg().flags.isCarry(), "iter " + iter + ": Exec allocation must succeed");
            short childPSP = cpu.getReg().AX.getValue();
            cpu.getMemory().writeWord(new nz.co.electricbolt.xt.cpu.SegOfs((short)(childPSP - 1), (short) 0x0001), childPSP);

            mgr.resizeMemory(cpu, childPSP, (short) childImage);
            assertFalse(cpu.getReg().flags.isCarry());

            // Child allocates working heap.
            mgr.allocateMemory(cpu, (short) 0x0400);
            assertFalse(cpu.getReg().flags.isCarry(), "iter " + iter + ": child heap allocation must succeed");
            short heap = cpu.getReg().AX.getValue();
            cpu.getMemory().writeWord(new nz.co.electricbolt.xt.cpu.SegOfs((short)(heap - 1), (short) 0x0001), childPSP);

            // Child terminates.
            mgr.freeBlocksOwnedBy(childPSP);
        }
    }

    @Test
    void freeInvalidSegment_setsCarry() {
        mgr.releaseUnusedMemoryAfterLoad(PSP_SEGMENT, (short) 0x0146, (short) 0x1FFE);
        mgr.freeMemory(cpu, (short) 0x1234); // not an MCB+1 segment
        assertTrue(cpu.getReg().flags.isCarry(), "freeing unknown segment must set CF");
    }
}
