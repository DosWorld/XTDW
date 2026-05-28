package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.Trace;

public class Memory {
    
    private static DOSMemoryManager memoryManager = null;
    
    public static void initializeMemoryManager(CPU cpu, short pspSegment) {
        memoryManager = new DOSMemoryManager(cpu);
        memoryManager.initialize(pspSegment);
    }
    
    @Interrupt(function = 0x48, description = "Allocate memory block")
    public void allocateMemoryBlock(CPU cpu, @BX short paragraphs) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        
        memoryManager.allocateMemory(cpu, paragraphs);
    }
    
    @Interrupt(function = 0x49, description = "Free allocated memory block")
    public void freeAllocatedMemoryBlock(CPU cpu, @ES short segment) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        
        memoryManager.freeMemory(cpu, segment);
    }
    
    @Interrupt(function = 0x4A, description = "Resize memory block")
    public void resizeMemoryBlock(CPU cpu, @ES short segment, @BX short newSize) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        memoryManager.resizeMemory(cpu, segment, newSize);
    }
    
    public static void allocateMemoryBlockStatic(CPU cpu, short paragraphs) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        memoryManager.allocateMemory(cpu, paragraphs);
    }
    
    public static void freeAllocatedMemoryBlockStatic(CPU cpu, short segment) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        memoryManager.freeMemory(cpu, segment);
    }

    public static void resizeMemoryBlockStatic(CPU cpu, short dataSeg, short newSize) {
        if (memoryManager == null || !memoryManager.isInitialized()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((byte) 0x01);
            return;
        }
        memoryManager.resizeMemory(cpu, dataSeg, newSize);
    }

    public static void releaseUnusedMemoryAfterLoad(short pspSegment, short ssSegment, short spValue) {
        if (memoryManager == null || !memoryManager.isInitialized()) return;
        memoryManager.releaseUnusedMemoryAfterLoad(pspSegment, ssSegment, spValue);
    }

    public static void freeBlocksOwnedByStatic(short ownerPSP) {
        if (memoryManager == null || !memoryManager.isInitialized()) return;
        memoryManager.freeBlocksOwnedBy(ownerPSP);
    }
}