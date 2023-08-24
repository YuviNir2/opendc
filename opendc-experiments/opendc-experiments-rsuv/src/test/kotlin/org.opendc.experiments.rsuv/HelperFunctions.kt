package org.opendc.experiments.rsuv

import com.fasterxml.jackson.core.JsonToken
import org.opendc.simulator.compute.power.CpuPowerModel
import org.opendc.simulator.compute.power.CpuPowerModels
import org.opendc.simulator.compute.power.NetworkPowerModel
import org.opendc.simulator.compute.power.NetworkPowerModels


var globalStartTime = 0L
var globalEndTime = 0L
var numOfVmsNeeded = 0

// Experiment Configs:
var envFileName = ""
var traceFileName = ""
var singlePlayerUsage = 0.0
var singlePlayerNetworkUsage = 0.0
var maxCores: Int = 0
var maxNics: Int = 0
var maxNumPlayersPerVm = 0
var vmMemoryCapacity = 0L
var cpuMaxPower = 0.0
var cpuIdlePower = 0.0
var cpuPowerModel = ""
var nicMaxPower = 0.0
var nicIdlePower = 0.0
var nicPowerModel = ""
var gameType: GameType = GameType.TBS
var dataRateLevel: DataRateLevel = DataRateLevel.Linear

fun getExperimentConfiguration(fileName: String) {
    val configFile = baseDir.resolve("experiment-configs/${fileName}.csv")
    val parser = factory.createParser(configFile)
    parser.schema = configSchema

    var env = ""
    var trace = ""
    var cpuUsage = 0.0
    var cores = 0
    var networkUsage = 0.0
    var nics = 0
    var maxPlayers = 0
    var memoryCapacity = 0L
    var cpuMax = 0.0
    var cpuIdle = 0.0
    var cpuModel = ""
    var nicMax = 0.0
    var nicIdle = 0.0
    var nicModel = ""
    var gameTypeLocal = ""
    var dataRate = ""

    while (!parser.isClosed) {
        val token = parser.nextValue()
        if (token == JsonToken.END_OBJECT) {
            envFileName = env
            traceFileName = trace
            singlePlayerUsage = cpuUsage
            maxCores = cores
            singlePlayerNetworkUsage = networkUsage
            maxNics = nics
            maxNumPlayersPerVm = maxPlayers
            vmMemoryCapacity = memoryCapacity
            cpuMaxPower = cpuMax
            cpuIdlePower = cpuIdle
            cpuPowerModel = cpuModel
            nicMaxPower = nicMax
            nicIdlePower = nicIdle
            nicPowerModel = nicModel
            gameType = getGameType(gameTypeLocal)
            dataRateLevel = getDataRateLevel(dataRate)
            break
        }

        when (parser.currentName) {
            "envFileName" -> env = parser.valueAsString
            "traceFileName" -> trace = parser.valueAsString
            "singlePlayerCpuUsage" -> cpuUsage = parser.valueAsDouble
            "maxCoresPerVm" -> cores = parser.valueAsInt
            "singlePlayerNetworkUsage" -> networkUsage = parser.valueAsDouble
            "maxNicsPerVm" -> nics = parser.valueAsInt
            "maxNumPlayersPerVm" -> maxPlayers = parser.valueAsInt
            "vmMemoryCapacity" -> memoryCapacity = parser.valueAsLong
            "cpuMaxPower" -> cpuMax = parser.valueAsDouble
            "cpuIdlePower" -> cpuIdle = parser.valueAsDouble
            "cpuPowerModel" -> cpuModel = parser.valueAsString
            "nicMaxPower" -> nicMax = parser.valueAsDouble
            "nicIdlePower" -> nicIdle = parser.valueAsDouble
            "nicPowerModel" -> nicModel = parser.valueAsString
            "gameType" -> gameTypeLocal = parser.valueAsString
            "dataRate" -> dataRate = parser.valueAsString
        }
    }
}

fun getGameType(gameType: String): GameType {
    return when (gameType) {
        "tbs" -> GameType.TBS
        "fps" -> GameType.FPS
        "mmorpg" -> GameType.MMORPG
        else -> GameType.TBS
    }
}

fun getDataRateLevel(dataRateLevel: String): DataRateLevel {
    return when (dataRateLevel) {
        "linear" -> DataRateLevel.Linear
        "square" -> DataRateLevel.Square
        "cubic" -> DataRateLevel.Cubic
        else -> DataRateLevel.Linear
    }
}


fun getCpuPowerModel(model: String, maxPower: Double, idlePower: Double): CpuPowerModel {
    return when (model) {
        "sqrt" -> CpuPowerModels.sqrt(maxPower, idlePower)
        "linear" -> CpuPowerModels.linear(maxPower, idlePower)
        "square" -> CpuPowerModels.square(maxPower, idlePower)
        "cubic" -> CpuPowerModels.cubic(maxPower, idlePower)
        "constant" -> CpuPowerModels.constant(maxPower)
        "zeroIdle" -> CpuPowerModels.zeroIdle(CpuPowerModels.linear(maxPower, idlePower))
        else ->  CpuPowerModels.linear(maxPower, idlePower)
    }
}

fun getNetworkPowerModel(model: String, maxPower: Double, idlePower: Double): NetworkPowerModel {
    return when (model) {
        "sqrt" -> NetworkPowerModels.sqrt(maxPower, idlePower)
        "linear" -> NetworkPowerModels.linear(maxPower, idlePower)
        "square" -> NetworkPowerModels.square(maxPower, idlePower)
        "cubic" -> NetworkPowerModels.cubic(maxPower, idlePower)
        "constant" -> NetworkPowerModels.constant(maxPower)
        "zeroIdle" -> NetworkPowerModels.zeroIdle(NetworkPowerModels.linear(maxPower, idlePower))
        else ->  NetworkPowerModels.linear(maxPower, idlePower)
    }
}
