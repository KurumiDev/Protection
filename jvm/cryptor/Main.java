package crypting;

public class Main {

    static int key = 20;

    static long key_2 = 0xFFFFFFF;

    public static int littleOperations(int number) {
        return ((number)
                ^ (0xFFFFF -
                (0xFFFFF ^ number
                        ^ (number << 8 |
                        number >> 8)))+1);
    }

    static int positions = 4;

    public static short shiftLeft(int value) {
        return (short) ((short) ((short) (value << positions)
                                ^ positions
                                        << (positions % 32))
                                                        ^ 0xFD);
    }

    public static short shiftRight(int value) {
        return (short) ((short) ((value ^ positions
                                << (positions % 32)
                                        ^ 0xFD)
                                            >> positions));
    }

//    public static short xor(short input) {
//
////        return (short) rotateRight(input, 8);
////        return (byte) (((byte) ~(~Integer.rotateLeft(
////                (~Integer.rotateRight(
////                        (int) (~(input) ^ (~key_2 % (key - 0xD44DA))
////                                ^ (~key)
////                                    ^ 0xFF4DE4F),
////                                        256)) ^ 0xFF464,
////                                                    128) ^ 0xFF4DF)
////                                                            ^ 0x15)
////                                                                ^ Integer.reverseBytes(~(Integer.rotateRight((int) (key_2%key),
////                                                                        (int) (key_2/4)))/8));
//    }

    public static int pack(int number, int _key) {
        return shiftLeft(~
                (~number ^
                        shiftRight(~_key >> 1) ^ ((shiftLeft(0xCAFEBABE) >> 2) ^ shiftRight(0xCAFEBABA) >> 2))
                ^ (shiftRight(~_key ^ 0xFFFF)
                % 0xFFFF));
    }

    public static void main(String[] args) {
        System.out.println();
        int lol = 127;
        System.out.println(lol = pack(lol, 20));
        System.out.println(lol = pack(lol, 20));
    }

}