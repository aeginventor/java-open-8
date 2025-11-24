package com.example.resilient_purchase.demo

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

