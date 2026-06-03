package nz.co.electricbolt.xt.cpu;

public class Mem16 {

    private SegOfs segOfs;
    private Reg16 reg;
    private Memory memory;

    Mem16(final SegOfs segOfs, final Memory memory) {
        this.segOfs = segOfs;
        this.memory = memory;
        this.reg = null;
    }

    Mem16(final Reg16 reg) {
        this.segOfs = null;
        this.memory = null;
        this.reg = reg;
    }

    void setMem(final SegOfs segOfs, final Memory memory) {
        this.segOfs = segOfs;
        this.memory = memory;
        this.reg = null;
    }

    void setReg(final Reg16 reg) {
        this.reg = reg;
        this.segOfs = null;
        this.memory = null;
    }

    public SegOfs getSegOfs() {
        return segOfs;
    }

    public Reg16 getReg() {
        return reg;
    }

    public short getValue() {
        if (reg != null) {
            return reg.getValue();
        } else {
            return memory.readWord(segOfs);
        }
    }

    public void setValue(final short value) {
        if (reg != null) {
            reg.setValue(value);
        } else {
            memory.writeWord(segOfs, value);
        }
    }
}
