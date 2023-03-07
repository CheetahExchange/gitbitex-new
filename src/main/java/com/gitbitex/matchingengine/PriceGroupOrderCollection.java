package com.gitbitex.matchingengine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;

import lombok.Getter;

@Getter
public class PriceGroupOrderCollection extends LinkedHashMap<String, Order> {
    //public BigDecimal remainingSize = BigDecimal.ZERO;

    public void addOrder(Order order) {
        put(order.getOrderId(), order);
        //remainingSize = remainingSize.add(order.getRemainingSize());
    }

    public void decrRemainingSize(BigDecimal size){
        //remainingSize=remainingSize.subtract(size);
    }

    public BigDecimal getRemainingSize(){
        return values().stream()
                .map(x->x.getRemainingSize())
                .reduce((x,y)->x.add(y))
                .get();
    }
}
