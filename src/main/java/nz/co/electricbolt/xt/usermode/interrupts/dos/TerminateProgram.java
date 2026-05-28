package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.RegSet;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.AL;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.Interrupt;

import java.util.Stack;

public class TerminateProgram {

    private static final Stack<SavedContext> contextStack = new Stack<>();
    private static short currentPSP = 0x0090;
    private static byte lastExitCode = 0;
    private static byte lastExitType = 0;

    public static short getCurrentPSP() {
        return currentPSP;
    }

    public static void setCurrentPSP(short segment) {
        currentPSP = segment;
    }

    public static void pushContext(CPU cpu) {
        SavedContext ctx = new SavedContext();
        ctx.regs = cpu.getReg().clone();
        ctx.psp = new byte[256];
        ctx.savedPSP = currentPSP;
        for (int i = 0; i < 256; i++) {
            ctx.psp[i] = cpu.getMemory().readByte(new SegOfs(currentPSP, (short) i));
        }
        ctx.ss = cpu.getReg().SS.getValue();
        ctx.sp = cpu.getReg().SP.getValue();
        contextStack.push(ctx);
    }

    public static void popContext(CPU cpu) {
        SavedContext ctx = contextStack.pop();
        currentPSP = ctx.savedPSP;
        cpu.getReg().setFrom(ctx.regs);
        cpu.getReg().SS.setValue(ctx.ss);
        cpu.getReg().SP.setValue(ctx.sp);
        cpu.syncSegmentBases();
        for (int i = 0; i < 256; i++) {
            cpu.getMemory().writeByte(new SegOfs(currentPSP, (short) i), ctx.psp[i]);
        }
        if (ctx.childSegment != 0) {
            freeMemory(cpu, ctx.childSegment);
        }
    }

    public static void setChildSegment(short segment) {
        if (!contextStack.isEmpty()) {
            contextStack.peek().childSegment = segment;
        }
    }

    public static boolean hasParent() {
        return !contextStack.isEmpty();
    }

    private static void freeMemory(CPU cpu, short segment) {
        cpu.getReg().ES.setValue(segment);
        Memory.freeAllocatedMemoryBlockStatic(cpu, cpu.getReg().ES.getValue());
    }

    @Interrupt(interrupt = 0x20, function = 0x00, description = "Terminate program")
    public void terminate1(final CPU cpu) {
        lastExitCode = 0;
        lastExitType = 0;
        if (hasParent()) {
            popContext(cpu);
        } else {
            System.exit(0);
        }
    }

    @Interrupt(function = 0x00, description = "Terminate program")
    public void terminate2(final CPU cpu) {
        lastExitCode = 0;
        lastExitType = 0;
        if (hasParent()) {
            popContext(cpu);
        } else {
            System.exit(0);
        }
    }

    @Interrupt(function = 0x4C, description = "Terminate program with exit code")
    public void terminate3(final CPU cpu, final @AL byte exitCode) {
        lastExitCode = exitCode;
        lastExitType = 0;
        if (hasParent()) {
            popContext(cpu);
        } else {
            System.exit(cpu.getReg().AL.getValue());
        }
    }

    @Interrupt(function = 0x4D, description = "Get return code of subprocess")
    public void getReturnCode(final CPU cpu) {
        cpu.getReg().AL.setValue(lastExitCode);
        cpu.getReg().AH.setValue(lastExitType);
        lastExitCode = 0;
        lastExitType = 0;
    }

    private static class SavedContext {
        RegSet regs;
        byte[] psp;
        short ss;
        short sp;
        short childSegment;
        short savedPSP;
    }
}
