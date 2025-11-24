package com.example.resilient_purchase

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class PessimisticLockConcurrentTest(

    @Autowired val productRepository: ProductRepository,
    @Autowired val applicationContext: org.springframework.context.ApplicationContext
) {

    @Test
    fun `비관적 락 기반 동시성 테스트 - 재고와 성공 횟수의 합이 초기 재고와 동일해야 한다`() {
        // given
        val initialStock = 100
        val saved = productRepository.save(Product(name = "pessimistic-sample", stock = initialStock))
        val productId = saved.id!!

        val svc = applicationContext.getBean("pessimisticLockOrderService", OrderService::class.java)

        val threads = 200
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(50)

        val successCount = AtomicInteger(0)

        // when
        repeat(threads) {
            pool.submit {
                try {
                    try {
                        svc.order(productId, 1, "pessimistic")
                        successCount.incrementAndGet()
                    } catch (_: Exception) {
                        // 재고 부족 등은 실패로 간주하고 카운트하지 않음
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        pool.shutdown()

        // then
        val remaining = productRepository.findById(productId).get().stock
        println(
            "비관적 락 테스트 종료 — 남은 재고: $remaining, " +
                    "성공 횟수: ${successCount.get()}"
        )

        // 1) 재고는 음수가 되지 않아야 한다
        assertTrue(remaining >= 0)

        // 2) 성공한 주문 수 + 남은 재고 == 초기 재고
        assertEquals(initialStock, successCount.get() + remaining)
    }
}
