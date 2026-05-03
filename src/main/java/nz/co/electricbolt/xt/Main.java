package nz.co.electricbolt.xt;

import nz.co.electricbolt.xt.usermode.ProgramRunner;
import nz.co.electricbolt.xt.usermode.interrupts.Interrupts;
import java.util.List;
import java.util.ArrayList;
import java.io.File;

public class Main {

    private boolean traceCPU = false;
    private boolean traceInterrupt = false;
    private String traceFile = "";
    String emulatedProgramPath = "";
    String emulatedProgramArgs = "";
    String hostWorkingDir = "";
    private final CommandLineParser commandLine;
    private Long maxInstructions = null;
    private final List<Breakpoint> breakpoints = new ArrayList<>();
    private boolean traceMode = false;
    private final List<Watchpoint> watchpoints = new ArrayList<>();
    private final List<DumpRegion> dumpRegions = new ArrayList<>();
    private final List<String> environmentVariables = new ArrayList<>();

    private Main(final CommandLineParser commandLine) {
        this.commandLine = commandLine;
    }

    private void printAppVersion() {
        System.out.println("XT/DW version 1.0.6; Copyright (c) 2026; DosWorld.");
        System.out.println("Copyright (c) 2025; Electric Bolt Limited.");
    }

    private void haltSyntax(final String message) {
        printAppVersion();
        System.out.println("Syntax:        xt help [run|int]");
        System.out.println("               xt run [options] program [program-args]");
        System.out.println("               xt trace [options] program [program-args]");
        System.out.println("               xt int");
        if (!message.isEmpty()) {
            System.out.println();
            System.out.println("error: " + message);
        }
        System.exit(255);
    }

    private void haltSyntaxRun(final String message) {
        printAppVersion();
        System.out.println("Syntax:        xt run [--max=N] [-e KEY=VALUE]... [-c dir] program [program-args]");
        System.out.println("               Run a .EXE or .COM command line MS-DOS app on your host system.");
        System.out.println("--max=N      = Maximum number of instructions to execute before stopping");
        System.out.println("-e KEY=VALUE = Set a DOS environment variable. Can be specified multiple times.");
        System.out.println("--env=KEY=VALUE = Set a DOS environment variable. Can be specified multiple times.");
        System.out.println("-c dir       = The host directory that will be the root of the emulated C: drive");
        System.out.println("               If not specified then the current working directory will be used.");
        System.out.println("program      = The .EXE or .COM command line MS-DOS app you want to run. You can");
        System.out.println("               optionally prefix with emulated path.");
        System.out.println("program-args = Optional arguments for the MS-DOS app, max 127 characters.");
        System.out.println();
        System.out.println("The exit code will be 255 if XT terminates the program due to an error,");
        System.out.println("otherwise the exit code will be the exit code of the MS-DOS app.");
        if (!message.isEmpty()) {
            System.out.println();
            System.out.println("error: " + message);
        }
        System.exit(255);
    }

    private void haltSyntaxTrace(final String message) {
        printAppVersion();
        System.out.println("Syntax:        xt trace [--max=N] [-e KEY=VALUE]... [--bp=SEG:OFS[:COND]]... [--wp=SEG:OFS:type]... [--dump=SEG:OFS:LEN]... program [program-args]");
        System.out.println("               Trace execution of a .EXE or .COM command line MS-DOS app.");
        System.out.println("               For each instruction, displays CS:IP, disassembly, and all register values.");
        System.out.println("--max=N      = Maximum number of instructions to trace before stopping");
        System.out.println("-e KEY=VALUE = Set a DOS environment variable. Can be specified multiple times.");
        System.out.println("--env=KEY=VALUE = Set a DOS environment variable. Can be specified multiple times.");
        System.out.println("--bp=SEG:OFS = Set a breakpoint at the specified segment:offset (hex)");
        System.out.println("               Can be specified multiple times. Example: --bp=1000:2000");
        System.out.println("--bp=SEG:OFS:COND = Conditional breakpoint. Condition format: REG==VALUE");
        System.out.println("               Supported registers: AX, BX, CX, DX, SI, DI, BP, SP, DS, ES, SS, FLAGS.CARRY, FLAGS.ZERO, FLAGS.OVERFLOW");
        System.out.println("               Example: --bp=1000:2000:AX==1234   (stops when AX=0x1234)");
        System.out.println("               Example: --bp=1000:2000:FLAGS.ZERO==1   (stops when ZF=1)");
        System.out.println("--wp=SEG:OFS:type = Set a watchpoint at the specified segment:offset (hex)");
        System.out.println("               type can be: r (read), w (write), a (access)");
        System.out.println("               Can be specified multiple times. Example: --wp=1000:2000:w");
        System.out.println("--dump=SEG:OFS:LEN = Dump memory region at program stop (hex). Can be multiple.");
        System.out.println("               Example: --dump=1000:2000:32");
        System.out.println("program      = The .EXE or .COM command line MS-DOS app you want to trace.");
        System.out.println("program-args = Optional arguments for the MS-DOS app.");
        System.out.println();
        System.out.println("Memory layout for loaded programs:");
        System.out.println("  PSP (Program Segment Prefix) is always at 0x0090:0x0000 (256 bytes)");
        System.out.println("  For COM files: code starts at 0x0090:0x0100");
        System.out.println("  For EXE files: code starts at 0x00A0:0x0000 (relocatable)");
        System.out.println("  Stack for COM files is in the same segment, for EXE files at 0xF000:0xF000");
        System.out.println("  Breakpoints and watchpoints use these segment:offset addresses.");
        if (!message.isEmpty()) {
            System.out.println();
            System.out.println("error: " + message);
        }
        System.exit(255);
    }
    private void haltSyntaxInt() {
        printAppVersion();
        System.out.println("Syntax:        xt int");
        System.out.println("               Output the list of interrupts that are implemented by this");
        System.out.println("               version of XT for the run command.");
        System.exit(255);
    }

    private void run() {
        if (hostWorkingDir.isEmpty()) {
            hostWorkingDir = System.getProperty("user.dir");
        }
        if (!hostWorkingDir.endsWith(File.separator)) {
            hostWorkingDir += File.separator;
        }
        final ProgramRunner runner = new ProgramRunner(emulatedProgramPath, emulatedProgramArgs, hostWorkingDir,
                traceCPU, traceInterrupt, traceFile, breakpoints, watchpoints, maxInstructions, traceMode, dumpRegions, environmentVariables);
        printSettings();
        runner.loadAndExecute();
    }

    private void printSettings() {
        if (!breakpoints.isEmpty()) {
            System.out.println("Breakpoints set:");
            for (Breakpoint bp : breakpoints) {
                System.out.println("  " + bp);
            }
        }
        if (!watchpoints.isEmpty()) {
            System.out.println("Watchpoints set:");
            for (Watchpoint wp : watchpoints) {
                System.out.println("  " + wp);
            }
        }
        if (!dumpRegions.isEmpty()) {
            System.out.println("Dump regions:");
            for (DumpRegion dr : dumpRegions) {
                System.out.println("  " + dr);
            }
        }
        if (maxInstructions != null) {
            System.out.println("Maximum instructions limit: " + maxInstructions);
        }
    }

    private void parseInt() {
        final Interrupts interrupts = new Interrupts();
        interrupts.printInterrupts();
        System.exit(255);
    }

    private void parseTraceInterrupt() {
        traceInterrupt = true;
        final String argument = commandLine.next();
        if (argument == null) {
            haltSyntaxTrace("expecting tracing host file argument");
        } else if (argument.startsWith("-")) {
            haltSyntaxTrace(argument + " argument not recognized");
        } else {
            traceFile = argument;
        }
    }

    private void parseTraceCPU() {
        traceCPU = true;
        final String argument = commandLine.next();
        if (argument == null) {
            haltSyntaxTrace("expecting -ti or tracing host file argument");
        } else if (argument.equals("-ti")) {
            parseTraceInterrupt();
        } else if (argument.startsWith("-")) {
            haltSyntaxTrace(argument + " argument not recognized");
        } else {
            traceFile = argument;
        }
    }

    private void parseTraceOptions() {
        final String argument = commandLine.next();
        if (argument.equals("-tc")) {
            parseTraceCPU();
        } else if (argument.equals("-ti")) {
            parseTraceInterrupt();
        } else {
            haltSyntaxTrace(argument + " argument not recognized");
        }
    }

    private void parseRootDirectory() {
        commandLine.next();
        String argument = commandLine.next();
        if (argument == null) {
            haltSyntaxRun("expecting host root directory argument");
        } else if (argument.startsWith("-")) {
            haltSyntaxRun("expecting host root directory argument");
        } else {
            hostWorkingDir = argument;
        }
    }

    private boolean parseEnvOption() {
        String argument = commandLine.peek();
        if (argument == null) return false;
        if (argument.startsWith("--env=")) {
            commandLine.next();
            environmentVariables.add(argument.substring(6));
            return true;
        } else if (argument.equals("-e")) {
            commandLine.next();
            String env = commandLine.next();
            if (env == null) {
                if (traceMode) haltSyntaxTrace("expecting environment variable after -e");
                else haltSyntaxRun("expecting environment variable after -e");
            }
            environmentVariables.add(env);
            return true;
        }
        return false;
    }

    private void parseRunOptions() {
        while (commandLine.hasNext()) {
            String argument = commandLine.peek();
            if (argument == null || !argument.startsWith("--")) {
                break;
            }
            if (argument.startsWith("--max=")) {
                parseMaxOption();
            } else if (argument.startsWith("--env=")) {
                parseEnvOption();
            } else {
                haltSyntaxRun(argument + " option not recognized in run mode");
            }
        }
    }

    private void parseRun() {
        while (commandLine.hasNext()) {
            String argument = commandLine.peek();
            if (argument == null) break;
            if (argument.startsWith("--")) {
                parseRunOptions();
            } else if (argument.equals("-c")) {
                parseRootDirectory();
            } else if (argument.equals("-e")) {
                parseEnvOption();
            } else {
                break;
            }
        }

        if (!commandLine.hasNext()) {
            haltSyntaxRun("expecting program argument");
        }
        String argument = commandLine.next();
        if (argument.startsWith("-")) {
            haltSyntaxRun(argument + " not recognized");
        } else {
            emulatedProgramPath = argument;
        }
        final StringBuilder buf = new StringBuilder();
        while (commandLine.hasNext()) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append(commandLine.next());
        }
        emulatedProgramArgs = buf.toString();
        run();
    }

    private boolean parseWatchpointOption() {
        String argument = commandLine.peek();
        if (argument != null && argument.startsWith("--wp=")) {
            commandLine.next();
            String wpStr = argument.substring(5);
            String[] parts = wpStr.split(":");
            if (parts.length == 3) {
                try {
                    int segment = Integer.parseInt(parts[0], 16);
                    int offset = Integer.parseInt(parts[1], 16);
                    String typeStr = parts[2].toLowerCase();
                    if (segment < 0 || segment > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Segment must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Segment must be between 0 and 0xFFFF");
                        }
                    }
                    if (offset < 0 || offset > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Offset must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Offset must be between 0 and 0xFFFF");
                        }
                    }
                    Watchpoint.Type type;
                    switch (typeStr) {
                        case "r":
                        case "read":
                            type = Watchpoint.Type.READ;
                            break;
                        case "w":
                        case "write":
                            type = Watchpoint.Type.WRITE;
                            break;
                        case "a":
                        case "access":
                            type = Watchpoint.Type.ACCESS;
                            break;
                        default:
                            if (traceMode) {
                                haltSyntaxTrace("Invalid watchpoint type: " + typeStr + ". Use r/w/a");
                            } else {
                                haltSyntaxRun("Invalid watchpoint type: " + typeStr + ". Use r/w/a");
                            }
                            return false;
                    }
                    watchpoints.add(new Watchpoint((short) segment, (short) offset, type));
                    return true;
                } catch (NumberFormatException e) {
                    if (traceMode) {
                        haltSyntaxTrace("Invalid watchpoint format: " + wpStr + ". Expected format: SEG:OFS:type");
                    } else {
                        haltSyntaxRun("Invalid watchpoint format: " + wpStr + ". Expected format: SEG:OFS:type");
                    }
                }
            } else {
                if (traceMode) {
                    haltSyntaxTrace("Invalid watchpoint format: " + wpStr + ". Expected format: SEG:OFS:type (r/w/a)");
                } else {
                    haltSyntaxRun("Invalid watchpoint format: " + wpStr + ". Expected format: SEG:OFS:type (r/w/a)");
                }
            }
        }
        return false;
    }

    private void parseTrace() {
        traceMode = true;
        while (commandLine.hasNext()) {
            String argument = commandLine.peek();
            if (argument == null) break;
            if (argument.startsWith("--")) {
                parseDoubleDashOptions();
            } else if (argument.equals("-e")) {
                parseEnvOption();
            } else if (argument.startsWith("-t")) {
                parseTraceOptions();
            } else {
                break;
            }
        }

        if (!commandLine.hasNext()) {
            haltSyntaxTrace("expecting program argument");
        }
        String argument = commandLine.next();
        if (argument.startsWith("-")) {
            haltSyntaxTrace(argument + " not recognized");
        } else {
            emulatedProgramPath = argument;
        }
        final StringBuilder buf = new StringBuilder();
        while (commandLine.hasNext()) {
            if (!buf.isEmpty()) {
                buf.append(' ');
            }
            buf.append(commandLine.next());
        }
        emulatedProgramArgs = buf.toString();
        run();
    }

    private void parseHelp() {
        if (commandLine.hasNext()) {
            final String argument = commandLine.next();
            switch (argument) {
                case "run" -> haltSyntaxRun("");
                case "trace" -> haltSyntaxTrace("");
                case "int" -> haltSyntaxInt();
                default -> haltSyntax(argument + " not recognized");
            }
        }
        haltSyntax("");
    }

    private boolean parseMaxOption() {
        String argument = commandLine.peek();
        if (argument != null && argument.startsWith("--max=")) {
            commandLine.next();
            try {
                String value = argument.substring(6);
                maxInstructions = Long.parseLong(value);
                if (maxInstructions <= 0) {
                    if (traceMode) {
                        haltSyntaxTrace("--max value must be positive");
                    } else {
                        haltSyntaxRun("--max value must be positive");
                    }
                }
                return true;
            } catch (NumberFormatException e) {
                if (traceMode) {
                    haltSyntaxTrace("Invalid --max value: " + argument.substring(6));
                } else {
                    haltSyntaxRun("Invalid --max value: " + argument.substring(6));
                }
            }
        }
        return false;
    }

    private boolean parseBreakpointOption() {
        String argument = commandLine.peek();
        if (argument != null && argument.startsWith("--bp=")) {
            commandLine.next();
            String bpStr = argument.substring(5);
            String[] parts = bpStr.split(":");
            if (parts.length == 2) {
                try {
                    int segment = Integer.parseInt(parts[0], 16);
                    int offset = Integer.parseInt(parts[1], 16);
                    if (segment < 0 || segment > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Segment must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Segment must be between 0 and 0xFFFF");
                        }
                    }
                    if (offset < 0 || offset > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Offset must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Offset must be between 0 and 0xFFFF");
                        }
                    }
                    breakpoints.add(new Breakpoint((short) segment, (short) offset));
                    return true;
                } catch (NumberFormatException e) {
                    if (traceMode) {
                        haltSyntaxTrace("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET in hex");
                    } else {
                        haltSyntaxRun("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET in hex");
                    }
                }
            } else if (parts.length == 3) {
                try {
                    int segment = Integer.parseInt(parts[0], 16);
                    int offset = Integer.parseInt(parts[1], 16);
                    String condStr = parts[2];
                    if (segment < 0 || segment > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Segment must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Segment must be between 0 and 0xFFFF");
                        }
                    }
                    if (offset < 0 || offset > 0xFFFF) {
                        if (traceMode) {
                            haltSyntaxTrace("Offset must be between 0 and 0xFFFF");
                        } else {
                            haltSyntaxRun("Offset must be between 0 and 0xFFFF");
                        }
                    }
                    breakpoints.add(new Breakpoint((short) segment, (short) offset, condStr));
                    return true;
                } catch (NumberFormatException e) {
                    if (traceMode) {
                        haltSyntaxTrace("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET:COND");
                    } else {
                        haltSyntaxRun("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET:COND");
                    }
                }
            } else {
                if (traceMode) {
                    haltSyntaxTrace("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET or SEG:OFFSET:COND");
                } else {
                    haltSyntaxRun("Invalid breakpoint format: " + bpStr + ". Expected format: SEG:OFFSET or SEG:OFFSET:COND");
                }
            }
        }
        return false;
    }

    private boolean parseDumpOption() {
        String argument = commandLine.peek();
        if (argument != null && argument.startsWith("--dump=")) {
            commandLine.next();
            String dumpStr = argument.substring(7);
            String[] parts = dumpStr.split(":");
            if (parts.length == 3) {
                try {
                    int segment = Integer.parseInt(parts[0], 16);
                    int offset = Integer.parseInt(parts[1], 16);
                    int length = Integer.parseInt(parts[2], 16);
                    if (segment < 0 || segment > 0xFFFF) {
                        haltSyntaxTrace("Segment must be between 0 and 0xFFFF");
                    }
                    if (offset < 0 || offset > 0xFFFF) {
                        haltSyntaxTrace("Offset must be between 0 and 0xFFFF");
                    }
                    if (length < 0 || length > 0xFFFF) {
                        haltSyntaxTrace("Length must be between 0 and 0xFFFF");
                    }
                    dumpRegions.add(new DumpRegion((short) segment, (short) offset, length));
                    return true;
                } catch (NumberFormatException e) {
                    haltSyntaxTrace("Invalid dump format: " + dumpStr + ". Expected format: SEG:OFS:LEN in hex");
                }
            } else {
                haltSyntaxTrace("Invalid dump format: " + dumpStr + ". Expected format: SEG:OFS:LEN");
            }
        }
        return false;
    }

    private void parseDoubleDashOptions() {
        while (commandLine.hasNext()) {
            String argument = commandLine.peek();
            if (argument == null || !argument.startsWith("--")) {
                break;
            }
            if (argument.startsWith("--max=")) {
                parseMaxOption();
            } else if (argument.startsWith("--env=")) {
                parseEnvOption();
            } else if (argument.startsWith("--bp=")) {
                parseBreakpointOption();
            } else if (argument.startsWith("--wp=")) {
                parseWatchpointOption();
            } else if (argument.startsWith("--dump=")) {
                parseDumpOption();
            } else {
                if (traceMode) {
                    haltSyntaxTrace(argument + " option not recognized");
                } else {
                    haltSyntaxRun(argument + " option not recognized");
                }
            }
        }
    }

    private void parse() {
        if (commandLine.hasNext()) {
            final String argument = commandLine.next();
            switch (argument) {
                case "help" -> parseHelp();
                case "int" -> parseInt();
                case "run" -> parseRun();
                case "trace" -> parseTrace();
                default -> haltSyntax(argument + " not recognized");
            }
        }
        haltSyntax("");
    }

    public static void main(String[] args) {
        final Main main = new Main(new CommandLineParser(args));
        main.parse();
    }
}
