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
        val product = createExperimentProduct(req.method, req.initialStock)
        val service = selectOrderService(req.method)

        val (successCount, failureCount) = executeConcurrentOrders(
            service, product.id!!, req.quantity, req.method, req.threads
        )

        val remainingStock = getRemainingStock(product.id!!)
        val invariantHolds = checkInvariant(successCount, req.quantity, remainingStock, req.initialStock)

        val result = buildExperimentResult(
            req, successCount, failureCount, remainingStock, invariantHolds
        )

        return ResponseEntity.ok(result)
    }

    private fun createExperimentProduct(method: String, initialStock: Int): Product {
        return productRepository.save(
            Product(
                name = "experiment-$method",
                stock = initialStock
            )
        )
    }

    private fun selectOrderService(method: String): OrderService {
        return when (method) {
            "no-lock" -> noLockOrderService
            "pessimistic" -> pessimisticLockOrderService
            else -> lockOrderService
        }
    }

    private fun executeConcurrentOrders(
        service: OrderService,
        productId: Long,
        quantity: Int,
        method: String,
        threads: Int
    ): Pair<Int, Int> {
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(minOf(threads, 50))
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                try {
                    service.order(productId, quantity, method)
                    successCount.incrementAndGet()
                } catch (_: Exception) {
                    failureCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()

        return Pair(successCount.get(), failureCount.get())
    }

    private fun getRemainingStock(productId: Long): Int {
        return productRepository.findById(productId).get().stock
    }

    private fun checkInvariant(successCount: Int, quantity: Int, remaining: Int, initialStock: Int): Boolean {
        return remaining >= 0 && successCount * quantity + remaining == initialStock
    }

    private fun buildExperimentResult(
        req: ConcurrencyExperimentRequest,
        successCount: Int,
        failureCount: Int,
        remainingStock: Int,
        invariantHolds: Boolean
    ): ConcurrencyExperimentResult {
        return ConcurrencyExperimentResult(
            method = req.method,
            initialStock = req.initialStock,
            threads = req.threads,
            quantity = req.quantity,
            successCount = successCount,
            failureCount = failureCount,
            remainingStock = remainingStock,
            invariantHolds = invariantHolds
        )
    }
}
