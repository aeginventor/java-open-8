package com.example.resilient_purchase.api

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
    val method: String = "no-lock"
)

data class RunExperimentResult(
    val method: String,
    val initialStock: Int,
    val threads: Int,
    val successCount: Int,        // = "성공 응답 건수"
    val failureCount: Int,
    val remainingStock: Int,
    val expectedDecrease: Int,    // 성공 응답 기준 "기대되는 감소량"
    val actualDecrease: Int,      // 재고 기준 "실제 감소량"
    val hasGhostSuccess: Boolean  // "재고에 반영되지 않은 성공 응답 존재 여부"
)

data class LockConceptDemoResponse(
    val initialStock: Int,
    val localLockSuccessCount: Int,
    val localLockFinalStock: Int,
    val globalLockSuccessCount: Int,
    val globalLockFinalStock: Int
)

object DemoSharedStock {
    @Volatile
    var stock: Int = 0
}

class LocalLockDemoService {

    @Synchronized
    fun order(): Boolean {
        if (DemoSharedStock.stock <= 0) {
            return false
        }

        Thread.sleep(100)
        DemoSharedStock.stock -= 1
        return true
    }
}

class GlobalLockDemoService {

    companion object {
        private val lock = Any()
    }

    fun order(): Boolean {
        synchronized(lock) {
            if (DemoSharedStock.stock <= 0) {
                return false
            }
            Thread.sleep(100)
            DemoSharedStock.stock -= 1
            return true
        }
    }
}

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
        require(req.threads >= 1) { "동시 요청 수는 1 이상이어야 합니다." }

        val product = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

        val initialStock = product.stock
        val threads = req.threads
        val method = req.method

        // 실험에서는 "요청당 1개"만 주문하는 시나리오로 고정
        val quantity = 1

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

        // JPA 1차 캐시 비우고, 실제 최신 재고 읽기
        entityManager.clear()

        val finalProduct = productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("실험 중 상품이 사라졌습니다.") }

        val remaining = finalProduct.stock

        val expectedDecrease = successCount.get() * quantity
        val actualDecrease = initialStock - remaining
        // "재고에 반영되지 않은 성공 응답"이 하나 이상 있는지
        val hasGhostSuccess = expectedDecrease > actualDecrease

        val result = RunExperimentResult(
            method = method,
            initialStock = initialStock,
            threads = threads,
            successCount = successCount.get(),
            failureCount = failureCount.get(),
            remainingStock = remaining,
            expectedDecrease = expectedDecrease,
            actualDecrease = actualDecrease,
            hasGhostSuccess = hasGhostSuccess
        )

        return ResponseEntity.ok(result)
    }
}