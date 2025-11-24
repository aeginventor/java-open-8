package com.example.resilient_purchase.service

import org.springframework.stereotype.Service
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Service
class ConcurrencyTestExecutor {

    companion object {
        private const val MAX_THREAD_POOL_SIZE = 50
    }

    fun executeConcurrentOrders(
        orderService: OrderService,
        productId: Long,
        quantity: Int,
        method: String,
        threads: Int
    ): TestResult {
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(minOf(threads, MAX_THREAD_POOL_SIZE))
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        submitOrderTasks(pool, latch, threads, orderService, productId, quantity, method, successCount, failureCount)
        awaitCompletion(latch, pool)

        return TestResult(successCount.get(), failureCount.get())
    }

    private fun submitOrderTasks(
        pool: java.util.concurrent.ExecutorService,
        latch: CountDownLatch,
        threads: Int,
        service: OrderService,
        productId: Long,
        quantity: Int,
        method: String,
        successCount: AtomicInteger,
        failureCount: AtomicInteger
    ) {
        repeat(threads) {
            pool.submit {
                executeOrder(service, productId, quantity, method, successCount, failureCount, latch)
            }
        }
    }

    private fun executeOrder(
        service: OrderService,
        productId: Long,
        quantity: Int,
        method: String,
        successCount: AtomicInteger,
        failureCount: AtomicInteger,
        latch: CountDownLatch
    ) {
        try {
            service.order(productId, quantity, method)
            successCount.incrementAndGet()
        } catch (_: Exception) {
            failureCount.incrementAndGet()
        } finally {
            latch.countDown()
        }
    }

    private fun awaitCompletion(latch: CountDownLatch, pool: java.util.concurrent.ExecutorService) {
        latch.await()
        pool.shutdown()
    }

    data class TestResult(
        val successCount: Int,
        val failureCount: Int
    )
}

