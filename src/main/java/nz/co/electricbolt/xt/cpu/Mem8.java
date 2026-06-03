package nz.co.electricbolt.xt.cpu;

public class Mem8 {

    private SegOfs segOfs;
    private Reg8 reg;
    private Memory memory;

    Mem8(final SegOfs segOfs, final Memory memory) {
        this.segOfs = segOfs;
        this.memory = memory;
        this.reg = null;
    }

    Mem8(final Reg8 reg) {
        this.segOfs = null;
        this.memory = null;
        this.reg = reg;
    }

    void setMem(final SegOfs segOfs, final Memory memory) {
        this.segOfs = segOfs;
        this.memory = memory;
        this.reg = null;
    }

    void setReg(final Reg8 reg) {
        this.reg = reg;
        this.segOfs = null;
        this.memory = null;
    }

    public byte getValue() {
        if (reg != null) {
            return reg.getValue();
        } else {
            return memory.readByte(segOfs);
        }
    }

    public void setValue(final byte value) {
        if (reg != null) {
            reg.setValue(value);
        } else {
            memory.writeByte(segOfs, value);
        }
    }
}
