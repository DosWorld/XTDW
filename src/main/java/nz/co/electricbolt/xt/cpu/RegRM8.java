package nz.co.electricbolt.xt.cpu;

public class RegRM8 {

    private Reg8 reg;
    private final Mem8 mem;
    private int regValue;

    public RegRM8(final Reg8 reg, final Reg8 RMReg, final int regValue) {
        this.reg = reg;
        this.mem = new Mem8(RMReg);
        this.regValue = regValue;
    }

    public RegRM8(final Reg8 reg, final SegOfs RMMem, final Memory memory, final int regValue) {
        this.reg = reg;
        this.mem = new Mem8(RMMem, memory);
        this.regValue = regValue;
    }

    void set(final Reg8 reg, final Reg8 RMReg, final int regValue) {
        this.reg = reg;
        this.mem.setReg(RMReg);
        this.regValue = regValue;
    }

    void set(final Reg8 reg, final SegOfs RMMem, final Memory memory, final int regValue) {
        this.reg = reg;
        this.mem.setMem(RMMem, memory);
        this.regValue = regValue;
    }

    public Reg8 getReg8() {
        return reg;
    }

    public Mem8 getMem8() {
        return mem;
    }

    public int getRegValue() {
        return regValue;
    }
}
