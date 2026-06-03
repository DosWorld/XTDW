package nz.co.electricbolt.xt.cpu;

public class Flags {
    public static final short CARRY = 0x0001;
    public static final short RESERVED1 = 0x0002; // Always one.
    public static final short PARITY = 0x0004;
    public static final short RESERVED2 = 0x0008; // Always zero.
    public static final short AUX_CARRY = 0x0010;
    public static final short RESERVED3 = 0x0020; // Always zero.
    public static final short ZERO = 0x0040;
    public static final short SIGN = 0x0080;
    public static final short TRAP = 0x0100;
    public static final short INTERRUPT_ENABLE = 0x0200;
    public static final short DIRECTION = 0x0400;
    public static final short OVERFLOW = 0x0800;
    public static final short RESERVED4 = 0x1000; // Always one.
    public static final short RESERVED5 = 0x2000; // Always one.
    public static final short RESERVED6 = 0x4000; // Always one.
    public static final short RESERVED7 = (short) 0x8000; // Always one.

    private static final short ALWAYS_ONE_MASK16 = RESERVED7 | RESERVED6 | RESERVED5 | RESERVED4 | RESERVED1;
    private static final short ALWAYS_ZERO_MASK16 = RESERVED3 | RESERVED2;
    private static final short FLAG_MASK16 = OVERFLOW | DIRECTION | INTERRUPT_ENABLE | TRAP | SIGN | ZERO | AUX_CARRY | PARITY | CARRY;

    // Bits cleared by arithmetic flag helpers (all flags except TRAP/INTERRUPT/DIRECTION/OVERFLOW preserved separately)
    private static final int ARITH_MASK = OVERFLOW | SIGN | ZERO | AUX_CARRY | PARITY | CARRY;

    static final boolean[] PARITY_TABLE = new boolean[256];
    static {
        for (int i = 0; i < 256; i++) {
            int p = i ^ (i >> 4);
            p &= 0xF;
            PARITY_TABLE[i] = ((0x6996 >> p) & 1) == 0;
        }
    }

    private short value;

    public Flags() {
        value = ALWAYS_ONE_MASK16;
    }

    public void setValue8(final byte value) {
        short value16 = (short) (getValue16() & (short) 0xFF00);
        value16 |= (short) (value & 0xFF);
        setValue16(value16);
    }

    public byte getValue8() {
        return (byte) (getValue16() & 0x00FF);
    }

    public void setValue16(final short value) {
        this.value = (short) (((value & FLAG_MASK16) | ALWAYS_ONE_MASK16) & ~ALWAYS_ZERO_MASK16);
    }

    public short getValue16() {
        return value;
    }

    public String getName() {
        return "FLAGS";
    }

    public String toString() {
        return ("FLAGS=" +
                (isOverflow() ? "OF " : "") +
                (isDirectionDown() ? "DF " : "") +
                (isInterruptEnabled() ? "IF " : "") +
                (isTrapEnabled() ? "TF " : "") +
                (isSignNegative() ? "SF " : "") +
                (isZero() ? "ZF " : "") +
                (isAuxiliaryCarry() ? "AF " : "") +
                (isParityEven() ? "PF " : "") +
                (isCarry() ? "CF " : "")).trim();
    }

    public boolean equals(final Object obj) {
        if (!(obj instanceof Flags other)) {
            return false;
        }
        return value == other.value;
    }

    public int hashCode() {
        return value;
    }

    public void setCarry(final boolean carry) {
        if (carry) {
            value |= CARRY;
        } else {
            value &= ~CARRY;
        }
    }

    public boolean isCarry() {
        return (value & CARRY) == CARRY;
    }

    public boolean isNotCarry() {
        return (value & CARRY) == 0;
    }

    public void setParityEven(final boolean even) {
        if (even) {
            value |= PARITY;
        } else {
            value &= ~PARITY;
        }
    }

    public boolean isParityEven() {
        return (value & PARITY) == PARITY;
    }

    public boolean isParityOdd() {
        return (value & PARITY) == 0;
    }

    public void setAuxiliaryCarry(final boolean carry) {
        if (carry) {
            value |= AUX_CARRY;
        } else {
            value &= ~AUX_CARRY;
        }
    }
    public boolean isAuxiliaryCarry() {
        return (value & AUX_CARRY) == AUX_CARRY;
    }

    public boolean isNotAuxiliaryCarry() {
        return (value & AUX_CARRY) == 0;
    }

    public void setZero(final boolean zero) {
        if (zero) {
            value |= ZERO;
        } else {
            value &= ~ZERO;
        }
    }

    public boolean isZero() {
        return (value & ZERO) == ZERO;
    }

    public boolean isNotZero() {
        return (value & ZERO) == 0;
    }

    public void setSignNegative(final boolean sign) {
        if (sign) {
            value |= SIGN;
        } else {
            value &= ~SIGN;
        }
    }

    public boolean isSignNegative() {
        return (value & SIGN) == SIGN;
    }

    public boolean isSignPositive() {
        return (value & SIGN) == 0;
    }

    public void setTrapEnabled(final boolean trap) {
        if (trap) {
            value |= TRAP;
        } else {
            value &= ~TRAP;
        }
    }

    public boolean isTrapEnabled() {
        return (value & TRAP) == TRAP;
    }

    public boolean isTrapDisabled() {
        return (value & TRAP) == 0;
    }

    public void setInterruptEnabled(final boolean enabled) {
        if (enabled) {
            value |= INTERRUPT_ENABLE;
        } else {
            value &= ~INTERRUPT_ENABLE;
        }
    }

    public boolean isInterruptEnabled() {
        return (value & INTERRUPT_ENABLE) == INTERRUPT_ENABLE;
    }

    public boolean isInterruptDisabled() {
        return (value & INTERRUPT_ENABLE) == 0;
    }

    public void setDirectionDown(final boolean down) {
        if (down) {
            value |= DIRECTION;
        } else {
            value &= ~DIRECTION;
        }
    }

    public boolean isDirectionDown() {
        return (value & DIRECTION) == DIRECTION;
    }

    public boolean isDirectionUp() {
        return (value & DIRECTION) == 0;
    }

    public void setOverflow(final boolean overflow) {
        if (overflow) {
            value |= OVERFLOW;
        } else {
            value &= ~OVERFLOW;
        }
    }

    public boolean isOverflow() {
        return (value & OVERFLOW) == OVERFLOW;
    }

    public boolean isNotOverflow() {
        return (value & OVERFLOW) == 0;
    }

    // Batch-write all arithmetic flags (CF/PF/AF/ZF/SF/OF) in one store.
    public void setArithFlags8(final int result, final int a, final int b, final int carryIn) {
        int f = value & ~ARITH_MASK;
        if (result > 0xFF)                                    f |= CARRY;
        if ((result & 0x80) != 0)                             f |= SIGN;
        if ((result & 0xFF) == 0)                             f |= ZERO;
        if (((a ^ b ^ result) & 0x10) != 0)                  f |= AUX_CARRY;
        if (PARITY_TABLE[result & 0xFF])                      f |= PARITY;
        if (((a ^ result) & (~(a ^ b)) & 0x80) != 0)         f |= OVERFLOW;
        value = (short) f;
    }

    public void setArithFlags8Sub(final int result, final int a, final int b, final int carryIn) {
        int f = value & ~ARITH_MASK;
        if ((result & 0xFFF) > 0xFF)                          f |= CARRY;
        if ((result & 0x80) != 0)                             f |= SIGN;
        if ((result & 0xFF) == 0)                             f |= ZERO;
        if (((a ^ b ^ result) & 0x10) != 0)                  f |= AUX_CARRY;
        if (PARITY_TABLE[result & 0xFF])                      f |= PARITY;
        if (((a ^ b) & (a ^ result) & 0x80) != 0)            f |= OVERFLOW;
        value = (short) f;
    }

    public void setArithFlags16(final int result, final int a, final int b, final int carryIn) {
        int f = value & ~ARITH_MASK;
        if ((result & 0xFFFFF) > 0xFFFF)                      f |= CARRY;
        if ((result & 0x8000) != 0)                           f |= SIGN;
        if ((result & 0xFFFF) == 0)                           f |= ZERO;
        if (((a ^ b ^ result) & 0x10) != 0)                  f |= AUX_CARRY;
        if (PARITY_TABLE[result & 0xFF])                      f |= PARITY;
        if (((a ^ result) & (~(a ^ b)) & 0x8000) != 0)       f |= OVERFLOW;
        value = (short) f;
    }

    public void setArithFlags16Sub(final int result, final int a, final int b, final int carryIn) {
        int f = value & ~ARITH_MASK;
        if ((result & 0xFFFFF) > 0xFFFF)                      f |= CARRY;
        if ((result & 0x8000) != 0)                           f |= SIGN;
        if ((result & 0xFFFF) == 0)                           f |= ZERO;
        if (((a ^ b ^ result) & 0x10) != 0)                  f |= AUX_CARRY;
        if (PARITY_TABLE[result & 0xFF])                      f |= PARITY;
        if (((a ^ b) & (a ^ result) & 0x8000) != 0)          f |= OVERFLOW;
        value = (short) f;
    }

    public void setLogicFlags8(final int result) {
        int f = value & ~ARITH_MASK;
        if ((result & 0x80) != 0)    f |= SIGN;
        if ((result & 0xFF) == 0)    f |= ZERO;
        if (PARITY_TABLE[result & 0xFF]) f |= PARITY;
        value = (short) f;
    }

    public void setLogicFlags16(final int result) {
        int f = value & ~ARITH_MASK;
        if ((result & 0x8000) != 0)  f |= SIGN;
        if ((result & 0xFFFF) == 0)  f |= ZERO;
        if (PARITY_TABLE[result & 0xFF]) f |= PARITY;
        value = (short) f;
    }
}