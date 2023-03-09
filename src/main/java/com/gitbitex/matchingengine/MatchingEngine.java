package com.gitbitex.matchingengine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.fastjson.JSON;

import com.gitbitex.AppProperties;
import com.gitbitex.enums.OrderStatus;
import com.gitbitex.kafka.KafkaMessageProducer;
import com.gitbitex.matchingengine.command.CancelOrderCommand;
import com.gitbitex.matchingengine.command.DepositCommand;
import com.gitbitex.matchingengine.command.PlaceOrderCommand;
import com.gitbitex.matchingengine.command.PutProductCommand;
import com.gitbitex.matchingengine.message.OrderLog;
import com.gitbitex.matchingengine.snapshot.L2OrderBook;
import com.gitbitex.matchingengine.snapshot.OrderBookManager;
import com.gitbitex.stripexecutor.StripedExecutorService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

@Slf4j
public class MatchingEngine {
    private final ProductBook productBook = new ProductBook();
    private final AccountBook accountBook = new AccountBook();
    private final Map<String, OrderBook> orderBooks = new HashMap<>();
    private final ConcurrentHashMap<String, SimpleOrderBook> simpleOrderBooks = new ConcurrentHashMap<>();
    private final ConcurrentSkipListMap<Long, ModifiedObjectList> modifiedObjectsByCommandOffset
        = new ConcurrentSkipListMap<>();
    private final StripedExecutorService simpleOrderBookExecutor = new StripedExecutorService(1);
    private final StripedExecutorService kafkaExecutor = new StripedExecutorService(2);
    private final StripedExecutorService redisExecutor = new StripedExecutorService(2);
    private final ScheduledExecutorService scheduledExecutor = Executors.newScheduledThreadPool(3);
    private final OrderBookManager orderBookManager;
    private final KafkaMessageProducer producer;
    private final AppProperties appProperties;
    private final MatchingEngineStateStore stateStore;
    private final RTopic accountTopic;
    private final RTopic orderTopic;
    private final RTopic orderBookLogTopic;
    private final ConcurrentLinkedQueue<ModifiedObjectList> modifiedObjectQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong modifiedObjectQueueSizeCounter = new AtomicLong();
    private final ConcurrentHashMap<String, Long> lastL2OrderBookSequences = new ConcurrentHashMap<>();

    @Getter
    private Long startupCommandOffset;

    public MatchingEngine(MatchingEngineStateStore stateStore, KafkaMessageProducer producer,
        RedissonClient redissonClient, OrderBookManager orderBookManager, AppProperties appProperties) {
        this.appProperties = appProperties;
        this.producer = producer;
        this.stateStore = stateStore;
        this.orderBookManager = orderBookManager;
        this.accountTopic = redissonClient.getTopic("account", StringCodec.INSTANCE);
        this.orderTopic = redissonClient.getTopic("order", StringCodec.INSTANCE);
        this.orderBookLogTopic = redissonClient.getTopic("orderBookLog", StringCodec.INSTANCE);
        restoreState();
        startSateSaveTask();
        startModifiedObjectSaveTask();
        startL2OrderBookPublishTask();
    }

    public void shutdown() {
        simpleOrderBookExecutor.shutdown();
        kafkaExecutor.shutdown();
        redisExecutor.shutdown();
        scheduledExecutor.shutdown();
        stateStore.close();
    }

    public void executeCommand(DepositCommand command) {
        ModifiedObjectList modifiedObjects = new ModifiedObjectList(command.getOffset(), null);
        accountBook.deposit(command.getUserId(), command.getCurrency(), command.getAmount(), command.getTransactionId(),
            modifiedObjects);
        enqueue(modifiedObjects);
    }

    public void executeCommand(PutProductCommand command) {
        ModifiedObjectList modifiedObjects = new ModifiedObjectList(command.getOffset(), null);
        productBook.putProduct(new Product(command), modifiedObjects);
        enqueue(modifiedObjects);
    }

    public void executeCommand(PlaceOrderCommand command) {
        OrderBook orderBook = createOrderBook(command.getProductId());
        ModifiedObjectList modifiedObjects = new ModifiedObjectList(command.getOffset(), command.getProductId());
        orderBook.placeOrder(new Order(command), modifiedObjects);
        enqueue(modifiedObjects);
    }

    public void executeCommand(CancelOrderCommand command) {
        ModifiedObjectList modifiedObjects = new ModifiedObjectList(command.getOffset(), command.getProductId());
        orderBooks.get(command.getProductId()).cancelOrder(command.getOrderId(), modifiedObjects);
        enqueue(modifiedObjects);
    }

    private OrderBook createOrderBook(String productId) {
        OrderBook orderBook = orderBooks.get(productId);
        if (orderBook == null) {
            orderBook = new OrderBook(productId, null, null, accountBook, productBook);
            orderBooks.put(productId, orderBook);
        }
        return orderBook;
    }

    private void enqueue(ModifiedObjectList modifiedObjects) {
        while (modifiedObjectQueueSizeCounter.get() >= 100000) {
            logger.warn("modified object queue is full");
            Thread.yield();
        }
        modifiedObjectQueue.offer(modifiedObjects);
        modifiedObjectQueueSizeCounter.incrementAndGet();
    }

    private void save(ModifiedObjectList modifiedObjects) {
        modifiedObjectsByCommandOffset.put(modifiedObjects.getCommandOffset(), modifiedObjects);

        modifiedObjects.forEach(obj -> {
            if (obj instanceof Order) {
                save(modifiedObjects.getSavedCounter(), (Order)obj);
            } else if (obj instanceof Account) {
                save(modifiedObjects.getSavedCounter(), (Account)obj);
            } else if (obj instanceof Trade) {
                save(modifiedObjects.getSavedCounter(), (Trade)obj);
            } else if (obj instanceof OrderLog) {
                save(modifiedObjects.getSavedCounter(), (OrderLog)obj);
            } else {
                modifiedObjects.getSavedCounter().incrementAndGet();
            }
        });

        updateSimpleOrderBook(modifiedObjects);
    }

    private void updateSimpleOrderBook(ModifiedObjectList modifiedObjects) {
        String productId = modifiedObjects.getProductId();
        if (productId == null) {
            return;
        }

        simpleOrderBookExecutor.execute(productId, () -> {
            simpleOrderBooks.putIfAbsent(productId, new SimpleOrderBook(productId));
            SimpleOrderBook simpleOrderBook = simpleOrderBooks.get(productId);
            modifiedObjects.forEach(obj -> {
                if (obj instanceof Order) {
                    Order order = (Order)obj;
                    if (order.getStatus() == OrderStatus.OPEN) {
                        simpleOrderBook.putOrder(order);
                    } else {
                        simpleOrderBook.removeOrder(order);
                    }
                } else if (obj instanceof OrderLog) {
                    OrderLog orderLog = (OrderLog)obj;
                    simpleOrderBook.setSequence(orderLog.getSequence());
                } else if (obj instanceof OrderBookCompleteNotify) {
                    takeL2OrderBookSnapshot(simpleOrderBook, 500);
                }
            });
        });
    }

    private void takeL2OrderBookSnapshot(SimpleOrderBook simpleOrderBook, long sequenceDiff) {
        String productId = simpleOrderBook.getProductId();
        long lastL2OrderBookSequence = lastL2OrderBookSequences.getOrDefault(productId, 0L);
        if (simpleOrderBook.getSequence() - lastL2OrderBookSequence > sequenceDiff) {
            L2OrderBook l2OrderBook = new L2OrderBook(simpleOrderBook, 50);
            lastL2OrderBookSequences.put(productId, simpleOrderBook.getSequence());
            logger.info(JSON.toJSONString(l2OrderBook));
            orderBookManager.saveL2BatchOrderBook(l2OrderBook);
        }
    }

    private void save(AtomicLong savedCounter, Account account) {
        kafkaExecutor.execute(account.getUserId(), () -> {
            String data = JSON.toJSONString(account);
            producer.sendAccount(account, (m, e) -> savedCounter.incrementAndGet());
            accountTopic.publishAsync(data);
        });
    }

    private void save(AtomicLong savedCounter, Order order) {
        String productId = order.getProductId();
        kafkaExecutor.execute(productId, () -> {
            String data = JSON.toJSONString(order);
            producer.sendOrder(order, (m, e) -> savedCounter.incrementAndGet());
            orderTopic.publishAsync(data);
        });
    }

    private void save(AtomicLong savedCounter, Trade trade) {
        kafkaExecutor.execute(trade.getProductId(), () -> {
            String data = JSON.toJSONString(trade);
            producer.sendTrade(trade, (m, e) -> savedCounter.incrementAndGet());
        });
    }

    private void save(AtomicLong savedCounter, OrderLog orderLog) {
        savedCounter.incrementAndGet();
        String productId = orderLog.getProductId();
        kafkaExecutor.execute(productId, () -> {
            orderBookLogTopic.publishAsync(JSON.toJSONString(orderLog));
        });
    }

    private void saveState() {
        if (modifiedObjectsByCommandOffset.isEmpty()) {
            return;
        }

        Long commandOffset = null;
        Set<Account> accounts = new HashSet<>();
        Set<Order> orders = new HashSet<>();
        Set<Product> products = new HashSet<>();
        Map<String, Long> tradeIds = new HashMap<>();
        Map<String, Long> sequences = new HashMap<>();

        Iterator<Entry<Long, ModifiedObjectList>> itr = modifiedObjectsByCommandOffset.entrySet().iterator();
        while (itr.hasNext()) {
            Entry<Long, ModifiedObjectList> entry = itr.next();
            ModifiedObjectList modifiedObjects = entry.getValue();
            if (!modifiedObjects.isAllSaved()) {
                break;
            }
            for (Object obj : modifiedObjects) {
                if (obj instanceof Account) {
                    accounts.add((Account)obj);
                } else if (obj instanceof Order) {
                    orders.add((Order)obj);
                } else if (obj instanceof Trade) {
                    Trade trade = (Trade)obj;
                    tradeIds.put(trade.getProductId(), trade.getTradeId());
                } else if (obj instanceof OrderLog) {
                    OrderLog orderLog = (OrderLog)obj;
                    sequences.put(orderLog.getProductId(), orderLog.getSequence());
                } else if (obj instanceof Product) {
                    products.add((Product)obj);
                }
            }
            commandOffset = entry.getKey();
            itr.remove();
        }

        if (commandOffset != null) {
            Long savedCommandOffset = stateStore.getCommandOffset();
            if (savedCommandOffset != null && commandOffset <= savedCommandOffset) {
                logger.warn("ignore outdated commandOffset: ignored={} saved={}", commandOffset, savedCommandOffset);
                return;
            }
            stateStore.write(commandOffset, accounts, orders, products, tradeIds, sequences);
            logger.info(
                "state saved: commandOffset={}, {} account(s), {} order(s), {} product(s), {} tradeId(s), {} sequence"
                    + "(s)",
                commandOffset, accounts.size(), orders.size(), products.size(), tradeIds.size(), sequences.size());
        }
    }

    private void restoreState() {
        startupCommandOffset = stateStore.getCommandOffset();
        if (startupCommandOffset == null) {
            return;
        }
        Map<String, Long> tradeIds = stateStore.getTradeIds();
        Map<String, Long> sequences = stateStore.getSequences();
        stateStore.forEachProduct(productBook::addProduct);
        stateStore.forEachAccount(accountBook::add);
        stateStore.forEachOrder(order -> {
            String productId = order.getProductId();
            Long tradeId = tradeIds.get(productId);
            Long sequence = sequences.get(productId);
            orderBooks.computeIfAbsent(productId,
                k -> new OrderBook(productId, tradeId, sequence, accountBook, productBook)).addOrder(order);
            simpleOrderBooks.computeIfAbsent(productId,
                k -> new SimpleOrderBook(productId, sequence)).putOrder(order);
        });
    }

    private void startSateSaveTask() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            try {
                saveState();
            } catch (Exception e) {
                logger.error("save state error: {}", e.getMessage(), e);
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private void startModifiedObjectSaveTask() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            while (true) {
                ModifiedObjectList modifiedObjects = modifiedObjectQueue.poll();
                if (modifiedObjects == null) {
                    break;
                }
                modifiedObjectQueueSizeCounter.decrementAndGet();
                save(modifiedObjects);
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void startL2OrderBookPublishTask() {
        scheduledExecutor.scheduleWithFixedDelay(() -> {
            simpleOrderBooks.forEach((productId, simpleOrderBook) -> {
                simpleOrderBookExecutor.execute(productId, () -> {
                    takeL2OrderBookSnapshot(simpleOrderBook, 0);
                });
            });
        }, 0, 1, TimeUnit.SECONDS);
    }

}
