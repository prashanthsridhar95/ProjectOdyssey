package com.odyssey.data

import io.ktor.client.engine.HttpClientEngine

/** The HTTP engine is the one platform-specific piece of the data layer. */
expect fun defaultEngine(): HttpClientEngine
