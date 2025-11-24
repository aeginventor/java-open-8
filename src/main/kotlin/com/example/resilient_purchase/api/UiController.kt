package com.example.resilient_purchase.api

import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext

data class ResetStockRequest(
    val stock: Int
)

data class StockResponse(
    val productId: Long,
    val stock: Int
)

data class RunExperimentRequest(
    val threads: Int = 200,
    val method: String = "no-lock"
)

data class RunExperimentResult(
    val method: String,
    val initialStock: Int,
    val threads: Int,
    val successCount: Int,
    val failureCount: Int,
    val remainingStock: Int,
    val oversold: Boolean
)

@RestController
@RequestMapping("/ui")
class UiController(

    private val productRepository: ProductRepository,

    @Qualifier("noLockOrderService")
    private val noLockOrderService: OrderService,

    @Qualifier("lockOrderService")
    private val lockOrderService: OrderService,

    @Qualifier("pessimisticLockOrderService")
    private val pessimisticLockOrderService: OrderService,

    @PersistenceContext
    private val entityManager: EntityManager
)  {

    private val targetProductId: Long = 1L

    @PostMapping("/reset-stock")
    fun resetStock(@RequestBody req: ResetStockRequest): ResponseEntity<StockResponse> {
        val product = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

        product.stock = req.stock
        productRepository.save(product)

        return ResponseEntity.ok(
            StockResponse(
                productId = product.id!!,
                stock = product.stock
            )
        )
    }

    @GetMapping("/current-stock")
    fun currentStock(): ResponseEntity<StockResponse> {
        val product = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

        return ResponseEntity.ok(
            StockResponse(
                productId = product.id!!,
                stock = product.stock
            )
        )
    }

    @PostMapping("/run-experiment")
    fun runExperiment(@RequestBody req: RunExperimentRequest): ResponseEntity<RunExperimentResult> {
        // 1) 실험 시작 시 재고 읽기
        val product = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

        val initialStock = product.stock
        val threads = req.threads
        val method = req.method

        val service = when (method) {
            "no-lock" -> noLockOrderService
            "pessimistic" -> pessimisticLockOrderService
            else -> lockOrderService
        }

        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(minOf(threads, 50))

        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)

        repeat(threads) {
            pool.submit {
                try {
                    try {
                        service.order(targetProductId, 1, method)
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

        entityManager.clear()

        val finalProduct = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("실험 중 상품이 사라졌습니다.") }

        val remaining = finalProduct.stock

        val oversold = successCount.get() > initialStock

        val result = RunExperimentResult(
            method = method,
            initialStock = initialStock,
            threads = threads,
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            remainingStock = remaining,
            oversold = oversold
        )

        return ResponseEntity.ok(result)
    }
}