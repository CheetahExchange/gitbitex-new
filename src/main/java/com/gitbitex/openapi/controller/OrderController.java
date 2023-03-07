package com.gitbitex.openapi.controller;

import com.gitbitex.enums.OrderSide;
import com.gitbitex.enums.OrderStatus;
import com.gitbitex.enums.OrderType;
import com.gitbitex.enums.TimeInForcePolicy;
import com.gitbitex.kafka.KafkaMessageProducer;
import com.gitbitex.marketdata.entity.Order;
import com.gitbitex.marketdata.entity.Product;
import com.gitbitex.marketdata.entity.User;
import com.gitbitex.marketdata.repository.OrderRepository;
import com.gitbitex.matchingengine.command.CancelOrderCommand;
import com.gitbitex.matchingengine.command.PlaceOrderCommand;
import com.gitbitex.openapi.model.OrderDto;
import com.gitbitex.openapi.model.PagedList;
import com.gitbitex.openapi.model.PlaceOrderRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {
    private final OrderRepository orderRepository;
    private final KafkaMessageProducer producer;

    @PostMapping(value = "/orders")
    public OrderDto placeOrder(@RequestBody @Valid PlaceOrderRequest request,
                               @RequestAttribute(required = false) User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        OrderType type = OrderType.valueOf(request.getType().toUpperCase());
        OrderSide side = OrderSide.valueOf(request.getSide().toUpperCase());
        BigDecimal size = new BigDecimal(request.getSize());
        BigDecimal price = request.getPrice() != null ? new BigDecimal(request.getPrice()) : null;
        BigDecimal funds = request.getFunds() != null ? new BigDecimal(request.getFunds()) : null;
        TimeInForcePolicy timeInForcePolicy = request.getTimeInForce() != null
                ? TimeInForcePolicy.valueOf(request.getTimeInForce().toUpperCase())
                : null;

        PlaceOrderCommand command = new PlaceOrderCommand();
        command.setProductId(request.getProductId());
        command.setOrderId(UUID.randomUUID().toString());
        command.setUserId(currentUser.getId());
        command.setOrderType(type);
        command.setOrderSide(side);
        command.setSize(size);
        command.setPrice(price);
        command.setFunds(funds);
        command.setTime(new Date());
        producer.sendToMatchingEngine("all", command, null);

        OrderDto orderDto = new OrderDto();
        orderDto.setId(command.getOrderId());
        return orderDto;
    }

    @DeleteMapping("/orders/{orderId}")
    @SneakyThrows
    public void cancelOrder(@PathVariable String orderId, @RequestAttribute(required = false) User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        Order order = orderRepository.findByOrderId(orderId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: " + orderId);
        }
        if (!order.getUserId().equals(currentUser.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        CancelOrderCommand command = new CancelOrderCommand();
        command.setProductId(order.getProductId());
        command.setOrderId(order.getOrderId());
        producer.sendToMatchingEngine("all", command, null);
    }

    @DeleteMapping("/orders")
    @SneakyThrows
    public void cancelOrders(String productId, String side, @RequestAttribute(required = false) User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        OrderSide orderSide = side != null ? OrderSide.valueOf(side.toUpperCase()) : null;

        PagedList<Order> orderPage = orderRepository.findAll(currentUser.getId(), productId, OrderStatus.OPEN,
                orderSide, 1, 20000);

        for (Order order : orderPage.getItems()) {
            //matchingEngineService.handleCancelOrderRequest(order);
        }
    }

    @GetMapping("/orders")
    public PagedList<OrderDto> listOrders(@RequestParam(required = false) String productId,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "50") int pageSize,
                                          @RequestAttribute(required = false) User currentUser) {
        if (currentUser == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        OrderStatus orderStatus = status != null ? OrderStatus.valueOf(status.toUpperCase()) : null;

        PagedList<Order> orderPage = orderRepository.findAll(currentUser.getId(), productId, orderStatus, null,
                page,
                pageSize);
        return new PagedList<>(
                orderPage.getItems().stream().map(this::orderDto).collect(Collectors.toList()),
                orderPage.getCount());
    }

    private OrderDto orderDto(Order order) {
        OrderDto orderDto = new OrderDto();
        orderDto.setId(order.getOrderId());
        orderDto.setPrice(order.getPrice().toPlainString());
        orderDto.setSize(order.getSize().toPlainString());
        orderDto.setFilledSize(order.getFilledSize() != null ? order.getFilledSize().toPlainString() : "0");
        orderDto.setFunds(order.getFunds() != null ? order.getFunds().toPlainString() : "0");
        orderDto.setExecutedValue(order.getExecutedValue() != null ? order.getExecutedValue().toPlainString() : "0");
        orderDto.setSide(order.getSide().name().toLowerCase());
        orderDto.setProductId(order.getProductId());
        orderDto.setType(order.getType().name().toLowerCase());
        if (order.getCreatedAt() != null) {
            orderDto.setCreatedAt(order.getCreatedAt().toInstant().toString());
        }
        if (order.getStatus() != null) {
            orderDto.setStatus(order.getStatus().name().toLowerCase());
        }
        return orderDto;
    }

    private void formatOrder(Order order, Product product) {
        BigDecimal size = order.getSize();
        BigDecimal price = order.getPrice();
        BigDecimal funds = order.getFunds();
        OrderSide side = order.getSide();

        switch (order.getType()) {
            case LIMIT:
                size = size.setScale(product.getBaseScale(), RoundingMode.DOWN);
                price = price.setScale(product.getQuoteScale(), RoundingMode.DOWN);
                funds = side == OrderSide.BUY ? size.multiply(price) : BigDecimal.ZERO;
                break;
            case MARKET:
                price = BigDecimal.ZERO;
                if (side == OrderSide.BUY) {
                    size = BigDecimal.ZERO;
                    funds = funds.setScale(product.getQuoteScale(), RoundingMode.DOWN);
                } else {
                    size = size.setScale(product.getBaseScale(), RoundingMode.DOWN);
                    funds = BigDecimal.ZERO;
                }
                break;
            default:
                throw new RuntimeException("unknown order type: " + order.getType());
        }

        order.setSize(size);
        order.setPrice(price);
        order.setFunds(funds);
    }

    private void validateOrder(Order order) {
        BigDecimal size = order.getSize();
        BigDecimal funds = order.getFunds();
        OrderSide side = order.getSide();

        if (side == OrderSide.SELL) {
            if (size.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("bad SELL order: size must be positive");
            }
        } else {
            if (funds.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("bad BUY order: funds must be positive");
            }
        }
    }

}
