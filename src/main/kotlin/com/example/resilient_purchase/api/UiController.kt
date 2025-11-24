package com.example.resilient_purchase.api

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class ResetStockRequest(
    val stock: Int
)

data class StockResponse(
    val productId: Long,
    val stock: Int
)

data class RunExperimentRequest(
    val threads: Int = 200,
    val quantity: Int = 1,
    val method: String = "no-lock"
)

data class RunExperimentResult(
    val method: String,
    val initialStock: Int,
    val threads: Int,
    val quantity: Int,
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
) {

    private val targetProductId: Long = 1L  // 실험용 기본 상품 ID

    /** 1. 재고 초기화 */
    @PostMapping("/reset-stock")
    fun resetStock(@RequestBody req: ResetStockRequest): ResponseEntity<StockResponse> {
        require(req.stock >= 0) { "재고는 0 이상이어야 합니다." }

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

    /** 2. 현재 재고 조회 */
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

    /** 3. 동시 주문 실험 실행 */
    @PostMapping("/run-experiment")
    fun runExperiment(@RequestBody req: RunExperimentRequest): ResponseEntity<RunExperimentResult> {
        require(req.threads in 1..200) { "동시 요청 수는 1 이상 200 이하여야 합니다." }
        require(req.quantity >= 1) { "주문 수량은 1 이상이어야 합니다." }

        val product = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

        val initialStock = product.stock
        val threads = req.threads
        val quantity = req.quantity
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
                        service.order(targetProductId, quantity, method)
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

        val oversold = successCount.get() * quantity > initialStock

        val result = RunExperimentResult(
            method = method,
            initialStock = initialStock,
            threads = threads,
            quantity = quantity,
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            remainingStock = remaining,
            oversold = oversold
        )

        return ResponseEntity.ok(result)
    }
}