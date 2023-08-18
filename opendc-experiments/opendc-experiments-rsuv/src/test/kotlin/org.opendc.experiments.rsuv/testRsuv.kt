/*
 * Copyright (c) 2020 AtLarge Research
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.opendc.experiments.rsuv

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.dataformat.csv.CsvFactory
import com.fasterxml.jackson.dataformat.csv.CsvParser
import com.fasterxml.jackson.dataformat.csv.CsvSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.opendc.compute.service.ComputeService
import org.opendc.compute.service.scheduler.FilterScheduler
import org.opendc.compute.service.scheduler.filters.ComputeFilter
import org.opendc.compute.service.scheduler.filters.RamFilter
import org.opendc.compute.service.scheduler.filters.VCpuFilter
import org.opendc.compute.service.scheduler.weights.CoreRamWeigher
 import org.opendc.experiments.compute.ExtendedVirtualMachine
 import org.opendc.experiments.rsuv.topology.clusterTopology
 import org.opendc.experiments.compute.registerComputeMonitor
 import org.opendc.experiments.compute.replayExtended
 import org.opendc.experiments.compute.setupComputeService
import org.opendc.experiments.compute.setupHosts
import org.opendc.experiments.compute.telemetry.ComputeMonitor
import org.opendc.experiments.compute.telemetry.table.HostTableReader
import org.opendc.experiments.compute.telemetry.table.ServiceTableReader
import org.opendc.experiments.compute.topology.HostSpec
 import org.opendc.experiments.provisioner.Provisioner
 import org.opendc.simulator.compute.power.CpuPowerModels
 import org.opendc.simulator.compute.power.NetworkPowerModels
 import org.opendc.simulator.compute.workload.SimTrace
import org.opendc.simulator.kotlin.runSimulation
import java.io.File
import java.time.Instant
 import java.util.UUID
 import kotlin.math.ceil
 import kotlin.math.max
import kotlin.random.Random

/**
 * An integration test suite for my experiments.
 */
class MyTest {
    /**
     * The monitor used to keep track of the metrics.
     */
    private lateinit var monitor: TestComputeMonitor

    /**
     * The [FilterScheduler] to use for all experiments.
     */
    private lateinit var computeScheduler: FilterScheduler

    // TODO: might need to add a weigher/filter for network components
    /**
     * Set up the experimental environment.
     */
    @BeforeEach
    fun setUp() {
        monitor = TestComputeMonitor()
        computeScheduler = FilterScheduler(
            filters = listOf(ComputeFilter(), VCpuFilter(3.0), RamFilter(1.0)),
            weighers = listOf(CoreRamWeigher(multiplier = 1.0))
        )
    }

    private val baseDir: File = File("src/test/resources")
    private val singlePlayerUsage = 1000.0
    private val singlePlayerNetworkUsage = 10.0
    private val maxCores: Int = 16
    private val maxNics: Int = 2
    private val maxNumPlayersPerVm = 50
    private var numOfVmsNeeded = 0
    private var globalStartTime = 0L
    private var globalEndTime = 0L
    private val vmMemoryCapacity = 96000L
    enum class GameType {
        TBS, FPS, MMORPG
    }

    enum class DataRateLevel {
        Linear, Square, Cubic
    }

    //play.inpvp.net_1000_pings
    /**
     * Test a small simulation setup.
     */
    @Test
    fun testSmall() = runSimulation {
        val seed = 1L
        val topology = createTopology("dav4")
        val workload = getWorkload("play.inpvp.net_30000_pings.csv")
        val monitor = monitor

        Provisioner(dispatcher, seed).use { provisioner ->
            provisioner.runSteps(
                setupComputeService(serviceDomain = "compute.opendc.org", { computeScheduler }),
                registerComputeMonitor(serviceDomain = "compute.opendc.org", monitor),
                setupHosts(serviceDomain = "compute.opendc.org", topology)
            )

            val service = provisioner.registry.resolve("compute.opendc.org", ComputeService::class.java)!!
            service.replayExtended(timeSource, workload, seed)
        }

        val file = baseDir.resolve("results/result.txt")
        file.bufferedWriter().use { writer ->
            writer.write("Number of VMs successfully deployed;idleTime;activeTime;stealTime;lostTime;cpuDemand;cpuLimit;energyUsage(Total Energy Consumption);powerUsage;cpuUtilization\n")
            writer.write("${monitor.attemptsSuccess};${monitor.idleTime};${monitor.activeTime};${monitor.stealTime};${monitor.lostTime};${monitor.cpuDemand};${monitor.cpuLimit};${monitor.totalEnergyUsage};${monitor.powerUsage};${monitor.cpuUtilization}\n")
        }

        println(
            "\nScheduler: \n" +
                "Number of VMs successfully deployed=${monitor.attemptsSuccess}\n" +
                "Failure=${monitor.attemptsFailure}\n" +
                "Error=${monitor.attemptsError}\n" +
                "Pending=${monitor.serversPending}\n" +
                "Active=${monitor.serversActive}\n" +
                "idleTime=${monitor.idleTime}\n" +
                "activeTime=${monitor.activeTime}\n" +
                "stealTime=${monitor.stealTime}\n" +
                "lostTime=${monitor.lostTime}\n" +
                "cpuDemand=${monitor.cpuDemand}\n" +
                "cpuLimit=${monitor.cpuLimit}\n" +
                "energyUsage(Total Energy Consumption)=${monitor.totalEnergyUsage}\n" +
                "powerUsage=${monitor.powerUsage}\n" +
                "cpuUtilization=${monitor.cpuUtilization}\n" +
                "nicUsage=${monitor.nicUsage}\n" +
                "nicUtilization=${monitor.nicUtilization}\n" +
                "nicDemand=${monitor.nicDemand}\n" +
                "nicLimit=${monitor.nicLimit}\n" +
                "uptime=${monitor.uptime} \n" +
                "downtime=${monitor.downtime} \n"
        )
    }

    private fun getWorkload(traceName: String) : List<ExtendedVirtualMachine> {
        val traceFile = baseDir.resolve("traces/$traceName")
        val fragments = buildFragments2(traceFile, maxNumPlayersPerVm, GameType.TBS, DataRateLevel.Cubic)
        return generateWorkload(fragments, maxNumPlayersPerVm , GameType.TBS)
    }

    /**
     * Obtain the topology factory for the test.
     */
    private fun createTopology(name: String = "topology"): List<HostSpec> {
        val stream = checkNotNull(object {}.javaClass.getResourceAsStream("/env/$name.txt"))
        return stream.use { clusterTopology(stream, CpuPowerModels.linear(350.0, 200.0), NetworkPowerModels.linear(86.0, 24.0)) }
    }

    class TestComputeMonitor : ComputeMonitor {
        var attemptsSuccess = 0
        var attemptsFailure = 0
        var attemptsError = 0
        var serversPending = 0
        var serversActive = 0

        override fun record(reader: ServiceTableReader) {
            attemptsSuccess = reader.attemptsSuccess
            attemptsFailure = reader.attemptsFailure
            attemptsError = reader.attemptsError
            serversPending = reader.serversPending
            serversActive = reader.serversActive
        }

        var idleTime = 0L
        var activeTime = 0L
        var stealTime = 0L
        var lostTime = 0L
        var totalEnergyUsage = 0.0
        var uptime = 0L
        var downtime = 0L
        var cpuLimit = 0.0
        var cpuUsage = 0.0
        var cpuDemand = 0.0
        var cpuUtilization = 0.0
        var powerUsage = 0.0
        var nicUsage = 0.0
        var nicDemand = 0.0
        var nicLimit = 0.0
        var nicUtilization = 0.0

        override fun record(reader: HostTableReader) {
            idleTime += reader.cpuIdleTime
            activeTime += reader.cpuActiveTime
            stealTime += reader.cpuStealTime
            lostTime += reader.cpuLostTime
            totalEnergyUsage += reader.powerTotal
            uptime += reader.uptime
            downtime += reader.downtime
            cpuLimit += reader.cpuLimit
            cpuUsage += reader.cpuUsage
            cpuDemand += reader.cpuDemand
            cpuUtilization += reader.cpuUtilization
            powerUsage += reader.powerUsage
            nicUsage += reader.nicUsage
            nicDemand += reader.nicDemand
            nicLimit += reader.nicLimit
            nicUtilization += reader.nicUtilization
        }
    }

    private val factory = CsvFactory()
        .enable(CsvParser.Feature.ALLOW_COMMENTS)
        .enable(CsvParser.Feature.TRIM_SPACES)

    private fun buildFragments2(path: File, maxNumPlayers: Int, gameType: GameType, dataRateLevel: DataRateLevel): Map<Int, FragmentBuilder> {
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
                val numVms = ceil(numPlayersEnd.toDouble()/maxNumPlayers).toInt()
                if (numVms > numOfVmsNeeded) numOfVmsNeeded = numVms
//                println("TimestampEnd=$timestampEnd Number of VM=$numVms")
                var i = 1;
                while (remainingPlayers > maxNumPlayers) {
                    val builder = fragments.computeIfAbsent(i) { FragmentBuilder() }
                    val numPlayersOnVm = Random.nextInt(1, maxNumPlayers)
                    val usage = getUsage(gameType, numPlayersOnVm, singlePlayerUsage)
                    val networkUsage = getNetworkUsage(dataRateLevel ,numPlayersOnVm, singlePlayerNetworkUsage)
                    val cores = getNumCores(numPlayersOnVm.toDouble()/maxNumPlayers)
//                    println("i=$i + numPlayersOnVm=$numPlayersOnVm + usage=$usage + networkUsage=$networkUsage + cores=$cores")
                    builder.add(timestampStart, timestampEnd, usage, cores, networkUsage)
                    remainingPlayers -= numPlayersOnVm
                    if (numOfVmsNeeded < i) numOfVmsNeeded = i
                    i++
                }
                val builder = fragments.computeIfAbsent(i) { FragmentBuilder() }
                val usage = getUsage(gameType, remainingPlayers, singlePlayerUsage)
                val networkUsage = getNetworkUsage(dataRateLevel ,remainingPlayers, singlePlayerNetworkUsage)
                val cores = getNumCores(remainingPlayers.toDouble()/maxNumPlayers)
//                println("i=$i + remainingPlayers=$remainingPlayers + usage=$usage + networkUsage=$networkUsage + cores=$cores")
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

    private fun getNumCores(proportion: Double) : Int {
        return when {
            proportion > 0.75 -> maxCores
            proportion > 0.5 -> maxCores/2
            proportion > 0.25 -> maxCores/4
            else -> 1
        }
    }

    private fun getUsage(gameType: GameType, numPlayers: Int, singlePlayerUsage: Double) : Double {
        return when (gameType) {
            GameType.TBS -> singlePlayerUsage * numPlayers
            GameType.FPS -> Math.pow(singlePlayerUsage, numPlayers.toDouble())
            GameType.MMORPG -> singlePlayerUsage * numPlayers + Math.pow(numPlayers.toDouble(), 2.0)
        }
    }

    private fun getNetworkUsage(dataRateLevel: DataRateLevel, numPlayers: Int, singlePlayerUsage: Double) : Double {
        return when (dataRateLevel) {
            DataRateLevel.Linear -> singlePlayerUsage * numPlayers
            DataRateLevel.Square -> singlePlayerUsage * (Math.pow(numPlayers.toDouble(), 2.0))
            DataRateLevel.Cubic -> singlePlayerUsage * (Math.pow(numPlayers.toDouble(), 3.0))
        }
    }

    private fun generateWorkload(fragments: Map<Int, FragmentBuilder>, maxNumPlayers: Int, gameType: GameType): List<ExtendedVirtualMachine> {
        val vms = mutableListOf<ExtendedVirtualMachine>()
        var counter = 0
        val maxCpuCapacityNeeded = getUsage(gameType, maxNumPlayers, singlePlayerUsage)
        val maxNetworkCapacityNeeded = getNetworkUsage(DataRateLevel.Cubic, maxNumPlayers, singlePlayerNetworkUsage)
        println("maxCpuCapacityNeeded $maxCpuCapacityNeeded maxNetworkCapacityNeeded $maxNetworkCapacityNeeded \n")

            for(i in 1..numOfVmsNeeded) {
            if (!fragments.containsKey(i)) continue
            val builder = fragments.getValue(i)
            val totalLoad = builder.totalLoad
            val uid = UUID.nameUUIDFromBytes("$i-${counter++}".toByteArray())
//                            println("adding VM:\n" +
//                    "UID $uid\n" +
//                    "ID $i\n" +
//                    "cpuCount $maxCores\n" +
//                    "cpuCapacity $maxCpuCapacityNeeded\n" +
//                    "memCapacity $vmMemoryCapacity\n" +
//                    "networkCapacity $maxNetworkCapacityNeeded\n" +
//                    "totalLoad $totalLoad\n" +
//                    "startTime $globalStartTime\n" +
//                    "stopTime $globalEndTime\n")
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

    private class FragmentBuilder {

        /**
         * The total load of the trace.
         */
        @JvmField var totalLoad: Double = 0.0

        /**
         * The internal builder for the trace.
         */
        private val builder = SimTrace.builder()

        /**
         * The deadline of the previous fragment.
         */
        private var previousDeadline = Long.MIN_VALUE

        /**
         * Add a fragment to the trace.
         *
         * @param timestamp Timestamp at which the fragment starts (in epoch millis).
         * @param deadline Timestamp at which the fragment ends (in epoch millis).
         * @param usage CPU usage of this fragment.
         * @param cores Number of cores used.
         */
        fun add(timestamp: Long, deadline: Long, usage: Double, cores: Int) {
            val duration = max(0, deadline - timestamp)
            totalLoad += (usage * duration) / 1000.0 // avg MHz * duration = MFLOPs

            if (timestamp != previousDeadline) {
                // There is a gap between the previous and current fragment; fill the gap
                builder.add(timestamp, 0.0, cores)
            }

            builder.add(deadline, usage, cores)
            previousDeadline = deadline
        }

        fun add(timestamp: Long, deadline: Long, usage: Double, cores: Int, networkUsage: Double) {
            val duration = max(0, deadline - timestamp)
            totalLoad += (usage * duration) / 1000.0 // avg MHz * duration = MFLOPs

            if (timestamp != previousDeadline) {
                // There is a gap between the previous and current fragment; fill the gap
                builder.add(timestamp, 0.0, cores, 0.0)
            }

//            println("adding network usage $networkUsage + and usage=$usage")
            builder.add(deadline, usage, cores, networkUsage)
            previousDeadline = deadline
        }

        /**
         * Build the trace.
         */
        fun build(): SimTrace = builder.build()
    }

        /**
         * The [CsvSchema] that is used to parse the trace file.
         */
    private val numPlayersSchema = CsvSchema.builder()
        .addColumn("timestamp", CsvSchema.ColumnType.NUMBER)
        .addColumn("avgPlayerCount", CsvSchema.ColumnType.NUMBER)
        .setAllowComments(true)
        .setUseHeader(true)
        .build()

//    private val fragmentsSchema = CsvSchema.builder()
//        .addColumn("id", CsvSchema.ColumnType.NUMBER)
//        .addColumn("timestamp", CsvSchema.ColumnType.NUMBER)
//        .addColumn("duration", CsvSchema.ColumnType.NUMBER)
//        .addColumn("cores", CsvSchema.ColumnType.NUMBER)
//        .addColumn("usage", CsvSchema.ColumnType.NUMBER)
//        .setAllowComments(true)
//        .setUseHeader(true)
//        .build()
//
//    /**
//     * The [CsvSchema] that is used to parse the meta file.
//     */
//    private val metaSchema = CsvSchema.builder()
//            .addColumn("id", CsvSchema.ColumnType.NUMBER)
//            .addColumn("startTime", CsvSchema.ColumnType.NUMBER)
//            .addColumn("stopTime", CsvSchema.ColumnType.NUMBER)
//            .addColumn("cpuCount", CsvSchema.ColumnType.NUMBER)
//            .addColumn("cpuCapacity", CsvSchema.ColumnType.NUMBER)
//            .addColumn("networkCapacity", CsvSchema.ColumnType.NUMBER)
//            .addColumn("memCapacity", CsvSchema.ColumnType.NUMBER)
//            .setAllowComments(true)
//            .setUseHeader(true)
//            .build()

//    private fun buildFragments(path : File): Map<Int, FragmentBuilder> {
//        val fragments = mutableMapOf<Int, FragmentBuilder>()
//        val parser = factory.createParser(path)
//        parser.schema = numPlayersSchema
//
//        var timestampStart = 0L
//        var timestampEnd = 0L
//        var numPlayersEnd = 0
//
//        while (!parser.isClosed) {
//            val token = parser.nextValue()
//            if (token == JsonToken.END_OBJECT) {
//                if (timestampStart == 0L) {
//                    timestampStart = timestampEnd
//                    continue
//                }
//
//                var remainingPlayers = numPlayersEnd
//                val numVms = Math.ceil(numPlayersEnd.toDouble()/maxNumPlayersPerVm).toInt()
////                println("TimestampEnd=$timestampEnd Number of VM=$numVms")
//                for(i in 1..numVms) {
//                    val builder = fragments.computeIfAbsent(i) { FragmentBuilder() }
//                    if (remainingPlayers > maxNumPlayersPerVm) {
//                        builder.add(timestampStart, timestampEnd, maxUsage, maxCores)
//                        remainingPlayers -= maxNumPlayersPerVm
//                    }
//                    else {
//                        val usage = maxUsage*remainingPlayers/maxNumPlayersPerVm
//                        val cores = getNumCores(remainingPlayers.toDouble()/maxNumPlayersPerVm)
////                        println("remainingPlayers= $remainingPlayers\n" +
////                            "semi usage= $usage\n" +
////                            "semi cores= $cores")
//                        builder.add(timestampStart, timestampEnd, usage, cores)
//                    }
//                }
//
////                println("NUM PLAYERS FUNCTION\n" +
////                    "numPlayersEnd $numPlayersEnd\n"+
////                    "timestampStart $timestampStart\n" +
////                    "timestampEnd $timestampEnd\n")
//
//                timestampStart = timestampEnd
//                timestampEnd = 0L
//                numPlayersEnd = 0
//
//                continue
//            }
//
//            when (parser.currentName) {
//                "timestamp" -> timestampEnd = parser.valueAsLong
//                "avgPlayerCount" -> numPlayersEnd = parser.valueAsInt
//            }
//        }
//
//        return fragments
//    }


//    private fun parseMeta(path: File, fragments: Map<Int, FragmentBuilder>): List<ExtendedVirtualMachine> {
//        val vms = mutableListOf<ExtendedVirtualMachine>()
//        var counter = 0
//
//        val parser = factory.createParser(path)
//        parser.schema = metaSchema
//
//        var id = 0
//        var startTime = 0L
//        var stopTime = 0L
//        var cpuCount = 0
//        var cpuCapacity = 0.0
//        var networkCapacity = 0.0
//        var memCapacity = 0.0
//
//        while (!parser.isClosed) {
//            val token = parser.nextValue()
//            if (token == JsonToken.END_OBJECT) {
//                if (!fragments.containsKey(id)) {
//                    continue
//                }
//                val builder = fragments.getValue(id)
//                val totalLoad = builder.totalLoad
//                val uid = UUID.nameUUIDFromBytes("$id-${counter++}".toByteArray())
////                println("adding VM:\n" +
////                    "UID $uid\n" +
////                    "ID $id\n" +
////                    "cpuCount $cpuCount\n" +
////                    "cpuCapacity $cpuCapacity\n" +
////                    "memCapacity $memCapacity\n" +
////                    "networkCapacity $networkCapacity\n" +
////                    "totalLoad $totalLoad\n" +
////                    "startTime $startTime\n" +
////                    "stopTime $stopTime\n")
//
//                vms.add(
//                    ExtendedVirtualMachine(
//                        uid,
//                        id.toString(),
//                        cpuCount,
//                        cpuCapacity,
//                        memCapacity.roundToLong(),
//                        networkCapacity,
//                        totalLoad,
//                        Instant.ofEpochMilli(startTime),
//                        Instant.ofEpochMilli(stopTime),
//                        builder.build(),
//                        null
//                    )
//                )
//
////                println("META\n" +
////                    "id $id\n" +
////                    "startTime $startTime\n" +
////                    "stopTime $stopTime\n" +
////                    "cpuCount $cpuCount\n" +
////                    "cpuCapacity $cpuCapacity\n"+
////                    "memCapacity $memCapacity\n")
//                id = 0
//                startTime = 0L
//                stopTime = 0
//                cpuCount = 0
//                cpuCapacity = 0.0
//                networkCapacity = 0.0
//                memCapacity = 0.0
//
//                continue
//            }
//
//            when (parser.currentName) {
//                "id" -> id = parser.valueAsInt
//                "startTime" -> startTime = parser.valueAsLong
//                "stopTime" -> stopTime = parser.valueAsLong
//                "cpuCount" -> cpuCount = parser.valueAsInt
//                "cpuCapacity" -> cpuCapacity = parser.valueAsDouble
//                "networkCapacity" -> networkCapacity = parser.valueAsDouble
//                "memCapacity" -> memCapacity = parser.valueAsDouble
//            }
//        }
//
//        return vms
//    }


//    private fun parseFragments(path: File): Map<Int, FragmentBuilder> {
//        val fragments = mutableMapOf<Int, FragmentBuilder>()
//
//        val parser = factory.createParser(path)
//        parser.schema = fragmentsSchema
//
//        var id = 0
//        var timestamp = 0L
//        var duration = 0L
//        var cores = 0
//        var usage = 0.0
//
//        while (!parser.isClosed) {
//            val token = parser.nextValue()
//            if (token == JsonToken.END_OBJECT) {
//                val builder = fragments.computeIfAbsent(id) { FragmentBuilder() }
//                val deadlineMs = timestamp
//                val timeMs = (timestamp - duration)
//                builder.add(timeMs, deadlineMs, usage, cores)
//
////                println("FRAGMENTS\n" +
////                    "id $id\n" +
////                    "timestamp $timestamp\n" +
////                    "duration $duration\n" +
////                    "cores $cores\n" +
////                    "usage $usage\n"+
////                    "timeMs $timeMs\n"+
////                    "deadlineMs $deadlineMs\n")
//                id = 0
//                timestamp = 0L
//                duration = 0
//                cores = 0
//                usage = 0.0
//
//                continue
//            }
//
//            when (parser.currentName) {
//                "id" -> id = parser.valueAsInt
//                "timestamp" -> timestamp = parser.valueAsLong
//                "duration" -> duration = parser.valueAsLong
//                "cores" -> cores = parser.valueAsInt
//                "usage" -> usage = parser.valueAsDouble
//            }
//        }
//
//        return fragments
//    }


    /**
     * A hardcoded trace generator for testing purposes
     */

//    private fun generateTrace(numOfVms: Int, startTime: Instant): List<VirtualMachine> {
//        val trace = mutableListOf<VirtualMachine>()
//        val sessionDurationInSeconds = 60*60*24
//        val stopTime = startTime.plusSeconds(sessionDurationInSeconds.toLong())
//        val simTrace = generateSimTrace(0, sessionDurationInSeconds, startTime, stopTime)
//
//        for (i in 1..numOfVms) {
//            val vm = VirtualMachine(
//                uid = UUID.randomUUID(),
//                name = "VM_$i",
//                cpuCount = 1,
//                cpuCapacity = 1000.0,
//                memCapacity = 4096,
//                totalLoad = Math.random() * 100,
//                startTime = startTime,
//                stopTime = stopTime,
//                trace = simTrace,
//                interferenceProfile = null
//            )
//            trace.add(vm)
//        }
//        return trace
//    }
//
//    private fun generateSimTrace(whichTrace: Int, sessionDuration: Int, startTime: Instant, stopTime: Instant) : SimTrace {
//        if (whichTrace == 0) {
//            val usageCol = doubleArrayOf(50.0, 200.0, 4000.0, 8000.0, 3000.0, 200.0)
//            val deadlineCol = longArrayOf(
//                startTime.plusSeconds((sessionDuration/24*0.5).toLong()).toEpochMilli(),
//                startTime.plusSeconds((sessionDuration/24*8).toLong()).toEpochMilli(),
//                startTime.plusSeconds((sessionDuration/24*4).toLong()).toEpochMilli(),
//                startTime.plusSeconds((sessionDuration/24*4).toLong()).toEpochMilli(),
//                startTime.plusSeconds((sessionDuration/24*3).toLong()).toEpochMilli(),
//                stopTime.toEpochMilli()
//            )
//            val coresCol = intArrayOf(1, 2, 64, 128, 32, 2)
//            val size = 6
//            return SimTrace(usageCol, deadlineCol, coresCol, size)
//        } else {
//            val usageCol = doubleArrayOf(50.0)
//            val deadlineCol = longArrayOf(startTime.toEpochMilli())
//            val coresCol = intArrayOf(1)
//            val size = 1
//            val trace = SimTrace(usageCol, deadlineCol, coresCol, size)
//            return trace
//        }
//    }

}
