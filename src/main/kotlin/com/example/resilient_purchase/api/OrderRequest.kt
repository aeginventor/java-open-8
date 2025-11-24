package com.example.resilient_purchase.api

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

data class OrderRequest(
    @field:NotNull(message = "productId는 필수입니다.")
    val productId: Long?,

    @field:Min(value = 1, message = "quantity는 1 이상이어야 합니다.")
    val quantity: Int = 1,

    // lock / no-lock 중 선택, 기본값은 lock
    val method: String? = "lock"
)