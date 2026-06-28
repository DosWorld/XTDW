package nz.co.electricbolt.xt.usermode;

import nz.co.electricbolt.xt.Breakpoint;
import nz.co.electricbolt.xt.Watchpoint;
import nz.co.electricbolt.xt.DumpRegion;
import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.CPUDelegate;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.Interrupts;
import nz.co.electricbolt.xt.usermode.interrupts.dos.FileIO;
import nz.co.electricbolt.xt.usermode.util.DirectoryTranslation;
import nz.co.electricbolt.xt.usermode.util.MemoryUtil;
import nz.co.electricbolt.xt.usermode.util.Trace;
import java.util.List;
import nz.co.electricbolt.xt.cpu.EMS;

public class ProgramRunner implements CPUDelegate {

    private final String programPath;
    private final CPU cpu;
    private final String commandLine;
    private final Interrupts interrupts;
    private final DirectoryTranslation directoryTranslation;
    private final Trace trace;

    private final List<Breakpoint> breakpoints;
    private final List<Watchpoint> watchpoints;
    private final List<DumpRegion> dumpRegions;
    private final Long maxInstructions;
    private final boolean traceMode;
    private final List<String> environmentVariables;
    private final boolean noEMS;

    public ProgramRunner(final String programPath, final String commandLine, final String hostWorkingDirectory,
                         final boolean traceCPU, final boolean traceInterrupt, final String traceFile,
                         final List<Breakpoint> breakpoints, final List<Watchpoint> watchpoints, final Long maxInstructions,
                         final boolean traceMode, List<DumpRegion> dumpRegions, List<String> environmentVariables,
                         final boolean noEMS) {
        directoryTranslation = new DirectoryTranslation(hostWorkingDirectory);
        this.programPath = directoryTranslation.emulatedPathToHostPath(programPath);
        this.commandLine = commandLine;
        this.breakpoints = breakpoints;
        this.watchpoints = watchpoints;
        this.dumpRegions = dumpRegions;
        this.maxInstructions = maxInstructions;
        this.traceMode = traceMode;
        this.environmentVariables = environmentVariables;
        this.noEMS = noEMS;

        this.cpu = new CPU(this);
        this.interrupts = new Interrupts();
        this.trace = new Trace(cpu, traceCPU, traceInterrupt, traceFile);
        this.cpu.fetchTracingEnabled = traceCPU || traceInterrupt;
        this.cpu.setTraceMode(traceMode);

        // In trace mode, print the total number of executed instructions when the
        // program ends. Termination happens through several System.exit() paths
        // (normal DOS terminate, halt, invalid opcode/memory), so a shutdown hook
        // is the one place that reliably fires for all of them.
        if (traceMode) {
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.println("\nInstructions executed: " + cpu.getInstructionCount())));
        }
    }

    public void loadAndExecute() {
        if (!noEMS) {
            EMS.init(16 * 1024);
            cpu.getMemory().setEMS(EMS.getInstance());
        }

        for (int i = 0; i < 256; i++) {
            int linear = 0xF0000 + 0xFF00 + i;
            cpu.getMemory().setLinearByte(linear, (byte) 0xCF);
        }

        // Fake EMS device driver header, read by programs that detect the EMM
        // driver via INT 21h AH=35h (get vector for INT 67h) followed by reading
        // the 8-byte device name at offset 0x0A of the *returned segment*. Real
        // EMM drivers are loaded as device drivers whose header sits at seg:0000,
        // so the canonical detector reads ES:000Ah (offset 0 + 0x0A). We therefore
        // publish the INT 67h vector at offset 0 of segment 0xF000 and lay the
        // header there: bytes 0-2 are a near JMP straight to the real dispatch stub
        // at 0xFF67 (which CPU.step()'s F000:FF00-FFFF check turns into
        // delegate.interrupt()), so the CPU's IVT far-jump for INT 67h still ends
        // up at the reflection dispatch instead of executing the header/name bytes
        // as code. The 8-byte name "EMMXXXX0" lands at offset 0x0A, matching the
        // device-driver-header convention and making ES:000Ah the portable contract.
        final int emmHeaderOffset = 0x0000;
        final int emmDispatchOffset = 0xFF67;
        if (EMS.getInstance() != null) {
            int emmHeaderLinear = 0xF0000 + emmHeaderOffset;
            int jmpRel = (emmDispatchOffset - (emmHeaderOffset + 3)) & 0xFFFF;
            cpu.getMemory().setLinearByte(emmHeaderLinear, (byte) 0xE9);
            cpu.getMemory().setLinearByte(emmHeaderLinear + 1, (byte) (jmpRel & 0xFF));
            cpu.getMemory().setLinearByte(emmHeaderLinear + 2, (byte) ((jmpRel >> 8) & 0xFF));
            byte[] emmName = "EMMXXXX0".getBytes();
            for (int i = 0; i < emmName.length; i++) {
                cpu.getMemory().setLinearByte(emmHeaderLinear + 0x0A + i, emmName[i]);
            }
            cpu.getMemory().setWord(new SegOfs((short) 0, (short) (0x67 * 4)), (short) emmHeaderOffset);
            cpu.getMemory().setWord(new SegOfs((short) 0, (short) (0x67 * 4 + 2)), (short) 0xF000);
        }

        final EnvironmentVariables environment = new EnvironmentVariables(cpu.getMemory(), (short) 0x0050, (short) 0x0000);
        environment.writeVariable("PATH", "C:\\");
        for (String env : environmentVariables) {
            int index = env.indexOf('=');
            if (index != -1) {
                environment.writeVariable(env.substring(0, index), env.substring(index + 1));
            }
        }
        String emulatedPath = directoryTranslation.hostPathToEmulatedPath(programPath);
        environment.writeExecutablePath(FileIO.getFilenameFromPath(emulatedPath));

        final ProgramSegmentPrefix psp = new ProgramSegmentPrefix(cpu.getMemory(), (short) 0x0090, (short) 0x0000);
        psp.writeProgramEnd((short) 0xF000);
        psp.writeEnvironment((short) 0x0050);
        psp.writeCommandLine(commandLine);
        nz.co.electricbolt.xt.usermode.interrupts.dos.Memory.initializeMemoryManager(cpu, (short) 0x0090);

        String filename1 = "";
        String filename2 = "";
        if (!commandLine.isEmpty()) {
            String[] files = commandLine.split(" ");
            if (files.length >= 1) {
                filename1 = FileIO.getFilenameFromPath(files[0]).toUpperCase();
                if (files.length >= 2) {
                    filename2 = FileIO.getFilenameFromPath(files[1]).toUpperCase();
                }
            }
        }
        psp.writeFilename(1, 'C', filename1);
        psp.writeFilename(2, 'C', filename2);

        cpu.getReg().AX.setValue((short) 0x0000);
        cpu.getReg().DS.setValue((short) 0x0090);
        cpu.getReg().ES.setValue((short) 0x0090);

        final ProgramLoader programLoader = new ProgramLoader(cpu);
        try {
            programLoader.load(programPath);
        } catch (RuntimeException e) {
            System.out.println(e.getMessage());
            System.exit(255);
        }
        nz.co.electricbolt.xt.usermode.interrupts.dos.Memory.releaseUnusedMemoryAfterLoad(
            (short) 0x0090, cpu.getReg().SS.getValue(), cpu.getReg().SP.getValue());

        int stubSeg = 0xF000;
        int stubBase = 0xFF00;
        for (int i = 0; i < 256; i++) {
            short off = cpu.getMemory().getWord(new SegOfs((short)0, (short)(i*4)));
            short seg = cpu.getMemory().getWord(new SegOfs((short)0, (short)(i*4+2)));
            if (off == 0 && seg == 0) {
                cpu.getMemory().setWord(new SegOfs((short)0, (short)(i*4)), (short)(stubBase + i));
                cpu.getMemory().setWord(new SegOfs((short)0, (short)(i*4+2)), (short)stubSeg);
            }
        }

        if (breakpoints != null && !breakpoints.isEmpty()) {
            cpu.setBreakpoints(breakpoints);
        }
        if (watchpoints != null && !watchpoints.isEmpty()) {
            cpu.setWatchpoints(watchpoints);
        }
        if (maxInstructions != null) {
            cpu.setMaxInstructions(maxInstructions);
        }
        cpu.execute();
        printDumps();
    }

    @Override
    public void fetched8(final byte value, final long instructionCount) {
        trace.fetched8(value, instructionCount);
    }

    @Override
    public void fetched16(final short value, final long instructionCount) {
        trace.fetched16(value, instructionCount);
    }

    @Override
    public void interrupt(final byte interrupt) {
        interrupts.execute(cpu, interrupt, trace, directoryTranslation);
    }

    @Override
    public void halt() {
        trace.log("CPU halted");
        trace.log(cpu.getReg().toString());
        System.err.println("CPU halted");
        System.err.println(cpu.getReg().toString());
        System.exit(255);
    }

    @Override
    public byte portRead8(final short address) {
        return 0;
    }

    @Override
    public void portWrite8(final short address, byte value) {
    }

    @Override
    public short portRead16(final short address) {
        return 0;
    }

    @Override
    public void portWrite16(final short address, short value) {
    }

    @Override
    public void invalidMemoryAccess(final SegOfs memoryAddress, final byte permissionMask) {
        final String message = String.format("Invalid memory access %s - %s%n",
                cpu.getMemory().fromBitmask(permissionMask), memoryAddress.toString());
        trace.log(message);
        trace.log(cpu.getReg().toString());
        System.err.printf(message);
        System.err.println(cpu.getReg().toString());
        MemoryUtil.dump(cpu.getMemory(), memoryAddress);
        System.exit(255);
    }

    @Override
    public void invalidOpcode(final String message) {
        trace.log(message);
        trace.log(cpu.getReg().toString());
        System.err.println(message);
        System.err.println(cpu.getReg().toString());
        System.exit(255);
    }

    private void printDumps() {
        if (dumpRegions == null) return;
        if (dumpRegions.isEmpty()) return;
        System.out.println("\n=== Memory dumps ===");
        for (DumpRegion dr : dumpRegions) {
            int seg = dr.getSegment() & 0xFFFF;
            int off = dr.getOffset() & 0xFFFF;
            int len = dr.getLength();
            System.out.printf("%04X:%04X (%d bytes):\n", seg, off, len);
            String dump = cpu.getMemory().hexDump((short) seg, (short) off, len);
            System.out.println(dump);
        }
    }
}
