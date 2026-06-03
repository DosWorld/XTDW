package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.ProgramLoader;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.Trace;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Exec {

    @Interrupt(interrupt = 0x21, function = 0x4B, subfunction = 0x00, description = "EXEC: Load and execute program")
    public void exec(CPU cpu, Trace trace, DirectoryTranslation dirTrans,
                     @ASCIZ @DS @DX String filename, @ES @BX SegOfs parameterBlock) {
        filename = dirTrans.emulatedPathToHostPath(filename.toUpperCase());
        File file = new File(filename);
        if (!file.exists() || !file.isFile()) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((short) 0x0002);
            return;
        }

        short envSegment = cpu.getMemory().readWord(parameterBlock);
        SegOfs cmdLinePtr = new SegOfs(
            cpu.getMemory().readWord(new SegOfs(parameterBlock.getSegment(), (short)(parameterBlock.getOffset() + 4))),
            cpu.getMemory().readWord(new SegOfs(parameterBlock.getSegment(), (short)(parameterBlock.getOffset() + 2)))
        );
        int cmdLen = cpu.getMemory().readByte(cmdLinePtr) & 0xFF;
        byte[] cmdBuf = new byte[cmdLen];
        for (int i = 0; i < cmdLen; i++) {
            cmdBuf[i] = cpu.getMemory().readByte(new SegOfs(cmdLinePtr.getSegment(), (short)(cmdLinePtr.getOffset() + 1 + i)));
        }
        String commandLine = new String(cmdBuf).trim();

        // Minimum paragraphs the child needs: PSP (0x10) + image size + min_alloc
        // (or, for COM, PSP + 0x10 paragraphs of stack space at minimum).
        int minParagraphs = computeMinimumParagraphs(file);
        if (minParagraphs < 0) {
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((short) 0x000B); // invalid format
            return;
        }

        TerminateProgram.pushContext(cpu);

        short parentPSP = TerminateProgram.getCurrentPSP();
        short parentEnv = cpu.getMemory().readWord(new SegOfs(parentPSP, (short) 0x002C));

        // Real DOS gives the child the largest free block. Probe it with
        // INT 21h/AH=48h BX=FFFFh (always returns CF=1, BX=largest), then
        // allocate that size. The MCB owner is set from currentPSP at the
        // moment of allocation, so we need to know childPSP first — but
        // allocation itself determines childPSP. Resolution: allocate with
        // parent ownership, then patch the MCB owner to childPSP below.
        short[] allocatedSegment = new short[1];
        short[] allocatedParagraphs = new short[1];
        cpu.getReg().BX.setValue((short) 0xFFFF);
        Memory.allocateMemoryBlockStatic(cpu, cpu.getReg().BX.getValue());
        // Probe always sets carry; BX holds the largest free block size.
        int largest = cpu.getReg().BX.getValue() & 0xFFFF;
        if (largest < minParagraphs) {
            TerminateProgram.popContext(cpu);
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((short) 0x0008); // insufficient memory
            cpu.getReg().BX.setValue((short) largest);
            return;
        }
        allocatedParagraphs[0] = (short) largest;
        allocateMemory(cpu, allocatedParagraphs[0], allocatedSegment);
        if (cpu.getReg().flags.isCarry()) {
            TerminateProgram.popContext(cpu);
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((short) 0x0008); // insufficient memory
            return;
        }

        short childPSP = allocatedSegment[0];

        // Patch MCB owner so the child owns its own block (allocateMemory
        // recorded parent's PSP because currentPSP hadn't switched yet).
        cpu.getMemory().writeWord(new SegOfs((short) (childPSP - 1), (short) 0x0001), childPSP);

        for (int i = 0; i < 256; i++) {
            cpu.getMemory().writeByte(new SegOfs(childPSP, (short) i), (byte) 0);
        }
        short topOfMemory = (short) (childPSP + allocatedParagraphs[0]);
        cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x002C), envSegment != 0 ? envSegment : parentEnv);
        cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x0002), topOfMemory);
        cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x0016), parentPSP);
        cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x0006), (short) 0xFFFF);
        cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x0008), (short) 0xFFFF);

        SegOfs cmdLine = new SegOfs(childPSP, (short) 0x0080);
        int cmdOutLen = Math.min(commandLine.length(), 126); // PSP:0080 can hold at most 126 chars + len + CR
        cpu.getMemory().writeByte(cmdLine, (byte) cmdOutLen);
        for (int i = 0; i < cmdOutLen; i++) {
            cpu.getMemory().writeByte(new SegOfs(cmdLine.getSegment(), (short)(cmdLine.getOffset() + 1 + i)), (byte) commandLine.charAt(i));
        }
        cpu.getMemory().writeByte(new SegOfs(cmdLine.getSegment(), (short)(cmdLine.getOffset() + 1 + cmdOutLen)), (byte) '\r');

        // Switch currentPSP before loading so allocator activity during load
        // attributes ownership to the child, and so an abort during load can
        // be cleaned up via popContext.
        TerminateProgram.setChildSegment(childPSP);
        TerminateProgram.setCurrentPSP(childPSP);

        ProgramLoader loader = new ProgramLoader(cpu);
        try {
            loader.load(filename, childPSP);
        } catch (RuntimeException e) {
            TerminateProgram.popContext(cpu);
            cpu.getReg().flags.setCarry(true);
            cpu.getReg().AX.setValue((short) 0x000B); // invalid format
            return;
        }

        // Shrink the child's block to (image + stack) and release the tail as a
        // free block, mirroring what ProgramRunner does for the top-level program.
        // Without this, a child that probes free memory with INT 21h/AH=48 BX=FFFFh
        // before calling AH=4Ah sees no free blocks and gets BX=0.
        int stackTopParagraph = (cpu.getReg().SS.getValue() & 0xFFFF)
            + (((cpu.getReg().SP.getValue() & 0xFFFF) + 15) >> 4);
        int childParagraphs = stackTopParagraph - (childPSP & 0xFFFF);
        if (childParagraphs < 1) childParagraphs = 1;
        if (childParagraphs < (allocatedParagraphs[0] & 0xFFFF)) {
            Memory.resizeMemoryBlockStatic(cpu, childPSP, (short) childParagraphs);
            cpu.getMemory().writeWord(new SegOfs(childPSP, (short) 0x0002),
                (short) (childPSP + childParagraphs));
        }

        // Push an IRET frame (flags, CS, IP) onto the child's stack so that the
        // iret() in CPU.step() pops the child's entry point correctly.
        cpu.push16(cpu.getReg().flags.getValue16());
        cpu.push16(cpu.getReg().CS.getValue());
        cpu.push16(cpu.getReg().IP.getValue());

        cpu.getReg().flags.setCarry(false);
        cpu.getReg().AX.setValue((short) 0x0000);
    }

    private void allocateMemory(CPU cpu, short paragraphs, short[] result) {
        cpu.getReg().BX.setValue(paragraphs);
        Memory.allocateMemoryBlockStatic(cpu, cpu.getReg().BX.getValue());
        if (!cpu.getReg().flags.isCarry()) {
            result[0] = cpu.getReg().AX.getValue();
        }
    }

    // PSP (0x10 paragraphs) + image + min_alloc for EXE, or 0x1000 paragraphs
    // (full 64 KB segment) for COM so the stack at offset 0xFFFF has room.
    // Returns -1 on read error. Parses only the EXE header fields it needs to
    // avoid taking a cross-package dependency on the loader's EXEHeader.
    private int computeMinimumParagraphs(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[0x1C];
            int n = fis.read(buf);
            if (n < 0x1C || buf[0x00] != 'M' || buf[0x01] != 'Z') {
                return 0x1000;
            }
            int lastBlockSize = word(buf, 0x02);
            int numberOfBlocks = word(buf, 0x04);
            int headerParagraphs = word(buf, 0x08);
            int minAlloc = word(buf, 0x0A);
            int totalFileSize = (lastBlockSize == 0)
                ? numberOfBlocks * 512
                : (numberOfBlocks - 1) * 512 + lastBlockSize;
            int codeParagraphs = ((totalFileSize - headerParagraphs * 16) + 15) >> 4;
            return 0x10 + codeParagraphs + minAlloc;
        } catch (IOException e) {
            return -1;
        }
    }

    private static int word(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }
}
