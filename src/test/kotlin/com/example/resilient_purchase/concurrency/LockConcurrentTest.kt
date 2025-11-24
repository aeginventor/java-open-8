package com.example.resilient_purchase.concurrency

import com.example.resilient_purchase.fixture.TestFixture
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class LockConcurrentTest(
    @Autowired val productRepository: ProductRepository,
    @Autowired val applicationContext: org.springframework.context.ApplicationContext
) {

    @Test
    fun `lock 기반 동시성 테스트 - 재고와 성공 횟수의 합이 초기 재고와 동일해야 한다`() {
        val product = createProduct()
        val service = getLockOrderService()

        val testResult = TestFixture.runConcurrencyTest(service, product.id!!, THREAD_COUNT)

        val remaining = getRemainingStock(product.id!!)
        assertValidStockState(remaining, testResult.successCount)
    }

    private fun createProduct() = TestFixture.createTestProduct(
        productRepository, "lock-sample", INITIAL_STOCK
    )

    private fun getLockOrderService(): OrderService {
        return applicationContext.getBean("lockOrderService", OrderService::class.java)
    }

    private fun getRemainingStock(productId: Long) = productRepository.findById(productId).get().stock

    private fun assertValidStockState(remaining: Int, successCount: Int) {
        println("락 서비스 테스트 종료 — 남은 재고: $remaining, 성공 횟수: $successCount")
        assertTrue(remaining >= 0)
        assertEquals(INITIAL_STOCK, successCount + remaining)
    }

    companion object {
        private const val INITIAL_STOCK = 100
        private const val THREAD_COUNT = 200
    }
}
