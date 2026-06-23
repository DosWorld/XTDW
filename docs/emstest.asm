bits 16
cpu 8086

org 0x100

start:
    ; Method 1: INT 67h AH=40h
    mov ah, 0x40
    int 0x67
    cmp ah, 0
    jne m1fail
    mov dx, m1ok
    jmp m1done
m1fail:
    mov dx, m1bad
m1done:
    mov ah, 9
    int 0x21

    ; Method 2: open "EMMXXXX0" device
    mov ah, 0x3D
    mov al, 0x02
    mov dx, emmname
    int 0x21
    jc m2fail
    mov bx, ax
    mov ah, 0x3E
    int 0x21
    mov dx, m2ok
    jmp m2done
m2fail:
    mov dx, m2bad
m2done:
    mov ah, 9
    int 0x21

    ; Method 3: INT 21h AH=35h get vector for INT 67h, check name at offset 0x0A
    mov ah, 0x35
    mov al, 0x67
    int 0x21
    mov ax, es
    or ax, bx
    jz m3fail
    ; compare 8 bytes at es:0x0A to "EMMXXXX0"
    push ds
    push es
    pop ds
    mov si, 0x0A
    mov di, emmname
    mov cx, 8
    repe cmpsb
    pop ds
    jne m3fail
    mov dx, m3ok
    jmp m3done
m3fail:
    mov dx, m3bad
m3done:
    mov ah, 9
    int 0x21

    mov ax, 0x4C00
    int 0x21

emmname:    db 'EMMXXXX0', 0
m1ok:       db 'Method1 (INT 67h AH=40h): OK', 13, 10, '$'
m1bad:      db 'Method1 (INT 67h AH=40h): FAIL', 13, 10, '$'
m2ok:       db 'Method2 (open EMMXXXX0): OK', 13, 10, '$'
m2bad:      db 'Method2 (open EMMXXXX0): FAIL', 13, 10, '$'
m3ok:       db 'Method3 (vector name check): OK', 13, 10, '$'
m3bad:      db 'Method3 (vector name check): FAIL', 13, 10, '$'
