package com.example.resilient_purchase.demo

class GlobalLockDemoService {

    companion object {
        private val lock = Any()
    }

    fun order(): Boolean {
        synchronized(lock) {
            if (DemoSharedStock.stock <= 0) {
                return false
            }
            Thread.sleep(100)
            DemoSharedStock.stock -= 1
            return true
        }
    }
}

