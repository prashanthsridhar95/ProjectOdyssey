package com.odyssey.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

actual fun defaultEngine(): HttpClientEngine = Js.create()
