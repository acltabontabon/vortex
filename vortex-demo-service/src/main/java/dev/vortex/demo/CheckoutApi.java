package dev.vortex.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demonstration API: four operations covering the shapes a real service has — a read, a write,
 * a read-after-write, and a state change.
 *
 * <p>Everything here goes through the bounded {@link WorkerPool}, so all four degrade together as
 * traffic rises. The data is held in memory and is not meant to be interesting; what is being
 * demonstrated is behaviour under load, not domain logic.
 */
@RestController
public class CheckoutApi {

    private final WorkerPool workers;
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final AtomicLong orderSequence = new AtomicLong();

    public CheckoutApi(WorkerPool workers) {
        this.workers = workers;
    }

    public record Account(String id, String status, BigDecimal availableBalance) {
    }

    public record Order(String id, String accountId, BigDecimal amount, String status, Instant createdAt) {
    }

    public record CreateOrderRequest(
            @NotBlank String accountId,
            @Positive BigDecimal amount) {
    }

    public record ErrorResponse(String error, String message) {
    }

    @GetMapping("/accounts/{id}")
    public Account getAccount(@PathVariable String id) {
        return workers.withWorker(() -> new Account(id, "ACTIVE",
                BigDecimal.valueOf(ThreadLocalRandom.current().nextInt(1_000, 100_000))
                        .movePointLeft(2)));
    }

    @PostMapping("/orders")
    public ResponseEntity<Order> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        Order created = workers.withWorker(() -> {
            String id = "ord-" + orderSequence.incrementAndGet();
            Order order = new Order(id, request.accountId(), request.amount(), "ACCEPTED", Instant.now());
            orders.put(id, order);
            return order;
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<Order> getOrder(@PathVariable String id) {
        Order order = workers.withWorker(() -> orders.get(id));
        // An unknown id is a legitimate 404 rather than an error, which keeps the demonstration
        // honest: a load test that reuses generated ids will see some of these, and that is a
        // property of the test data, not a fault in the service.
        return order == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(order);
    }

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<Order> cancelOrder(@PathVariable String id) {
        Order cancelled = workers.withWorker(() -> orders.computeIfPresent(id,
                (_, existing) -> new Order(existing.id(), existing.accountId(), existing.amount(),
                        "CANCELLED", existing.createdAt())));
        return cancelled == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(cancelled);
    }

    /**
     * The saturation response.
     *
     * <p>503 with {@code Retry-After} is what a well-behaved service does when it cannot take more
     * work, and it is what makes the error rate climb in a Vortex stress test rather than the run
     * simply timing out.
     */
    @ExceptionHandler(WorkerPool.CapacityExceededException.class)
    public ResponseEntity<ErrorResponse> handleSaturation(WorkerPool.CapacityExceededException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(new ErrorResponse("capacity_exceeded", e.getMessage()));
    }
}
