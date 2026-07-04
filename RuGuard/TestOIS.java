import java.io.*;
public class TestOIS {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(new java.util.Date()); // Use a class that exists
        oos.close();
        byte[] bytes = bos.toByteArray();
        // Replace "java/util/Date" with "java/util/XXXX" in bytes
        for(int i = 0; i < bytes.length - 14; i++) {
            if(bytes[i] == 'j' && bytes[i+1] == 'a' && bytes[i+2] == 'v' && bytes[i+3] == 'a' && bytes[i+10] == 'D') {
                bytes[i+10] = 'X'; bytes[i+11] = 'X'; bytes[i+12] = 'X'; bytes[i+13] = 'X';
            }
        }
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes));
        try {
            ois.readObject();
        } catch(Throwable t) {
            System.err.println("Caught: " + t);
        }
    }
}
