package example;

public class A {
    public int visit() {
        B b = new B();
        b.say("Hello");
        return 42;
    }

    public void unusedMethod() {
        System.out.println("I should be pruned");
    }
}

class B {
    public void say(String s) {
        System.out.println(s);
    }
}
