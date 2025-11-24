package com.example.resilient_purchase.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class OrderServiceSelector(
    @Qualifier("noLockOrderService")
    private val noLockOrderService: OrderService,

    @Qualifier("lockOrderService")
    private val lockOrderService: OrderService,

    @Qualifier("pessimisticLockOrderService")
    private val pessimisticLockOrderService: OrderService
) {

    fun select(method: String): OrderService {
        return when (method) {
            "no-lock" -> noLockOrderService
            "pessimistic" -> pessimisticLockOrderService
            else -> lockOrderService
        }
    }
}

