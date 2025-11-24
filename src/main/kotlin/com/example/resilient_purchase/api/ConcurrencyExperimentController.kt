package com.example.resilient_purchase.api

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping("/experiments")
class ConcurrencyExperimentController(

    private val productRepository: ProductRepository,

    @Qualifier("noLockOrderService")
    private val noLockOrderService: OrderService,

    @Qualifier("lockOrderService")
    private val lockOrderService: OrderService,

    @Qualifier("pessimisticLockOrderService")
    private val pessimisticLockOrderService: OrderService
) {

    @PostMapping("/concurrency")
    fun runExperiment(@RequestBody req: ConcurrencyExperimentRequest): ResponseEntity<ConcurrencyExperimentResult> {
        val initialStock = req.initialStock
        val saved = productRepository.save(
            Product(
                name = "experiment-${req.method}",
                stock = initialStock
            )
        )
        val productId = saved.id!!

        val service = when (req.method) {
            "no-lock" -> noLockOrderService
            "pessimistic" -> pessimisticLockOrderService
            else -> lockOrderService
        }

        val threads = req.threads
        val quantity = req.quantity

        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(minOf(threads, 50))

        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                try {
                    try {
                        service.order(productId, quantity, req.method)
                        successCount.incrementAndGet()
                    } catch (_: Exception) {
                        failureCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()

        val remaining = productRepository.findById(productId).get().stock
        val invariantHolds =
            remaining >= 0 && successCount.get() * quantity + remaining == initialStock

        val result = ConcurrencyExperimentResult(
            method = req.method,
            initialStock = initialStock,
            threads = threads,
            quantity = quantity,
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            remainingStock = remaining,
            invariantHolds = invariantHolds
        )

        return ResponseEntity.ok(result)
    }
}
