package nz.co.electricbolt.xt.cpu;

import nz.co.electricbolt.xt.Breakpoint;
import nz.co.electricbolt.xt.Watchpoint;
import nz.co.electricbolt.xt.util.Disassembler;
import java.util.List;
import java.util.ArrayList;

public class CPU {

    final RegSet reg = new RegSet();
    final Memory memory = new Memory(this);
    final ModRegRM modRegRM = new ModRegRM(this);
    final ALU alu = new ALU(reg.flags);
    final BCDInstructions bcd = new BCDInstructions(reg);
    final StringInstructions string = new StringInstructions(this);
    final Group1Instructions group1 = new Group1Instructions(this);
    final Group2Instructions group2 = new Group2Instructions(this);
    final Group3AInstructions group3A = new Group3AInstructions(this);
    final Group3BInstructions group3B = new Group3BInstructions(this);
    final Group4Instructions group4 = new Group4Instructions(this);
    final Group5Instructions group5 = new Group5Instructions(this);
    final CPUDelegate delegate;
    Reg16 segmentOverride;
    boolean repeat;
    Boolean repeatFlag;
    long instructionCount;
    int csBase;  // (CS & 0xFFFF) << 4, kept in sync with reg.CS
    int ssBase;  // (SS & 0xFFFF) << 4, kept in sync with reg.SS

    private long maxInstructions = -1;
    private List<Breakpoint> breakpoints = new ArrayList<>();
    private boolean hasBreakpoints = false;
    private boolean breakpointReached = false;
    private boolean traceMode = false;
    private List<Watchpoint> watchpoints = new ArrayList<>();
    boolean hasWatchpoints = false;
    private boolean watchpointReached = false;
    private final FPU8087 fpu;
    private final Disassembler disassembler;

    public CPU(CPUDelegate delegate) {
        this.delegate = delegate;
        this.disassembler = new Disassembler(this);
        this.fpu = new FPU8087(this);
    }

    public Reg16 getSegmentOverride() {
        return segmentOverride;
    }

    public void setSegmentOverride(final Reg16 segmentOverride) {
        this.segmentOverride = segmentOverride;
    }

    public Memory getMemory() {
        return memory;
    }

    public RegSet getReg() {
        return reg;
    }

    /** Sets CS and updates csBase cache. Use instead of reg.CS.setValue() everywhere. */
    public void setCS(short value) {
        reg.CS.setValue(value);
        csBase = (value & 0xFFFF) << 4;
    }

    /** Sets SS and updates ssBase cache. Use instead of reg.SS.setValue() everywhere. */
    public void setSS(short value) {
        reg.SS.setValue(value);
        ssBase = (value & 0xFFFF) << 4;
    }

    /** Resync csBase/ssBase after external code writes reg.CS or reg.SS directly. */
    public void syncSegmentBases() {
        csBase = (reg.CS.getValue() & 0xFFFF) << 4;
        ssBase = (reg.SS.getValue() & 0xFFFF) << 4;
    }

    public void execute() {
        while (true) {
            if (maxInstructions >= 0 && instructionCount >= maxInstructions) {
                dumpRegistersAndStop("Maximum instructions limit reached: " + maxInstructions);
                return;
            }
            repeat = false;
            repeatFlag = null;
            segmentOverride = null;

            if (checkBreakpoint()) {
                dumpRegistersAndStop("Breakpoint reached at " +
                    String.format("%04X:%04X", reg.CS.getValue(), reg.IP.getValue()));
                return;
            }
            if (traceMode) {
                printTraceLine();
            }
            step();
        }
    }

    public void execute(int maxSteps) {
        while (maxSteps-- > 0) {
            if (maxInstructions >= 0 && instructionCount >= maxInstructions) {
                dumpRegistersAndStop("Maximum instructions limit reached: " + maxInstructions);
                return;
            }
            repeat = false;
            repeatFlag = null;
            segmentOverride = null;
            if (checkBreakpoint()) {
                dumpRegistersAndStop("Breakpoint reached at " +
                    String.format("%04X:%04X", reg.CS.getValue(), reg.IP.getValue()));
                return;
            }
            if (traceMode) {
                printTraceLine();
            }
            step();
        }
    }

    public void printTraceLine() {
        short cs = reg.CS.getValue();
        short ip = reg.IP.getValue();
        SegOfs currentAddr = new SegOfs(cs, ip);
        StringBuilder bytes = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            byte b = memory.fetchByte(currentAddr);
            bytes.append(String.format("%02X", b & 0xFF));
            currentAddr.increment();
        }
        String flagsStr = String.format("%c%c%c%c%c%c%c%c%c",
            reg.flags.isCarry() ? 'C' : '-',
            reg.flags.isParityEven() ? 'P' : '-',
            reg.flags.isAuxiliaryCarry() ? 'A' : '-',
            reg.flags.isZero() ? 'Z' : '-',
            reg.flags.isSignNegative() ? 'S' : '-',
            reg.flags.isTrapEnabled() ? 'T' : '-',
            reg.flags.isInterruptEnabled() ? 'I' : '-',
            reg.flags.isDirectionDown() ? 'D' : '-',
            reg.flags.isOverflow() ? 'O' : '-'
        );
        String disasm = disassembler.disassemble();
        System.out.printf("%04X:%04X | %s | %-24s | AX=%04X,BX=%04X,CX=%04X,DX=%04X,SI=%04X,DI=%04X,BP=%04X,DS=%04X,ES=%04X | SS:SP=%04X:%04X | FLAGS=%s%n",
            cs & 0xFFFF,
            ip & 0xFFFF,
            bytes.toString(),
            disasm,
            reg.AX.getValue() & 0xFFFF,
            reg.BX.getValue() & 0xFFFF,
            reg.CX.getValue() & 0xFFFF,
            reg.DX.getValue() & 0xFFFF,
            reg.SI.getValue() & 0xFFFF,
            reg.DI.getValue() & 0xFFFF,
            reg.BP.getValue() & 0xFFFF,
            reg.DS.getValue() & 0xFFFF,
            reg.ES.getValue() & 0xFFFF,
            reg.SS.getValue() & 0xFFFF,
            reg.SP.getValue() & 0xFFFF,
            flagsStr
        );
    }

    private void step() {
        if ((reg.CS.getValue() & 0xFFFF) == 0xF000 && (reg.IP.getValue() & 0xFFFF) >= 0xFF00 && (reg.IP.getValue() & 0xFFFF) <= 0xFFFF) {
            int interrupt = reg.IP.getValue() & 0xFF;
            delegate.interrupt((byte) interrupt);
            short handlerFlags = reg.flags.getValue16();
            iret();
            reg.flags.setValue16(handlerFlags);
            return;
        }

        instructionCount++;
        byte opcode = fetch8();

        switch (opcode) {
            case (byte) 0x26:
                segmentOverride = reg.ES;
                step();
                break;
            case (byte) 0x2E:
                segmentOverride = reg.CS;
                step();
                break;
            case (byte) 0x36:
                segmentOverride = reg.SS;
                step();
                break;
            case (byte) 0x3E:
                segmentOverride = reg.DS;
                step();
                break;
            case (byte) 0xF2:
                repeat = true;
                repeatFlag = Boolean.FALSE;
                step();
                break;
            case (byte) 0xF3:
                repeat = true;
                repeatFlag = Boolean.TRUE;
                step();
                break;
            case (byte) 0x00:
            case (byte) 0x02:
            case (byte) 0x10:
            case (byte) 0x12: {
                final boolean carry = (opcode == 0x10 || opcode == 0x12) && reg.flags.isCarry();
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.add8(regRM.getMem8().getValue(), regRM.getReg8().getValue(), carry);
                if (opcode == 0x02 || opcode == 0x12) {
                    regRM.getReg8().setValue(result);
                } else {
                    regRM.getMem8().setValue(result);
                }
                break;
            }
            case (byte) 0x04:
            case (byte) 0x14: {
                final boolean carry = opcode == 0x14 && reg.flags.isCarry();
                reg.AL.setValue(alu.add8(fetch8(), reg.AL.getValue(), carry));
                break;
            }
            case (byte) 0x01:
            case (byte) 0x03:
            case (byte) 0x11:
            case (byte) 0x13: {
                final boolean carry = (opcode == 0x11 || opcode == 0x13) && reg.flags.isCarry();
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.add16(regRM.getMem16().getValue(), regRM.getReg16().getValue(), carry);
                if (opcode == 0x03 || opcode == 0x13) {
                    regRM.getReg16().setValue(result);
                } else {
                    regRM.getMem16().setValue(result);
                }
                break;
            }
            case (byte) 0x05:
            case (byte) 0x15: {
                final boolean carry = opcode == 0x15 && reg.flags.isCarry();
                reg.AX.setValue(alu.add16(fetch16(), reg.AX.getValue(), carry));
                break;
            }
            case (byte) 0x06:
                push16(reg.ES.getValue());
                break;
            case (byte) 0x07:
                reg.ES.setValue(pop16());
                break;
            case (byte) 0x0E:
                push16(reg.CS.getValue());
                break;
            case (byte) 0x16:
                push16(reg.SS.getValue());
                break;
            case (byte) 0x17:
                setSS(pop16());
                break;
            case (byte) 0x1E:
                push16(reg.DS.getValue());
                break;
            case (byte) 0x1F:
                reg.DS.setValue(pop16());
                break;
            case (byte) 0x50:
            case (byte) 0x51:
            case (byte) 0x52:
            case (byte) 0x53:
            case (byte) 0x54:
            case (byte) 0x55:
            case (byte) 0x56:
            case (byte) 0x57: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                push16((short) (reg16.getValue() - (opcode == 0x54 ? 2 : 0)));
                break;
            }
            case (byte) 0x58:
            case (byte) 0x59:
            case (byte) 0x5A:
            case (byte) 0x5B:
            case (byte) 0x5C:
            case (byte) 0x5D:
            case (byte) 0x5E:
            case (byte) 0x5F: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                reg16.setValue(pop16());
                break;
            }
            case (byte) 0x8F: {
                final RegRM16 regRM = modRegRM.fetch16();
                regRM.getMem16().setValue(pop16());
                break;
            }
            case (byte) 0x9C:
                push16(reg.flags.getValue16());
                break;
            case (byte) 0x9D: {
                popf();
                break;
            }
            case (byte) 0x9E: {
                reg.flags.setValue8(reg.AH.getValue());
                break;
            }
            case (byte) 0x9F: {
                reg.AH.setValue(reg.flags.getValue8());
                break;
            }
            case (byte) 0xF5:
                reg.flags.setCarry(!reg.flags.isCarry());
                break;
            case (byte) 0xF8:
                reg.flags.setCarry(false);
                break;
            case (byte) 0xF9:
                reg.flags.setCarry(true);
                break;
            case (byte) 0xFA:
                reg.flags.setInterruptEnabled(false);
                break;
            case (byte) 0xFB:
                reg.flags.setInterruptEnabled(true);
                break;
            case (byte) 0xFC:
                reg.flags.setDirectionDown(false);
                break;
            case (byte) 0xFD:
                reg.flags.setDirectionDown(true);
                break;
            case (byte) 0x08: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.or8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getMem8().setValue(result);
                break;
            }
            case (byte) 0x09: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.or16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getMem16().setValue(result);
                break;
            }
            case (byte) 0x0A: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.or8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getReg8().setValue(result);
                break;
            }
            case (byte) 0x0B: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.or16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getReg16().setValue(result);
                break;
            }
            case (byte) 0x0C: {
                reg.AL.setValue(alu.or8(fetch8(), reg.AL.getValue()));
                break;
            }
            case (byte) 0x0D: {
                reg.AX.setValue(alu.or16(fetch16(), reg.AX.getValue()));
                break;
            }
            case (byte) 0x18:
            case (byte) 0x28: {
                final boolean carry = (opcode == 0x18) && reg.flags.isCarry();
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.sub8(regRM.getMem8().getValue(), regRM.getReg8().getValue(), carry);
                regRM.getMem8().setValue(result);
                break;
            }
            case (byte) 0x1A:
            case (byte) 0x2A: {
                final boolean carry = opcode == 0x1A && reg.flags.isCarry();
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.sub8(regRM.getReg8().getValue(), regRM.getMem8().getValue(), carry);
                regRM.getReg8().setValue(result);
                break;
            }
            case (byte) 0x1C:
            case (byte) 0x2C: {
                final boolean carry = opcode == 0x1C && reg.flags.isCarry();
                reg.AL.setValue(alu.sub8(reg.AL.getValue(), fetch8(), carry));
                break;
            }
            case (byte) 0x19:
            case (byte) 0x29: {
                final boolean carry = (opcode == 0x19) && reg.flags.isCarry();
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.sub16(regRM.getMem16().getValue(), regRM.getReg16().getValue(), carry);
                regRM.getMem16().setValue(result);
                break;
            }
            case (byte) 0x1B:
            case (byte) 0x2B: {
                final boolean carry = (opcode == 0x1B) && reg.flags.isCarry();
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.sub16(regRM.getReg16().getValue(), regRM.getMem16().getValue(), carry);
                regRM.getReg16().setValue(result);
                break;
            }
            case (byte) 0x1D:
            case (byte) 0x2D: {
                final boolean carry = opcode == 0x1D && reg.flags.isCarry();
                reg.AX.setValue(alu.sub16(reg.AX.getValue(), fetch16(), carry));
                break;
            }
            case (byte) 0x20: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.and8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getMem8().setValue(result);
                break;
            }
            case (byte) 0x21: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.and16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getMem16().setValue(result);
                break;
            }
            case (byte) 0x22: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.and8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getReg8().setValue(result);
                break;
            }
            case (byte) 0x23: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.and16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getReg16().setValue(result);
                break;
            }
            case (byte) 0x24: {
                reg.AL.setValue(alu.and8(fetch8(), reg.AL.getValue()));
                break;
            }
            case (byte) 0x25: {
                reg.AX.setValue(alu.and16(fetch16(), reg.AX.getValue()));
                break;
            }
            case (byte) 0x27:
                bcd.daa();
                break;
            case (byte) 0x2F:
                bcd.das();
                break;
            case (byte) 0x37:
                bcd.aaa();
                break;
            case (byte) 0x3F:
                bcd.aas();
                break;
            case (byte) 0xD4: {
                final byte base = fetch8();
                if (!bcd.aam(base)) {
                    interrupt((byte) 0);
                }
                break;
            }
            case (byte) 0xD5: {
                final byte base = fetch8();
                bcd.aad(base);
                break;
            }
            case (byte) 0x30: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.xor8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getMem8().setValue(result);
                break;
            }
            case (byte) 0x31: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.xor16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getMem16().setValue(result);
                break;
            }
            case (byte) 0x32: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte result = alu.xor8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                regRM.getReg8().setValue(result);
                break;
            }
            case (byte) 0x33: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short result = alu.xor16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                regRM.getReg16().setValue(result);
                break;
            }
            case (byte) 0x34: {
                reg.AL.setValue(alu.xor8(fetch8(), reg.AL.getValue()));
                break;
            }
            case (byte) 0x35: {
                reg.AX.setValue(alu.xor16(fetch16(), reg.AX.getValue()));
                break;
            }
            case (byte) 0x38: {
                final RegRM8 regRM = modRegRM.fetch8();
                alu.sub8(regRM.getMem8().getValue(), regRM.getReg8().getValue(), false);
                break;
            }
            case (byte) 0x39: {
                final RegRM16 regRM = modRegRM.fetch16();
                alu.sub16(regRM.getMem16().getValue(), regRM.getReg16().getValue(), false);
                break;
            }
            case (byte) 0x3A: {
                final RegRM8 regRM = modRegRM.fetch8();
                alu.sub8(regRM.getReg8().getValue(), regRM.getMem8().getValue(), false);
                break;
            }
            case (byte) 0x3B: {
                final RegRM16 regRM = modRegRM.fetch16();
                alu.sub16(regRM.getReg16().getValue(), regRM.getMem16().getValue(), false);
                break;
            }
            case (byte) 0x3C:
                alu.sub8(reg.AL.getValue(), fetch8(), false);
                break;
            case (byte) 0x3D:
                alu.sub16(reg.AX.getValue(), fetch16(), false);
                break;
            case (byte) 0x40:
            case (byte) 0x41:
            case (byte) 0x42:
            case (byte) 0x43:
            case (byte) 0x44:
            case (byte) 0x45:
            case (byte) 0x46:
            case (byte) 0x47: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                final boolean origCarry = reg.flags.isCarry();
                reg16.setValue(alu.add16(reg16.getValue(), (short) 1, false));
                reg.flags.setCarry(origCarry);
                break;
            }
            case (byte) 0x48:
            case (byte) 0x49:
            case (byte) 0x4A:
            case (byte) 0x4B:
            case (byte) 0x4C:
            case (byte) 0x4D:
            case (byte) 0x4E:
            case (byte) 0x4F: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                final boolean origCarry = reg.flags.isCarry();
                reg16.setValue(alu.sub16(reg16.getValue(), (short) 1, false));
                reg.flags.setCarry(origCarry);
                break;
            }
            case (byte) 0xFE:
                group4.decode();
                break;
            case (byte) 0x70:
                jcc(reg.flags.isOverflow());
                break;
            case (byte) 0x71:
                jcc(reg.flags.isNotOverflow());
                break;
            case (byte) 0x72:
                jcc(reg.flags.isCarry());
                break;
            case (byte) 0x73:
                jcc(reg.flags.isNotCarry());
                break;
            case (byte) 0x74:
                jcc(reg.flags.isZero());
                break;
            case (byte) 0x75:
                jcc(reg.flags.isNotZero());
                break;
            case (byte) 0x76:
                jcc(reg.flags.isCarry() || reg.flags.isZero());
                break;
            case (byte) 0x77:
                jcc(reg.flags.isNotCarry() && reg.flags.isNotZero());
                break;
            case (byte) 0x78:
                jcc(reg.flags.isSignNegative());
                break;
            case (byte) 0x79:
                jcc(reg.flags.isSignPositive());
                break;
            case (byte) 0x7A:
                jcc(reg.flags.isParityEven());
                break;
            case (byte) 0x7B:
                jcc(reg.flags.isParityOdd());
                break;
            case (byte) 0x7C:
                jcc(reg.flags.isSignPositive() != reg.flags.isNotOverflow());
                break;
            case (byte) 0x7D:
                jcc(reg.flags.isSignNegative() == reg.flags.isOverflow());
                break;
            case (byte) 0x7E:
                jcc(reg.flags.isZero() || (reg.flags.isSignNegative() != reg.flags.isOverflow()));
                break;
            case (byte) 0x7F:
                jcc(reg.flags.isNotZero() && (reg.flags.isSignNegative() == reg.flags.isOverflow()));
                break;
            case (byte) 0xE3:
                jcc(reg.CX.getValue() == 0);
                break;
            case (byte) 0x80:
                group1.imm8();
                break;
            case (byte) 0x81:
                group1.imm16(false);
                break;
            case (byte) 0x83:
                group1.imm16(true);
                break;
            case (byte) 0x84: {
                final RegRM8 regRM = modRegRM.fetch8();
                alu.and8(regRM.getMem8().getValue(), regRM.getReg8().getValue());
                break;
            }
            case (byte) 0x85: {
                final RegRM16 regRM = modRegRM.fetch16();
                alu.and16(regRM.getMem16().getValue(), regRM.getReg16().getValue());
                break;
            }
            case (byte) 0xA8: {
                final byte imm8 = fetch8();
                alu.and8(reg.AL.getValue(), imm8);
                break;
            }
            case (byte) 0xA9: {
                final short imm16 = fetch16();
                alu.and16(reg.AX.getValue(), imm16);
                break;
            }
            case (byte) 0x86: {
                final RegRM8 regRM = modRegRM.fetch8();
                final byte temp = regRM.getMem8().getValue();
                regRM.getMem8().setValue(regRM.getReg8().getValue());
                regRM.getReg8().setValue(temp);
                break;
            }
            case (byte) 0x87: {
                final RegRM16 regRM = modRegRM.fetch16();
                final short temp = regRM.getMem16().getValue();
                regRM.getMem16().setValue(regRM.getReg16().getValue());
                regRM.getReg16().setValue(temp);
                break;
            }
            case (byte) 0x90:
            case (byte) 0x91:
            case (byte) 0x92:
            case (byte) 0x93:
            case (byte) 0x94:
            case (byte) 0x95:
            case (byte) 0x96:
            case (byte) 0x97: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                final short temp = reg16.getValue();
                reg16.setValue(reg.AX.getValue());
                reg.AX.setValue(temp);
                break;
            }
            case (byte) 0x88: {
                final RegRM8 regRM = modRegRM.fetch8();
                regRM.getMem8().setValue(regRM.getReg8().getValue());
                break;
            }
            case (byte) 0x89: {
                final RegRM16 regRM = modRegRM.fetch16();
                regRM.getMem16().setValue(regRM.getReg16().getValue());
                break;
            }
            case (byte) 0x8A: {
                final RegRM8 regRM = modRegRM.fetch8();
                regRM.getReg8().setValue(regRM.getMem8().getValue());
                break;
            }
            case (byte) 0x8B: {
                final RegRM16 regRM = modRegRM.fetch16();
                regRM.getReg16().setValue(regRM.getMem16().getValue());
                break;
            }
            case (byte) 0x8C: {
                final RegRM16 regRM = modRegRM.fetch16SReg();
                regRM.getMem16().setValue(regRM.getReg16().getValue());
                break;
            }
            case (byte) 0x8D: {
                final RegRM16 regRM = modRegRM.fetch16();
                regRM.getReg16().setValue(regRM.getMem16().getSegOfs().getOffset());
                break;
            }
            case (byte) 0x8E: {
                final RegRM16 regRM = modRegRM.fetch16SReg();
                regRM.getReg16().setValue(regRM.getMem16().getValue());
                break;
            }
            case (byte) 0xA0: {
                final SegOfs segOfs = modRegRM.fetchSegOfs();
                reg.AL.setValue(memory.readByte(segOfs));
                break;
            }
            case (byte) 0xA1: {
                final SegOfs segOfs = modRegRM.fetchSegOfs();
                reg.AX.setValue(memory.readWord(segOfs));
                break;
            }
            case (byte) 0xA2: {
                final SegOfs segOfs = modRegRM.fetchSegOfs();
                memory.writeByte(segOfs, reg.AL.getValue());
                break;
            }
            case (byte) 0xA3: {
                final SegOfs segOfs = modRegRM.fetchSegOfs();
                memory.writeWord(segOfs, reg.AX.getValue());
                break;
            }
            case (byte) 0xB0:
            case (byte) 0xB1:
            case (byte) 0xB2:
            case (byte) 0xB3:
            case (byte) 0xB4:
            case (byte) 0xB5:
            case (byte) 0xB6:
            case (byte) 0xB7: {
                final Reg8 reg8 = modRegRM.getReg8(opcode & 0x7);
                reg8.setValue(fetch8());
                break;
            }
            case (byte) 0xB8:
            case (byte) 0xB9:
            case (byte) 0xBA:
            case (byte) 0xBB:
            case (byte) 0xBC:
            case (byte) 0xBD:
            case (byte) 0xBE:
            case (byte) 0xBF: {
                final Reg16 reg16 = modRegRM.getReg16(opcode & 0x7);
                reg16.setValue(fetch16());
                break;
            }
            case (byte) 0xC6: {
                final RegRM8 regRM = modRegRM.fetch8();
                regRM.getMem8().setValue(fetch8());
                break;
            }
            case (byte) 0xC7: {
                final RegRM16 regRM = modRegRM.fetch16();
                regRM.getMem16().setValue(fetch16());
                break;
            }
            case (byte) 0xA4:
                string.move8();
                break;
            case (byte) 0xA5:
                string.move16();
                break;
            case (byte) 0xA6:
                string.compare8();
                break;
            case (byte) 0xA7:
                string.compare16();
                break;
            case (byte) 0xAA:
                string.store8();
                break;
            case (byte) 0xAB:
                string.store16();
                break;
            case (byte) 0xAC:
                string.load8();
                break;
            case (byte) 0xAD:
                string.load16();
                break;
            case (byte) 0xAE:
                string.scan8();
                break;
            case (byte) 0xAF:
                string.scan16();
                break;
            case (byte) 0x98: {
                reg.AX.setValue(reg.AL.getValue());
                break;
            }
            case (byte) 0x99: {
                if ((reg.AX.getValue() & 0x8000) == 0x8000) {
                    reg.DX.setValue((short) 0xFFFF);
                } else {
                    reg.DX.setValue((short) 0);
                }
                break;
            }
            case (byte) 0x9A: {
                final short offset = fetch16();
                final short segment = fetch16();
                push16(reg.CS.getValue());
                push16(reg.IP.getValue());
                setCS(segment);
                reg.IP.setValue(offset);
                break;
            }
            case (byte) 0xC2: {
                final short additionalPopBytes = fetch16();
                reg.IP.setValue(pop16());
                reg.SP.add(additionalPopBytes);
                break;
            }
            case (byte) 0xC3: {
                reg.IP.setValue(pop16());
                break;
            }
            case (byte) 0xCA: {
                final short additionalPopBytes = fetch16();
                reg.IP.setValue(pop16());
                setCS(pop16());
                reg.SP.add(additionalPopBytes);
                break;
            }
            case (byte) 0xCB: {
                reg.IP.setValue(pop16());
                setCS(pop16());
                break;
            }
            case (byte) 0xC4:
                loadSeg(reg.ES);
                break;
            case (byte) 0xC5:
                loadSeg(reg.DS);
                break;
            case (byte) 0x9B:
                if (fpu.hasException()) {
                    interrupt((byte) 2);
                    return;
                }
                break;
            case (byte) 0xF0:
                break;
            case (byte) 0xF4:
                delegate.halt();
                break;
            case (byte) 0xCC:
                interrupt((byte) 3);
                return;
            case (byte) 0xCD:
                interrupt(fetch8());
                return;
            case (byte) 0xCE:
                if (reg.flags.isOverflow())
                    interrupt((byte) 4);
                return;
            case (byte) 0xCF:
                iret();
                break;
            case (byte) 0xD0:
                group2.rotate8(1);
                break;
            case (byte) 0xD1:
                group2.rotate16(1);
                break;
            case (byte) 0xD2:
                group2.rotate8(reg.CL.getValue());
                break;
            case (byte) 0xD3:
                group2.rotate16(reg.CL.getValue());
                break;
            case (byte) 0xD7: {
                final SegOfs segOfs = new SegOfs(segmentOverride == null ? reg.DS : segmentOverride, (short) (reg.BX.getValue() + (reg.AL.getValue() & 0xFF)));
                reg.AL.setValue(memory.readByte(segOfs));
                segmentOverride = null;
                break;
            }
            case (byte) 0xE0: {
                loop(reg.flags.isNotZero());
                break;
            }
            case (byte) 0xE1: {
                loop(reg.flags.isZero());
                break;
            }
            case (byte) 0xE2: {
                loop(true);
                break;
            }
            case (byte) 0xE4: {
                final byte address = fetch8();
                reg.AL.setValue(delegate.portRead8(address));
                break;
            }
            case (byte) 0xE5: {
                final short address = fetch8();
                reg.AX.setValue(delegate.portRead16(address));
                break;
            }
            case (byte) 0xE6: {
                final short address = fetch8();
                delegate.portWrite8(address, reg.AL.getValue());
                break;
            }
            case (byte) 0xE7: {
                final short address = fetch8();
                delegate.portWrite16(address, reg.AX.getValue());
                break;
            }
            case (byte) 0xEC: {
                reg.AL.setValue(delegate.portRead8(reg.DX.getValue()));
                break;
            }
            case (byte) 0xED: {
                reg.AX.setValue(delegate.portRead16(reg.DX.getValue()));
                break;
            }
            case (byte) 0xEE: {
                delegate.portWrite8(reg.DX.getValue(), reg.AL.getValue());
                break;
            }
            case (byte) 0xEF: {
                delegate.portWrite16(reg.DX.getValue(), reg.AX.getValue());
                break;
            }
            case (byte) 0xE8: {
                final short offset = fetch16();
                push16(reg.IP.getValue());
                reg.IP.setValue((short) (reg.IP.getValue() + offset));
                break;
            }
            case (byte) 0xE9: {
                final short offset = fetch16();
                reg.IP.setValue((short) (reg.IP.getValue() + offset));
                break;
            }
            case (byte) 0xEA: {
                final short ip = fetch16();
                final short cs = fetch16();
                reg.IP.setValue(ip);
                setCS(cs);
                break;
            }
            case (byte) 0xEB: {
                final byte offset = fetch8();
                reg.IP.setValue((short) (reg.IP.getValue() + offset));
                break;
            }
            case (byte) 0xF6:
                group3A.decode();
                break;
            case (byte) 0xF7:
                group3B.decode();
                break;
            case (byte) 0xFF:
                group5.decode();
                break;
            case (byte) 0xD8:
            case (byte) 0xD9:
            case (byte) 0xDA:
            case (byte) 0xDB:
            case (byte) 0xDC:
            case (byte) 0xDD:
            case (byte) 0xDE:
            case (byte) 0xDF:
                byte modRMByte = memory.buf[(csBase + (reg.IP.getValue() & 0xFFFF)) & 0xFFFFF];
                reg.IP.add((short) 1);
                fpu.execute(opcode & 0xFF, modRMByte & 0xFF);
                break;
            default:
                delegate.invalidOpcode("Invalid opcode");
        }
    }

    byte fetch8() {
        int addr = (csBase + (reg.IP.getValue() & 0xFFFF)) & 0xFFFFF;
        final byte result = memory.buf[addr];
        delegate.fetched8(result, instructionCount);
        reg.IP.add((short) 1);
        return result;
    }

    short fetch16() {
        int ip = reg.IP.getValue() & 0xFFFF;
        int addrLo = (csBase + ip) & 0xFFFFF;
        int addrHi = (csBase + ((ip + 1) & 0xFFFF)) & 0xFFFFF;
        final short result = (short) ((memory.buf[addrHi] & 0xFF) << 8 | memory.buf[addrLo] & 0xFF);
        delegate.fetched16(result, instructionCount);
        reg.IP.add((short) 2);
        return result;
    }

    void loop(boolean flag) {
        final byte relOfs = fetch8();
        reg.CX.add((short) -1);
        if (reg.CX.getValue() != 0 && flag) {
            reg.IP.add(relOfs);
        }
    }

    void popf() {
        reg.flags.setValue16(pop16());
    }

    public void iret() {
        reg.IP.setValue(pop16());
        setCS(pop16());
        popf();
    }

    public void interrupt(byte interrupt) {
        push16(reg.flags.getValue16());
        push16(reg.CS.getValue());
        push16(reg.IP.getValue());

        reg.flags.setTrapEnabled(false);
        reg.flags.setInterruptEnabled(false);
        int ivtBase = 4 * (interrupt & 0xFF);
        setCS(memory.getWord(new SegOfs((short) 0, (short) (ivtBase + 2))));
        reg.IP.setValue(memory.getWord(new SegOfs((short) 0, (short) ivtBase)));
    }

    void loadSeg(Reg16 segReg) {
        final RegRM16 regRM = modRegRM.fetch16();
        SegOfs segOfs = regRM.getMem16().getSegOfs();
        regRM.getReg16().setValue(memory.readWord(segOfs));
        segOfs.addOffset((short) 2);
        segReg.setValue(memory.readWord(segOfs));
    }

    void push16(short value) {
        reg.SP.add((short) -2);
        int sp = reg.SP.getValue() & 0xFFFF;
        int addrLo = (ssBase + sp) & 0xFFFFF;
        int addrHi = (ssBase + ((sp + 1) & 0xFFFF)) & 0xFFFFF;
        memory.buf[addrLo] = (byte) value;
        memory.buf[addrHi] = (byte) (value >> 8);
    }

    public short pop16() {
        int sp = reg.SP.getValue() & 0xFFFF;
        int addrLo = (ssBase + sp) & 0xFFFFF;
        int addrHi = (ssBase + ((sp + 1) & 0xFFFF)) & 0xFFFFF;
        final short result = (short) ((memory.buf[addrHi] & 0xFF) << 8 | memory.buf[addrLo] & 0xFF);
        reg.SP.add((short) 2);
        return result;
    }

    void jcc(boolean flag) {
        final byte offset = fetch8();
        if (flag) {
            reg.IP.setValue((short) (reg.IP.getValue() + offset));
        }
    }

    private boolean checkBreakpoint() {
        if (!hasBreakpoints) return false;
        short currentCS = reg.CS.getValue();
        short currentIP = reg.IP.getValue();
        for (Breakpoint bp : breakpoints) {
            if (!bp.isHit() && bp.getSegment() == currentCS && bp.getOffset() == currentIP) {
                if (bp.checkCondition(this)) {
                    bp.setHit(true);
                    breakpointReached = true;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return reg.toString();
    }

    public void setMaxInstructions(long max) {
        this.maxInstructions = max;
    }

    public void setBreakpoints(List<Breakpoint> breakpoints) {
        this.breakpoints = breakpoints;
        this.hasBreakpoints = !breakpoints.isEmpty();
    }

    public boolean isBreakpointReached() {
        return breakpointReached;
    }

    private void dumpRegistersAndStop(String reason) {
        short cs = reg.CS.getValue();
        short ip = reg.IP.getValue();
        short ss = reg.SS.getValue();
        short sp = reg.SP.getValue();
        short bp = reg.BP.getValue();
        short ds = reg.DS.getValue();
        short es = reg.ES.getValue();
        System.out.println("\n*** EXECUTION STOPPED ***");
        System.out.println("Reason: " + reason);
        System.out.println("Instructions executed: " + instructionCount);
        System.out.println("\nRegister dump:");

        String flagsStr = String.format("%c%c%c%c%c%c%c%c%c",
            reg.flags.isCarry() ? 'C' : '-',
            reg.flags.isParityEven() ? 'P' : '-',
            reg.flags.isAuxiliaryCarry() ? 'A' : '-',
            reg.flags.isZero() ? 'Z' : '-',
            reg.flags.isSignNegative() ? 'S' : '-',
            reg.flags.isTrapEnabled() ? 'T' : '-',
            reg.flags.isInterruptEnabled() ? 'I' : '-',
            reg.flags.isDirectionDown() ? 'D' : '-',
            reg.flags.isOverflow() ? 'O' : '-'
        );
        System.out.printf("CS:%04X,IP:%04X | AX=%04X,BX=%04X,CX=%04X,DX=%04X,SI=%04X,DI=%04X,BP=%04X,DS=%04X,ES=%04X | SS:SP=%04X:%04X | FLAGS=%s%n",
            cs & 0xFFFF,
            ip & 0xFFFF,
            reg.AX.getValue() & 0xFFFF,
            reg.BX.getValue() & 0xFFFF,
            reg.CX.getValue() & 0xFFFF,
            reg.DX.getValue() & 0xFFFF,
            reg.SI.getValue() & 0xFFFF,
            reg.DI.getValue() & 0xFFFF,
            reg.BP.getValue() & 0xFFFF,
            reg.DS.getValue() & 0xFFFF,
            reg.ES.getValue() & 0xFFFF,
            reg.SS.getValue() & 0xFFFF,
            reg.SP.getValue() & 0xFFFF,
            flagsStr
        );

        System.out.println();
        System.out.println("\nStack dump (SS:SP, 32 words):");
        dumpStackWords(ss, sp, 32);
        System.out.println("\nStack frame dump (SS:BP, -64 to +64 words):");
        dumpStackFrameRelative(ss, bp, -64, 64);

        System.exit(0);
    }

    private void dumpStackWords(short segment, short address, int count) {
        for (int i = 0; i < count; i++) {
            short offset = (short) (address + (i * 2));
            SegOfs segOfs = new SegOfs(segment, offset);
            short value = memory.readWord(segOfs);
            System.out.printf("%04X:%04X: %04X", segment & 0xFFFF, offset & 0xFFFF, value & 0xFFFF);
            if (offset == address) {
                System.out.print(" <- SP");
            }
            System.out.println();
        }
    }

    private void dumpStackFrameRelative(short segment, short baseAddress, int startOffset, int endOffset) {
        System.out.printf("SS:BP = %04X:%04X%n", segment & 0xFFFF, baseAddress & 0xFFFF);
        System.out.println("Offset  Address    Value");
        System.out.println("------  ---------  -----");
        for (int offsetWords = startOffset; offsetWords <= endOffset; offsetWords++) {
            short offset = (short) (baseAddress + (offsetWords * 2));
            SegOfs segOfs = new SegOfs(segment, offset);
            short value = memory.readWord(segOfs);
            String offsetStr;
            if (offsetWords < 0) {
                offsetStr = String.format("BP-%04X", -offsetWords * 2);
            } else if (offsetWords > 0) {
                offsetStr = String.format("BP+%04X", offsetWords * 2);
            } else {
                offsetStr = "BP";
            }
            System.out.printf("%-6s  %04X:%04X  %04X",
                offsetStr,
                segment & 0xFFFF,
                offset & 0xFFFF,
                value & 0xFFFF);
            if (offsetWords == 0) {
                System.out.print(" <- BP");
            } else if (offsetWords == 2) {
                System.out.print(" <- Return IP addr (near call)");
            } else if (offsetWords == 4 && startOffset <= 4 && endOffset >= 4) {
                System.out.print(" <- Possible saved BP or parameter");
            }
            System.out.println();
        }
    }

    private void checkWatchpoint(SegOfs address, Watchpoint.Type accessType) {
        if (!hasWatchpoints) return;
        for (Watchpoint wp : watchpoints) {
            if (!wp.isHit() && wp.check(this, accessType, address)) {
                wp.setHit(true);
                watchpointReached = true;
                dumpRegistersAndStop(String.format("Watchpoint reached at %s (%s access)",
                    address.toString(), accessType.toString().toLowerCase()));
                return;
            }
        }
    }

    public void setTraceMode(boolean traceMode) {
        this.traceMode = traceMode;
    }

    public void setWatchpoints(List<Watchpoint> watchpoints) {
        this.watchpoints = watchpoints;
        this.hasWatchpoints = !watchpoints.isEmpty();
    }

    public boolean isWatchpointReached() {
        return watchpointReached;
    }

    public void checkMemoryReadWatchpoint(SegOfs address) {
        checkWatchpoint(address, Watchpoint.Type.READ);
    }

    public void checkMemoryWriteWatchpoint(SegOfs address) {
        checkWatchpoint(address, Watchpoint.Type.WRITE);
    }
}
