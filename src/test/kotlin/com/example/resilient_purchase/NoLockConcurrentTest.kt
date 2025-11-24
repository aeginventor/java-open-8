package com.example.resilient_purchase

import com.example.resilient_purchase.domain.Product
import com.example.resilient_purchase.repository.ProductRepository
import com.example.resilient_purchase.service.OrderService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@SpringBootTest
class NoLockConcurrentTest(
    @Autowired val productRepository: ProductRepository,
    @Autowired val applicationContext: org.springframework.context.ApplicationContext
) {

    @Test
    fun `no-lock 동시성 오버셀 재현 테스트`() {
        // 초기 데이터 세팅
        val p = productRepository.save(Product(name = "concurrency-sample", stock = 100))
        val productId = p.id!!

        // noLockOrderService 빈을 직접 가져옴 (이 서비스는 다음 단계에서 구현)
        val svc = applicationContext.getBean("noLockOrderService", OrderService::class.java)

        val threads = 200
        val latch = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(50)

        repeat(threads) {
            pool.submit {
                try {
                    try {
                        svc.order(productId, 1, "no-lock")
                    } catch (e: Exception) {
                        // 실패 무시(오버셀/예외 발생 예상)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()

        val remaining = productRepository.findById(productId).get().stock
        println("테스트 종료 — 남은 재고: $remaining")
        // 기대: no-lock에서는 오버셀(음수 또는 0 미만 혹은 <100의 값)이 발생할 가능성 존재
        assertTrue(remaining < 100)
    }
}
