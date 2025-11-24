package com.example.resilient_purchase.api

import com.example.resilient_purchase.demo.DemoSharedStock
import com.example.resilient_purchase.demo.GlobalLockDemoService
import com.example.resilient_purchase.demo.LocalLockDemoService
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

    companion object {
        private const val QUANTITY_PER_ORDER = 1
    }

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

        val product = findTargetProduct()
        val initialStock = product.stock
        val service = selectOrderService(req.method)

        val (successCount, failureCount) = executeConcurrentOrders(
            service, targetProductId, QUANTITY_PER_ORDER, req.method, req.threads
        )

        val remainingStock = getFinalStock()
        val result = buildExperimentResult(
            req.method, initialStock, req.threads, successCount, failureCount, remainingStock
        )

        return ResponseEntity.ok(result)
    }

    private fun findTargetProduct() = productRepository.findById(targetProductId)
        .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

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

    private fun getFinalStock(): Int {
        entityManager.clear()
        return productRepository.findById(targetProductId)
            .orElseThrow { IllegalStateException("실험 중 상품이 사라졌습니다.") }
            .stock
    }

    private fun buildExperimentResult(
        method: String,
        initialStock: Int,
        threads: Int,
        successCount: Int,
        failureCount: Int,
        remainingStock: Int
    ): RunExperimentResult {
        val expectedDecrease = successCount * QUANTITY_PER_ORDER
        val actualDecrease = initialStock - remainingStock
        val hasGhostSuccess = expectedDecrease > actualDecrease

        return RunExperimentResult(
            method = method,
            initialStock = initialStock,
            threads = threads,
            successCount = successCount,
            failureCount = failureCount,
            remainingStock = remainingStock,
            expectedDecrease = expectedDecrease,
            actualDecrease = actualDecrease,
            hasGhostSuccess = hasGhostSuccess
        )
    }

    @GetMapping("/lock-concept-demo")
    fun lockConceptDemo(): ResponseEntity<LockConceptDemoResponse> {
        val initialStock = 1

        // 1) 로컬 락(@Synchronized, 인스턴스 기준) 시나리오
        DemoSharedStock.stock = initialStock

        val localService1 = LocalLockDemoService()
        val localService2 = LocalLockDemoService()

        val localSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
        val startLatch1 = java.util.concurrent.CountDownLatch(1)
        val doneLatch1 = java.util.concurrent.CountDownLatch(2)

        fun runLocal(service: LocalLockDemoService) = Thread {
            startLatch1.await()
            if (service.order()) {
                localSuccessCount.incrementAndGet()
            }
            doneLatch1.countDown()
        }

        val lt1 = runLocal(localService1)
        val lt2 = runLocal(localService2)
        lt1.start()
        lt2.start()

        startLatch1.countDown()
        doneLatch1.await()

        val localFinalStock = DemoSharedStock.stock

        // 2) 공유 락(전역 락 = 비관적 락 개념) 시나리오
        DemoSharedStock.stock = initialStock

        val globalService1 = GlobalLockDemoService()
        val globalService2 = GlobalLockDemoService()

        val globalSuccessCount = java.util.concurrent.atomic.AtomicInteger(0)
        val startLatch2 = java.util.concurrent.CountDownLatch(1)
        val doneLatch2 = java.util.concurrent.CountDownLatch(2)

        fun runGlobal(service: GlobalLockDemoService) = Thread {
            startLatch2.await()
            if (service.order()) {
                globalSuccessCount.incrementAndGet()
            }
            doneLatch2.countDown()
        }

        val gt1 = runGlobal(globalService1)
        val gt2 = runGlobal(globalService2)
        gt1.start()
        gt2.start()

        startLatch2.countDown()
        doneLatch2.await()

        val globalFinalStock = DemoSharedStock.stock

        val response = LockConceptDemoResponse(
            initialStock = initialStock,
            localLockSuccessCount = localSuccessCount.get(),
            localLockFinalStock = localFinalStock,
            globalLockSuccessCount = globalSuccessCount.get(),
            globalLockFinalStock = globalFinalStock
        )

        return ResponseEntity.ok(response)
    }
}