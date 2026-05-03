package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;

/**
 * DOS Memory Manager using MCB (Memory Control Block) chain.
 * MCB structure (16 bytes):
 * - Offset 0: byte - marker ('M' = middle block, 'Z' = last block)
 * - Offset 1-2: word - PID of owner (0 = free block)
 * - Offset 3-4: word - size in paragraphs (16-byte units)
 * - Offset 5-15: reserved (11 bytes)
 */
public class DOSMemoryManager {
    
    // MCB structure offsets
    private static final short MCB_MARKER = 0x00;
    private static final short MCB_PID = 0x01;
    private static final short MCB_SIZE = 0x03;
    private static final short MCB_RESERVED_START = 0x05;
    private static final short MCB_RESERVED_END = 0x0F;
    
    // MCB marker values
    private static final byte MCB_MIDDLE = 'M';
    private static final byte MCB_LAST = 'Z';
    
    // DOS error codes
    public static final byte ERROR_NO_ERROR = 0x00;
    public static final byte ERROR_INVALID_FUNCTION = 0x01;
    public static final byte ERROR_FILE_NOT_FOUND = 0x02;
    public static final byte ERROR_PATH_NOT_FOUND = 0x03;
    public static final byte ERROR_NO_HANDLES = 0x04;
    public static final byte ERROR_ACCESS_DENIED = 0x05;
    public static final byte ERROR_INVALID_HANDLE = 0x06;
    public static final byte ERROR_MEMORY_CONTROL_BLOCKS_DESTROYED = 0x07;
    public static final byte ERROR_INSUFFICIENT_MEMORY = 0x08;
    public static final byte ERROR_INVALID_MEMORY_BLOCK_ADDRESS = 0x09;
    public static final byte ERROR_INVALID_ENVIRONMENT = 0x0A;
    public static final byte ERROR_INVALID_FORMAT = 0x0B;
    public static final byte ERROR_INVALID_ACCESS = 0x0C;
    public static final byte ERROR_INVALID_DATA = 0x0D;
    
    private final CPU cpu;
    private short firstMCBSegment;
    private boolean initialized = false;
    
    public DOSMemoryManager(CPU cpu) {
        this.cpu = cpu;
    }
    
    /**
     * Initialize the DOS memory chain. Places one MCB at pspSegment-1 that owns the entire
     * remaining memory (PSP through top of conventional RAM), assigned to the program at
     * pspSegment. Programs must call INT 21h/4Ah (resize) to release unused memory to the
     * free pool, exactly as real DOS requires.
     *
     * @param pspSegment The segment of the Program Segment Prefix (e.g. 0x0090)
     */
    public void initialize(short pspSegment) {
        int pspSeg = pspSegment & 0xFFFF;

        // MCB sits one paragraph before the PSP
        firstMCBSegment = (short) (pspSeg - 1);

        // Paragraphs from PSP to top of 640 KB conventional memory (0xA000)
        int topParagraph = 0xA000;
        int programParagraphs = topParagraph - pspSeg;

        writeMCB(firstMCBSegment, MCB_LAST, pspSegment, (short) programParagraphs);
        initialized = true;
    }

    private void writeMCB(short mcbSeg, byte marker, short pid, short size) {
        cpu.getMemory().writeByte(new SegOfs(mcbSeg, MCB_MARKER), marker);
        cpu.getMemory().writeWord(new SegOfs(mcbSeg, MCB_PID), pid);
        cpu.getMemory().writeWord(new SegOfs(mcbSeg, MCB_SIZE), size);
        for (short i = MCB_RESERVED_START; i <= MCB_RESERVED_END; i++)
            cpu.getMemory().writeByte(new SegOfs(mcbSeg, i), (byte) 0);
    }
    
    /**
     * DOS Function 0x48 - Allocate Memory
     * Allocates a block of memory of specified size in paragraphs.
     * 
     * Input:
     *   BX = number of paragraphs requested
     * 
     * Output:
     *   AX = segment of allocated block (on success)
     *   BX = size of largest available block in paragraphs (on failure)
     *   Carry flag = 0 on success, 1 on failure
     *   On failure, AX = error code (0x07 or 0x08)
     */
    public void allocateMemory(CPU cpu, short paragraphsRequested) {
        if (!initialized) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INVALID_FUNCTION);
            return;
        }

        mergeFreeBlocks();

        int paragraphsU = paragraphsRequested & 0xFFFF;

        // Find first free block large enough
        short currentSegment = firstMCBSegment;
        short largestBlockSize = 0;
        short bestBlockSegment = 0;
        short bestBlockSize = 0;

        while (true) {
            // Read MCB marker
            byte marker = cpu.getMemory().readByte(new SegOfs(currentSegment, MCB_MARKER));
            // Read PID
            short pid = cpu.getMemory().readWord(new SegOfs(currentSegment, MCB_PID));
            // Read block size (in paragraphs)
            short blockSize = cpu.getMemory().readWord(new SegOfs(currentSegment, MCB_SIZE));
            int blockSizeU = blockSize & 0xFFFF;

            // If this is a free block (PID == 0)
            if (pid == 0) {
                if (paragraphsU > 0 && blockSizeU >= paragraphsU) {
                    // Found a suitable block
                    if (bestBlockSegment == 0 || blockSizeU < (bestBlockSize & 0xFFFF)) {
                        bestBlockSegment = currentSegment;
                        bestBlockSize = blockSize;
                    }
                }

                // Track largest block for error reporting
                if (blockSizeU > (largestBlockSize & 0xFFFF)) {
                    largestBlockSize = blockSize;
                }
            }
            
            if (marker == MCB_LAST) break;

            int next = (currentSegment & 0xFFFF) + blockSizeU + 1;
            if (next >= 0xA000) break; // walked past conventional RAM
            currentSegment = (short) next;
        }
        
        if (bestBlockSegment == 0) {
            // No block large enough or paragraphsRequested == 0 (probe)
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INSUFFICIENT_MEMORY);
            cpu.getReg().BX.setValue(largestBlockSize);
            return;
        }
        
        // Allocate the block
        short allocatedSegment = bestBlockSegment;
        short allocatedSize = bestBlockSize;

        if ((allocatedSize & 0xFFFF) > paragraphsU) {
            // Split the block: create a new free block after the allocated one
            short newFreeBlockSegment = (short) (allocatedSegment + paragraphsU + 1);
            short newFreeBlockSize = (short) ((allocatedSize & 0xFFFF) - paragraphsU - 1);

            // Get current marker of the original block (to determine if it was last)
            byte originalMarker = cpu.getMemory().readByte(new SegOfs(allocatedSegment, MCB_MARKER));

            // Update allocated block
            cpu.getMemory().writeByte(new SegOfs(allocatedSegment, MCB_MARKER),
                (newFreeBlockSize == 0) ? originalMarker : MCB_MIDDLE);
            cpu.getMemory().writeWord(new SegOfs(allocatedSegment, MCB_SIZE), paragraphsRequested);

            if ((newFreeBlockSize & 0xFFFF) > 0) {
                // Create new MCB for free block
                cpu.getMemory().writeByte(new SegOfs(newFreeBlockSegment, MCB_MARKER), originalMarker);
                cpu.getMemory().writeWord(new SegOfs(newFreeBlockSegment, MCB_PID), (short) 0);
                cpu.getMemory().writeWord(new SegOfs(newFreeBlockSegment, MCB_SIZE), newFreeBlockSize);
                
                // Initialize reserved area
                for (short i = MCB_RESERVED_START; i <= MCB_RESERVED_END; i++) {
                    cpu.getMemory().writeByte(new SegOfs(newFreeBlockSegment, i), (byte) 0);
                }
            }
        } else {
            // Exact fit - just mark as allocated
            // Keep the same marker (M or Z)
            // No need to change marker
        }
        
        // Set owner PID (get current PSP segment - from DS or ES)
        short currentPSP = cpu.getReg().DS.getValue(); // DS typically points to PSP
        cpu.getMemory().writeWord(new SegOfs(allocatedSegment, MCB_PID), currentPSP);
        
        // Success — return the first usable paragraph (one past the MCB)
        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue((short) (allocatedSegment + 1));
    }
    
    public void freeMemory(CPU cpu, short segmentToFree) {
        if (!initialized) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INVALID_FUNCTION);
            return;
        }

        // Programs pass the data segment (MCB+1); the MCB is one paragraph earlier
        short mcbSegment = (short) (segmentToFree - 1);

        if (!isMCBInChain(mcbSegment)) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INVALID_MEMORY_BLOCK_ADDRESS);
            return;
        }

        // Mark as free
        cpu.getMemory().writeWord(new SegOfs(mcbSegment, MCB_PID), (short) 0);
        mergeFreeBlocks();
        cpu.getReg().flags.setCarry(false);
    }

    /**
     * INT 21h/4Ah — Resize an allocated block. ES = data segment (MCB+1), BX = new size in paragraphs.
     * On failure sets carry, AX=0x08, BX=max available in the block (for grow failures).
     */
    public void resizeMemory(CPU cpu, short dataSeg, short newSize) {
        if (!initialized) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INVALID_FUNCTION);
            return;
        }

        short mcbSeg = (short) (dataSeg - 1);
        if (!isMCBInChain(mcbSeg)) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) ERROR_INVALID_MEMORY_BLOCK_ADDRESS);
            return;
        }

        int newSizeU = newSize & 0xFFFF;
        byte marker = cpu.getMemory().readByte(new SegOfs(mcbSeg, MCB_MARKER));
        int currentSize = cpu.getMemory().readWord(new SegOfs(mcbSeg, MCB_SIZE)) & 0xFFFF;

        if (newSizeU == currentSize) {
            cpu.getReg().flags.setCarry(false);
            return;
        }

        if (newSizeU < currentSize) {
            // Shrink: split off the tail as a new free block
            short tailMCB = (short) (mcbSeg + newSizeU + 1);
            int tailSize = currentSize - newSizeU - 1;

            cpu.getMemory().writeWord(new SegOfs(mcbSeg, MCB_SIZE), newSize);
            cpu.getMemory().writeByte(new SegOfs(mcbSeg, MCB_MARKER), MCB_MIDDLE);

            writeMCB(tailMCB, marker, (short) 0, (short) tailSize);
            mergeFreeBlocks();
            cpu.getReg().flags.setCarry(false);
            return;
        }

        // Grow: check if the immediately following block is free and large enough
        int maxSizeAvailable = currentSize;
        if (marker != MCB_LAST) {
            short nextMCB = (short) (mcbSeg + currentSize + 1);
            short nextPid = cpu.getMemory().readWord(new SegOfs(nextMCB, MCB_PID));
            if (nextPid == 0) {
                int nextSize = cpu.getMemory().readWord(new SegOfs(nextMCB, MCB_SIZE)) & 0xFFFF;
                maxSizeAvailable = currentSize + 1 + nextSize; // current data + next MCB + next data
                if (newSizeU <= maxSizeAvailable) {
                    byte nextMarker = cpu.getMemory().readByte(new SegOfs(nextMCB, MCB_MARKER));
                    // Absorb the free block and possibly re-split
                    cpu.getMemory().writeWord(new SegOfs(mcbSeg, MCB_SIZE), (short) maxSizeAvailable);
                    cpu.getMemory().writeByte(new SegOfs(mcbSeg, MCB_MARKER), nextMarker);
                    if (newSizeU < maxSizeAvailable) {
                        // Re-split: leave a free block after the grown allocation
                        resizeMemory(cpu, dataSeg, newSize);
                        return;
                    }
                    cpu.getReg().flags.setCarry(false);
                    return;
                }
            }
        } else {
            // It's the last block, it can grow up to 0xA000
            maxSizeAvailable = 0xA000 - (mcbSeg & 0xFFFF) - 1;
        }

        // Cannot grow
        cpu.getReg().flags.setCarry(true);
        cpu.getReg().AX.setValue((byte) ERROR_INSUFFICIENT_MEMORY);
        cpu.getReg().BX.setValue((short) maxSizeAvailable);
    }

    private boolean isMCBInChain(short targetMCB) {
        short seg = firstMCBSegment;
        while (true) {
            if (seg == targetMCB) return true;
            byte marker = cpu.getMemory().readByte(new SegOfs(seg, MCB_MARKER));
            if (marker == MCB_LAST) return false;
            short blockSize = cpu.getMemory().readWord(new SegOfs(seg, MCB_SIZE));
            int next = (seg & 0xFFFF) + (blockSize & 0xFFFF) + 1;
            if (next >= 0x10000) return false;
            seg = (short) next;
        }
    }
    
    private void mergeFreeBlocks() {
        short currentSegment = firstMCBSegment;

        while (true) {
            byte marker = cpu.getMemory().readByte(new SegOfs(currentSegment, MCB_MARKER));
            if (marker == MCB_LAST) break;

            short pid = cpu.getMemory().readWord(new SegOfs(currentSegment, MCB_PID));
            short blockSize = cpu.getMemory().readWord(new SegOfs(currentSegment, MCB_SIZE));
            int nextInt = (currentSegment & 0xFFFF) + (blockSize & 0xFFFF) + 1;
            if (nextInt >= 0xA000) break;
            short nextSegment = (short) nextInt;

            if (pid == 0) {
                short nextPid = cpu.getMemory().readWord(new SegOfs(nextSegment, MCB_PID));
                if (nextPid == 0) {
                    short nextBlockSize = cpu.getMemory().readWord(new SegOfs(nextSegment, MCB_SIZE));
                    byte nextMarker = cpu.getMemory().readByte(new SegOfs(nextSegment, MCB_MARKER));
                    int merged = (blockSize & 0xFFFF) + (nextBlockSize & 0xFFFF) + 1;
                    cpu.getMemory().writeWord(new SegOfs(currentSegment, MCB_SIZE), (short) merged);
                    cpu.getMemory().writeByte(new SegOfs(currentSegment, MCB_MARKER), nextMarker);
                    continue; // re-examine currentSegment — may merge with another free block
                }
            }

            currentSegment = nextSegment;
        }
    }
    
    // Shrinks the program's initial MCB to SS:ceil(SP/16) and creates a free block for
    // the remainder — mirrors real DOS behaviour so INT 21h/48h probes return non-zero BX.
    public void releaseUnusedMemoryAfterLoad(short pspSegment, short ssSegment, short spValue) {
        if (!initialized) return;

        int stackTopParagraph = (ssSegment & 0xFFFF) + (((spValue & 0xFFFF) + 15) >> 4);
        int programParagraphs = stackTopParagraph - (pspSegment & 0xFFFF);
        if (programParagraphs < 1) programParagraphs = 1;

        int currentSize = cpu.getMemory().readWord(new SegOfs(firstMCBSegment, MCB_SIZE)) & 0xFFFF;
        if (programParagraphs >= currentSize) return;

        int mcbSeg = firstMCBSegment & 0xFFFF;
        int tailMCBSeg = mcbSeg + programParagraphs + 1;
        int tailSize = currentSize - programParagraphs - 1;

        cpu.getMemory().writeWord(new SegOfs(firstMCBSegment, MCB_SIZE), (short) programParagraphs);
        cpu.getMemory().writeByte(new SegOfs(firstMCBSegment, MCB_MARKER), MCB_MIDDLE);

        writeMCB((short) tailMCBSeg, MCB_LAST, (short) 0, (short) tailSize);
    }

    public short getFirstMCBSegment() {
        return firstMCBSegment;
    }
    
    /**
     * Check if the memory manager is initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
}
