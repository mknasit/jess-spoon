package example;

public class Worker implements MyInterface {

    private String status = "idle";

    @Override
    public void doWork() throws MyException {
      //  log();            // from interface
       // Util.help();      // external class
        System.out.println("Status: " + status);
       Helper helpertest = new Helper();  // ✅ use nested class
        int a = helpertest.run();
        System.out.println("value: " + a);
       // throw new MyException("fail");
    }

    public void unusedMethod() {
        System.out.println("Should be removed");
    }
}
