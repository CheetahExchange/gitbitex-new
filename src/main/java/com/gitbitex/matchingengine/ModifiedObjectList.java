package com.gitbitex.matchingengine;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import lombok.Getter;

@Getter
public class ModifiedObjectList extends ArrayList<Object> {
    private final AtomicLong savedCounter = new AtomicLong();
    private final long commandOffset;
    private final String productId;

    public ModifiedObjectList(long commandOffset, String productId) {
        super();
        this.commandOffset = commandOffset;
        this.productId = productId;
    }

    public boolean isAllSaved() {
        return savedCounter.get() == size();
    }

    @Override
    public boolean add(Object o) {
        if (o == null) {
            throw new NullPointerException("o");
        }
        return super.add(o);
    }
}

