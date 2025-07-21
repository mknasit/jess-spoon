package example;

public class Helper {
    public void run() {
        A test = new A();
        test.visit();
        System.out.println("Inner class running...");
    }
    public static class Inner {

    }
}
