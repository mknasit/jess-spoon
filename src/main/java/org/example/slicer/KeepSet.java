package org.example.slicer;

import spoon.reflect.declaration.CtElement;

import java.util.*;

/**
 * A policy map of elements -> retention strategy.
 * FULL  = keep definition body (method/ctor/static block body, field initializer, etc.)
 * SIGNATURE = keep only the declaration, stub where needed.
 */
public class KeepSet {

    public enum Retention { SIGNATURE, FULL }

    private final Map<CtElement, Retention> map = new LinkedHashMap<>();

    public void markSig(CtElement el) {
        if (el == null) return;
        map.putIfAbsent(el, Retention.SIGNATURE);
    }

    public void markFull(CtElement el) {
        if (el == null) return;
        map.put(el, Retention.FULL);
    }

    public boolean contains(CtElement el) {
        return el != null && map.containsKey(el);
    }

    public Retention retentionOf(CtElement el) {
        Retention r = map.get(el);
        return r == null ? Retention.SIGNATURE : r;
    }

    public void remove(CtElement el) { map.remove(el); }

    public Set<CtElement> elements() { return new LinkedHashSet<>(map.keySet()); }

    public void clear() { map.clear(); }
}
