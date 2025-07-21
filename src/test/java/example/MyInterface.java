package example;

public interface MyInterface {
    void doWork() throws MyException;

    default void log() {
        System.out.println("Logging...");
    }
}
