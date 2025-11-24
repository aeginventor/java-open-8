package com.example.resilient_purchase.api

import com.example.resilient_purchase.service.OrderService
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/orders")
class OrderController(

    @Qualifier("noLockOrderService")
    private val noLockOrderService: OrderService,

    @Qualifier("lockOrderService")
    private val lockOrderService: OrderService,

    @Qualifier("pessimisticLockOrderService")
    private val pessimisticLockOrderService: OrderService
) {

    @PostMapping
    fun order(@Valid @RequestBody req: OrderRequest): ResponseEntity<Any> {
        val service = when (req.method) {
            "no-lock" -> noLockOrderService
            "pessimistic" -> pessimisticLockOrderService
            else -> lockOrderService   // 기본은 lock
        }

        val result = service.order(req.productId!!, req.quantity, req.method ?: "lock")
        return ResponseEntity.ok(result)
    }
}
