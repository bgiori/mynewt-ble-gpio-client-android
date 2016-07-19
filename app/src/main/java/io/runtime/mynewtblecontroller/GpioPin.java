package io.runtime.mynewtblecontroller;

/**
 * Used to abstract the GPIO pin's number, direction (Input/Output), and value (High/Low).
 * Also used to convert between instruction and GpioPin and visa versa.
 */
public class GpioPin {
    public int pinNumber;
    public boolean isHigh;
    public boolean isOuput;

    /* Instatiate GpioPin from number, direction, and value */
    public GpioPin(int pinNumber, boolean isOuput, boolean isHigh) {
        this.isOuput = isOuput;
        this.pinNumber = pinNumber;
        this.isHigh = isHigh;
    }

    /* Instantiate GpioPin from instruction */
    public GpioPin(int instr) {
        this.isOuput = (getDir(instr) == 1);
        this.pinNumber = getPin(instr);
        this.isHigh = (getVal(instr) != 0);
    }

    /* Create device readable instruction from number, direction and value */
    public static int createGpioInstr(int pin, int dir, int val){
        pin <<= 8;
        dir <<= 4;
        pin += dir;
        pin += val;
        return pin;
    }

    /* Create device readable instruction from GpioPin object */
    public static int createGpioInstr(GpioPin gPin) {
        int dir = gPin.isOuput ? 1 : 2;
        int val = gPin.isHigh ? 1 : 0;
        return createGpioInstr(gPin.pinNumber, dir, val);
    }

    /* Helpers */
    private static int getPin(int instr) {
        return (instr >>> 8);
    }
    private static int getDir(int instr) {
        return ((instr & 0x00f0) >>> 4);
    }
    private static int getVal(int instr) {
        return (instr & 0x000f);
    }




}

