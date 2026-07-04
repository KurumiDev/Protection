public class TestThrow {
    public static void main(String[] args) {
        try {
            throw new RuntimeException("test");
        } catch (Throwable t) {
            System.err.println("Caught exception: " + t);
            t.printStackTrace();
        }
    }
}
