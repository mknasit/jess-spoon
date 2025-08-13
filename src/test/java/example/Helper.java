package example;

public class Helper {
    int a = 5;
    public int run() {
        A test = new A();
        test.visit();
        System.out.println("Inner class running...");
        return a;
    }
    public static class Inner {

    }
}
