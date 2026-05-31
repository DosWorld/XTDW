package nz.co.electricbolt.xt.usermode.interrupts.dos;

import nz.co.electricbolt.xt.cpu.CPU;
import nz.co.electricbolt.xt.cpu.SegOfs;
import nz.co.electricbolt.xt.usermode.interrupts.annotations.*;
import nz.co.electricbolt.xt.usermode.util.MemoryUtil;

import java.io.IOException;

public class ConsoleIO {

    @Interrupt(function = 0x01, description = "Read character from standard input with echo")
    public void readCharacterEcho(CPU cpu) throws IOException {
        int ch = System.in.read();
        System.out.print((char) ch);
        cpu.getReg().AL.setValue((byte) ch);
    }

    @Interrupt(function = 0x02, description = "Write character to standard output")
    public void writeCharacter(final CPU cpu, final @DL char c) {
        System.out.print(c);
    }

    @Interrupt(function = 0x06, description = "Direct console input/output")
    public void directConsoleIO(final CPU cpu, final @DL byte dl) {
        if (dl == (byte) 0xFF) {
            try {
                if (System.in.available() > 0) {
                    int ch = System.in.read();
                    cpu.getReg().AL.setValue((byte) ch);
                    cpu.getReg().flags.setZero(false);
                } else {
                    cpu.getReg().AL.setValue((byte) 0);
                    cpu.getReg().flags.setZero(true);
                }
            } catch (IOException e) {
                cpu.getReg().AL.setValue((byte) 0);
                cpu.getReg().flags.setZero(true);
            }
        } else {
            System.out.print((char) (dl & 0xFF));
            cpu.getReg().flags.setZero(false);
        }
    }

    @Interrupt(function = 0x07, description = "Read character from standard input without echo")
    public void readCharacterNoEcho(CPU cpu) throws IOException {
        int ch = System.in.read();
        cpu.getReg().AL.setValue((byte) ch);
    }

    @Interrupt(function = 0x08, description = "Read character from standard input without echo")
    public void readCharacterNoEchoAlt(CPU cpu) throws IOException {
        int ch = System.in.read();
        cpu.getReg().AL.setValue((byte) ch);
    }

    @Interrupt(function = 0x09, description = "Write string to standard output")
    public void writeString(final CPU cpu, final @ASCIZ(terminationChar = '$') @DS @DX String s) {
        System.out.print(s);
        cpu.getReg().AL.setValue((byte) '$');
    }

    @Interrupt(function = 0x0A, description = "Buffered keyboard input")
    public void bufferedInput(final CPU cpu, final @DS @DX SegOfs buffer) {
        int maxLength = cpu.getMemory().readByte(buffer) & 0xFF;
        byte[] input = new byte[maxLength + 2];
        try {
            int pos = 0;
            while (true) {
                int ch = System.in.read();
                if (ch == '\r' || ch == '\n') {
                    break;
                }
                if (pos < maxLength) {
                    System.out.print((char) ch);
                    input[pos + 2] = (byte) ch;
                    pos++;
                }
            }
            System.out.println();
            input[0] = (byte) maxLength;
            input[1] = (byte) pos;
            MemoryUtil.writeBuf(cpu.getMemory(), buffer, input);
        } catch (IOException e) {
            input[0] = (byte) maxLength;
            input[1] = 0;
            MemoryUtil.writeBuf(cpu.getMemory(), buffer, input);
        }
    }

    @Interrupt(function = 0x63, description = "Double-Byte Character Set (DBCS) support in East Asian versions of DOS.")
    public void DbcsApi(final CPU cpu, final @AL char subfn) {
        // Do nothing
    }
}
