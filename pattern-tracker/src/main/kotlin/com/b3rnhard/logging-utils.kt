package com.b3rnhard

import com.bitwig.extension.controller.api.ControllerHost

fun <K1, K2, K3, V> logNestedMapSummary(
    host: ControllerHost,
    map: Map<K1, Map<K2, Map<K3, V>>>,
    logPrefix: String
) {
    val numParentGroups = map.size
    if (numParentGroups == 0) {
        host.println("$logPrefix: 0 items tracked.")
    } else {
        val numChildEntriesTotal = map.values.sumOf { parentMap -> parentMap.size }
        val totalGrandChildEntries = map.values.sumOf { parentMap ->
            parentMap.values.sumOf { childMap -> childMap.size }
        }

        val avgChildrenPerParent = numChildEntriesTotal.toDouble() / numParentGroups

        val avgGrandChildrenPerChild = if (numChildEntriesTotal > 0) {
            totalGrandChildEntries.toDouble() / numChildEntriesTotal
        } else {
            0.0
        }

        host.println(
            String.format(
                "%s: %d parent groups x %.2f avg children/group x %.2f avg grandchildren/child = %d total grandchildren entries",
                logPrefix,
                numParentGroups,
                avgChildrenPerParent,
                avgGrandChildrenPerChild,
                totalGrandChildEntries
            )
        )
    }
} 