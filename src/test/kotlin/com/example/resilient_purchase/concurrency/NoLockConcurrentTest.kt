package com.example.resilient_purchase.concurrency

import com.example.resilient_purchase.fixture.TestFixture
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class NoLockConcurrentTest(
    @Autowired val productRepository: ProductRepository,
    @Autowired val applicationContext: org.springframework.context.ApplicationContext
) {

    @Test
    fun `no-lock 동시성 오버셀 재현 테스트`() {
        val product = createProduct()
        val service = getNoLockOrderService()

        TestFixture.executeConcurrentOrders(service, product.id!!, THREAD_COUNT)

        val remaining = getRemainingStock(product.id!!)
        assertOversellOccurred(remaining)
    }

    private fun createProduct() = TestFixture.createTestProduct(
        productRepository, "concurrency-sample", INITIAL_STOCK
    )

    private fun getNoLockOrderService(): OrderService {
        return applicationContext.getBean("noLockOrderService", OrderService::class.java)
    }

    private fun getRemainingStock(productId: Long) = productRepository.findById(productId).get().stock

    private fun assertOversellOccurred(remaining: Int) {
        println("테스트 종료 — 남은 재고: $remaining")
        assertTrue(remaining < INITIAL_STOCK)
    }

    companion object {
        private const val INITIAL_STOCK = 100
        private const val THREAD_COUNT = 200
    }
}
