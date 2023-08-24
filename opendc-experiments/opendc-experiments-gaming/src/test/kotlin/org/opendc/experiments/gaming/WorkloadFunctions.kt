package org.opendc.experiments.gaming

import com.fasterxml.jackson.core.JsonToken
import org.opendc.experiments.compute.ExtendedVirtualMachine
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random


fun buildFragments(
    path: File,
    maxNumPlayers: Int,
    gameType: GameType,
    dataRateLevel: DataRateLevel
): Map<Int, FragmentBuilder> {
    val fragments = mutableMapOf<Int, FragmentBuilder>()
    val parser = factory.createParser(path)
    parser.schema = numPlayersSchema

    var timestampStart = 0L
    var timestampEnd = 0L
    var numPlayersEnd = 0

    while (!parser.isClosed) {
        val token = parser.nextValue()
        if (token == JsonToken.END_OBJECT) {
            if (timestampStart == 0L) {
                timestampStart = timestampEnd
                globalStartTime = timestampStart
                continue
            }

            var remainingPlayers = numPlayersEnd
            val numVms = ceil(numPlayersEnd.toDouble() / maxNumPlayers).toInt()
            if (numVms > numOfVmsNeeded) numOfVmsNeeded = numVms
            var i = 1
            while (remainingPlayers > maxNumPlayers) {
                val builder = fragments.computeIfAbsent(i) { FragmentBuilder() }
                val numPlayersOnVm = Random.nextInt(1, maxNumPlayers)
                val usage = getUsage(gameType, numPlayersOnVm, singlePlayerUsage)
                val networkUsage = getNetworkUsage(dataRateLevel, numPlayersOnVm, singlePlayerNetworkUsage)
                val cores = getNumCores(numPlayersOnVm.toDouble() / maxNumPlayers)
                builder.add(timestampStart, timestampEnd, usage, cores, networkUsage)
                remainingPlayers -= numPlayersOnVm
                if (numOfVmsNeeded < i) numOfVmsNeeded = i
                i++
            }
            val builder = fragments.computeIfAbsent(i) { FragmentBuilder() }
            val usage = getUsage(gameType, remainingPlayers, singlePlayerUsage)
            val networkUsage = getNetworkUsage(dataRateLevel, remainingPlayers, singlePlayerNetworkUsage)
            val cores = getNumCores(remainingPlayers.toDouble() / maxNumPlayers)
            builder.add(timestampStart, timestampEnd, usage, cores, networkUsage)

            timestampStart = timestampEnd

            continue
        }

        when (parser.currentName) {
            "timestamp" -> timestampEnd = parser.valueAsLong
            "avgPlayerCount" -> numPlayersEnd = parser.valueAsInt
        }
    }

    globalEndTime = timestampEnd
    return fragments
}


fun getNumCores(proportion: Double): Int {
    return when {
        proportion > 0.75 -> maxCores
        proportion > 0.5 -> maxCores / 2
        proportion > 0.25 -> maxCores / 4
        else -> 1
    }
}

fun getUsage(gameType: GameType, numPlayers: Int, singlePlayerUsage: Double): Double {
    return when (gameType) {
        GameType.TBS -> singlePlayerUsage * numPlayers
        GameType.FPS -> singlePlayerUsage.pow(numPlayers.toDouble())
        GameType.MMORPG -> singlePlayerUsage * (numPlayers + numPlayers.toDouble().pow(2.0))
    }
}

fun getNetworkUsage(dataRateLevel: DataRateLevel, numPlayers: Int, singlePlayerUsage: Double): Double {
    return when (dataRateLevel) {
        DataRateLevel.Linear -> singlePlayerUsage * numPlayers
        DataRateLevel.Square -> singlePlayerUsage * (numPlayers.toDouble().pow(2.0))
        DataRateLevel.Cubic -> singlePlayerUsage * (numPlayers.toDouble().pow(3.0))
    }
}


fun generateWorkload(
    fragments: Map<Int, FragmentBuilder>,
    maxNumPlayers: Int,
    gameType: GameType,
    dataRateLevel: DataRateLevel
): List<ExtendedVirtualMachine> {
    val vms = mutableListOf<ExtendedVirtualMachine>()
    var counter = 0
    val maxCpuCapacityNeeded = getUsage(gameType, maxNumPlayers, singlePlayerUsage)
    val maxNetworkCapacityNeeded = getNetworkUsage(dataRateLevel, maxNumPlayers, singlePlayerNetworkUsage)

    for (i in 1..numOfVmsNeeded) {
        if (!fragments.containsKey(i)) continue
        val builder = fragments.getValue(i)
        val totalLoad = builder.totalLoad
        val uid = UUID.nameUUIDFromBytes("$i-${counter++}".toByteArray())

        vms.add(
            ExtendedVirtualMachine(
                uid,
                i.toString(),
                maxCores,
                maxCpuCapacityNeeded,
                vmMemoryCapacity,
                maxNics,
                maxNetworkCapacityNeeded,
                totalLoad,
                Instant.ofEpochMilli(globalStartTime),
                Instant.ofEpochMilli(globalEndTime),
                builder.build(),
                null
            )
        )
    }

    return vms
}

