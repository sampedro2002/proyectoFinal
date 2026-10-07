package com.eatfood.control.mobile.util

import java.time.LocalDate
import java.time.ZoneId

val ECUADOR_ZONE: ZoneId = ZoneId.of("America/Guayaquil")

fun ecuadorToday(): LocalDate = LocalDate.now(ECUADOR_ZONE)
