package com.biz.teamsheet.model

import kotlin.enums.enumEntries

enum class Position {
    GK, DEF, MID, ATT;

    fun isValid(position: String): Boolean {
        return enumEntries<Position>().any { it.name.equals(position, ignoreCase = true) }
    }
}