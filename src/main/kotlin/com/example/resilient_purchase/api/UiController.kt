package com.example.resilient_purchase.api

import com.example.resilient_purchase.demo.DemoSharedStock
import com.example.resilient_purchase.demo.GlobalLockDemoService
import com.example.resilient_purchase.demo.LocalLockDemoService
import com.example.resilient_purchase.dto.LockConceptDemoResponse
import com.example.resilient_purchase.dto.ResetStockRequest
import com.example.resilient_purchase.dto.RunExperimentRequest
import com.example.resilient_purchase.dto.RunExperimentResult
import com.example.resilient_purchase.dto.StockResponse
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.ConcurrencyTestExecutor
import com.example.resilient_purchase.service.OrderServiceSelector
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@RestController
@RequestMapping("/ui")
class UiController(

    private val productRepository: ProductRepository,

    private val orderServiceSelector: OrderServiceSelector,

    @PersistenceContext
    private val entityManager: EntityManager,

    private val concurrencyTestExecutor: ConcurrencyTestExecutor
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

        val testResult = concurrencyTestExecutor.executeConcurrentOrders(
            service, targetProductId, QUANTITY_PER_ORDER, req.method, req.threads
        )

        val remainingStock = getFinalStock()
        val result = buildExperimentResult(
            req.method, initialStock, req.threads, testResult.successCount, testResult.failureCount, remainingStock
        )

        return ResponseEntity.ok(result)
    }

    private fun findTargetProduct() = productRepository.findById(targetProductId)
        .orElseThrow { IllegalStateException("기본 상품(ID=1)이 필요합니다. data.sql을 확인해주세요.") }

    private fun selectOrderService(method: String) = orderServiceSelector.select(method)


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

        val (localSuccessCount, localFinalStock) = runLocalLockExperiment(initialStock)
        val (globalSuccessCount, globalFinalStock) = runGlobalLockExperiment(initialStock)

        val response = LockConceptDemoResponse(
            initialStock = initialStock,
            localLockSuccessCount = localSuccessCount,
            localLockFinalStock = localFinalStock,
            globalLockSuccessCount = globalSuccessCount,
            globalLockFinalStock = globalFinalStock
        )

        return ResponseEntity.ok(response)
    }

    private fun runLocalLockExperiment(initialStock: Int): Pair<Int, Int> {
        DemoSharedStock.stock = initialStock
        val successCount = AtomicInteger(0)

        runDemoThreads(
            LocalLockDemoService(),
            LocalLockDemoService(),
            successCount
        ) { service -> service.order() }

        return Pair(successCount.get(), DemoSharedStock.stock)
    }

    private fun runGlobalLockExperiment(initialStock: Int): Pair<Int, Int> {
        DemoSharedStock.stock = initialStock
        val successCount = AtomicInteger(0)

        runDemoThreads(
            GlobalLockDemoService(),
            GlobalLockDemoService(),
            successCount
        ) { service -> service.order() }

        return Pair(successCount.get(), DemoSharedStock.stock)
    }

    private fun <T> runDemoThreads(
        service1: T,
        service2: T,
        successCount: AtomicInteger,
        orderAction: (T) -> Boolean
    ) {
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val thread1 = createDemoThread(startLatch, doneLatch, successCount) { orderAction(service1) }
        val thread2 = createDemoThread(startLatch, doneLatch, successCount) { orderAction(service2) }

        thread1.start()
        thread2.start()
        startLatch.countDown()
        doneLatch.await()
    }

    private fun createDemoThread(
        startLatch: CountDownLatch,
        doneLatch: CountDownLatch,
        successCount: AtomicInteger,
        orderAction: () -> Boolean
    ): Thread {
        return Thread {
            startLatch.await()
            if (orderAction()) {
                successCount.incrementAndGet()
            }
            doneLatch.countDown()
        }
    }
}