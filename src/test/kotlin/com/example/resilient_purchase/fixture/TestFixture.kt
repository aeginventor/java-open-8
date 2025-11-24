package com.example.resilient_purchase.fixture

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object TestFixture {

    fun createTestProduct(repository: ProductRepository, name: String, stock: Int): Product {
        return repository.save(Product(name = name, stock = stock))
    }

    fun executeConcurrentOrders(
        service: OrderService,
        productId: Long,
        threads: Int,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(50)

        repeat(threads) {
            pool.submit {
                try {
                    service.order(productId, 1, "test")
                    onSuccess()
                } catch (e: Exception) {
                    onFailure()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()
    }

    data class ConcurrencyTestResult(
        val successCount: Int,
        val failureCount: Int = 0
    )

    fun runConcurrencyTest(
        service: OrderService,
        productId: Long,
        threads: Int
    ): ConcurrencyTestResult {
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        executeConcurrentOrders(
            service, productId, threads,
            onSuccess = { successCount.incrementAndGet() },
            onFailure = { failureCount.incrementAndGet() }
        )

        return ConcurrencyTestResult(successCount.get(), failureCount.get())
    }
}

